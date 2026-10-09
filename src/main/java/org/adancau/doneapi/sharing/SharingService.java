package org.adancau.doneapi.sharing;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.adancau.doneapi.session.DomainViews;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.adancau.doneapi.appliance.ApplianceDtos.ProgramSnapshot;
import org.adancau.doneapi.site.SiteProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static org.adancau.doneapi.sharing.SharingDtos.*;

@Service
public class SharingService {
  private final SessionShareRepository shares;
  private final SessionRepository sessions;
  private final ApplianceRepository appliances;
  private final UserRepository users;
  private final DomainViews views;
  private final MutationService mutations;
  private final MessageCatalog catalog;
  private final JsonMapper json;
  private final Clock clock;
  private final SiteProperties site;
  private final SharingProperties props;
  private final JdbcTemplate jdbc;
  public SharingService(SessionShareRepository shares, SessionRepository sessions, ApplianceRepository appliances,
      UserRepository users, DomainViews views, MutationService mutations, MessageCatalog catalog, JsonMapper json,
      Clock clock, SiteProperties site, SharingProperties props, JdbcTemplate jdbc) {
    this.shares=shares;this.sessions=sessions;this.appliances=appliances;this.users=users;this.views=views;
    this.mutations=mutations;this.catalog=catalog;this.json=json;this.clock=clock;this.site=site;this.props=props;this.jdbc=jdbc;
  }
  private SessionShareEntity valid(String token) {
    var s=shares.findById(token).orElseThrow(ApiException::missing);
    active(s);return s;
  }
  private void active(SessionShareEntity s) {
    if (s.getRevokedAt()!=null || !s.getExpiresAt().isAfter(clock.instant()))
      throw new ApiException(HttpStatus.GONE,"share_expired","This link has expired or was revoked.");
  }
  private SessionShareEntity fresh() {
    var s=new SessionShareEntity(); s.setToken(Crypto.randomToken());s.setProjectionId(UUID.randomUUID());
    s.setCreatedAt(clock.instant());s.setPublishedAt(clock.instant());s.setExpiresAt(clock.instant().plus(props.ttl()));return s;
  }
  private LiveActivityShareDTO view(SessionShareEntity s) {
    SharedSnapshot snapshot;
    Instant published=s.getPublishedAt();
    if (s.getOwnerId()!=null) {
      var session=sessions.findByIdAndUserId(s.getSessionId(),s.getOwnerId()).orElseThrow(ApiException::missing);
      var a=appliances.findByIdAndUserId(session.getApplianceId(),s.getOwnerId()).orElseThrow(ApiException::missing);
      var user=users.findById(s.getOwnerId()).orElseThrow(ApiException::missing);
      var program=views.measuredSnapshot(session); if (program==null) program=views.programSnapshot(session);
      snapshot=new SharedSnapshot(1,s.getProjectionId(),a.getName(),a.getKind(),program,session.getMode(),
          session.getStartedAt(),session.getExpectedEnd(),session.getCompletedAt(),session.getCollectedAt(),session.getCanceledAt(),clock.instant(),user.getLanguage());
      published=clock.instant(); // This view is read from the authoritative current DB state.
    } else snapshot=json.readValue(s.getSnapshotJson(),SharedSnapshot.class);
    String status=snapshot.canceledAt()!=null ? "canceled" : snapshot.collectedAt()!=null ? "collected" :
        snapshot.completedAt()!=null ? "finished" : snapshot.expectedEnd()!=null && !snapshot.expectedEnd().isAfter(clock.instant()) ? "due" : "running";
    return new LiveActivityShareDTO(site.origin().replaceAll("/$","")+"/share/"+s.getToken(),snapshot,status,published,clock.instant(),s.getExpiresAt(),300);
  }
  @Transactional(readOnly=true)
  public LiveActivityShareDTO get(String token) { return view(valid(token)); }
  public LiveActivityShareDTO createOwned(UUID owner, UUID sessionId, UUID key) {
    return mutations.execute(owner,key,"session.share:"+sessionId,Map.of(),LiveActivityShareDTO.class,user -> {
      sessions.findByIdAndUserId(sessionId,owner).orElseThrow(ApiException::missing);
      var existing=shares.findByOwnerIdAndSessionIdAndRevokedAtIsNull(owner,sessionId);
      if (existing.isPresent()) {
        if (existing.get().getExpiresAt().isAfter(clock.instant())) return view(existing.get());
        existing.get().setRevokedAt(clock.instant());shares.flush();
      }
      if (shares.findByOwnerIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(owner,clock.instant()).size()>=500)
        throw ApiException.conflict("share_limit","Maximum 500 current shared sessions per account.");
      var s=fresh();s.setOwnerId(owner);s.setSessionId(sessionId);shares.saveAndFlush(s);return view(s);
    });
  }
  @Transactional(readOnly=true)
  public List<LiveActivityShareDTO> list(UUID owner) {
    return shares.findByOwnerIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(owner,clock.instant()).stream().map(this::view).toList();
  }
  public MutationResult revokeOwned(UUID owner,String token,UUID key) {
    return mutations.execute(owner,key,"session.share.revoke:"+token,Map.of(),MutationResult.class,user -> {
      var s=shares.findById(token).filter(v -> owner.equals(v.getOwnerId())).orElseThrow(ApiException::missing);
      s.setRevokedAt(clock.instant());shares.flush();return new MutationResult(user.getRevision());
    });
  }
  private void anonymous() {
    if (!props.allowAnonymous()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"local_sharing_disabled","Local projection publishing is disabled.");
  }
  private SharedSnapshot validate(SharedSnapshot s) {
    String name=Names.clean(s.applianceName(),40,true);
    if (s.program()==null) throw ApiException.invalid("Program is required.");
    String programName=Names.clean(s.program().name(),60,true);
    int minutes=s.program().minutes();
    if (minutes<0 || minutes>1440 || (s.program().localizationKey()!=null && !catalog.contains(s.program().localizationKey())))
      throw ApiException.invalid("Invalid program.");
    var dates=Arrays.asList(s.startedAt(),s.expectedEnd(),s.completedAt(),s.collectedAt(),s.canceledAt(),s.capturedAt());
    for (var d:dates) if (d!=null && (d.isBefore(Instant.EPOCH) || d.isAfter(Instant.parse("2100-01-01T00:00:00Z"))))
      throw ApiException.invalid("Timestamp out of range.");
    if (s.startedAt().isAfter(s.capturedAt()) || s.capturedAt().isAfter(clock.instant().plusSeconds(300))) throw ApiException.invalid("Invalid capture time.");
    for (var d:Arrays.asList(s.completedAt(),s.canceledAt())) if (d!=null && (d.isBefore(s.startedAt()) || d.isAfter(s.capturedAt()))) throw ApiException.invalid("Invalid completion time.");
    if (s.completedAt()!=null && s.canceledAt()!=null || s.collectedAt()!=null && (s.completedAt()==null || s.collectedAt().isBefore(s.completedAt()) || s.collectedAt().isAfter(s.capturedAt()))) throw ApiException.invalid("Invalid session state.");
    if (s.timingMode()==org.adancau.doneapi.session.TimerMode.countdown ? (minutes<1 || s.expectedEnd()==null || !s.expectedEnd().isAfter(s.startedAt())) : s.expectedEnd()!=null)
      throw ApiException.invalid("Invalid timer mode.");
    return new SharedSnapshot(1,s.id(),name,s.kind(),new ProgramSnapshot(programName,minutes,s.program().localizationKey(),s.program().isCustom()),
        s.timingMode(),s.startedAt(),s.expectedEnd(),s.completedAt(),s.collectedAt(),s.canceledAt(),s.capturedAt(),s.language());
  }
  private void authorize(SessionShareEntity s,String header) {
    String key=header!=null && header.matches("Bearer [A-Za-z0-9_-]{43}") ? header.substring(7) : null;
    if (key==null || s.getWriterHash()==null || !MessageDigest.isEqual(Crypto.hash(key).getBytes(java.nio.charset.StandardCharsets.US_ASCII),s.getWriterHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new ApiException(HttpStatus.FORBIDDEN,"forbidden","Invalid write capability.");
  }
  @Transactional
  public LiveActivityShareDTO createProjection(CreateProjection r) {
    anonymous();var snapshot=validate(r.snapshot());
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))","done-share-create");
    var existing=shares.findByCommandId(r.commandId());
    if (existing.isPresent()) { authorize(existing.get(),"Bearer "+r.writeKey());active(existing.get());return view(existing.get()); }
    shares.deleteExpired(clock.instant());
    if (shares.countByOwnerIdIsNull()>=props.maximumAnonymousShares()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"share_capacity","Sharing capacity reached.");
    var s=fresh();s.setCommandId(r.commandId());s.setWriterHash(Crypto.hash(r.writeKey()));s.setSnapshotJson(json.writeValueAsString(snapshot));
    s.setRevision(r.revision());s.setCapturedAt(snapshot.capturedAt());shares.saveAndFlush(s);return view(s);
  }
  @Transactional
  public LiveActivityShareDTO updateProjection(String token,String header,UpdateProjection r) {
    anonymous();var s=shares.lockByToken(token).orElseThrow(ApiException::missing);active(s);authorize(s,header);
    var snapshot=validate(r.snapshot());
    if (r.revision()<s.getRevision() || r.revision()==s.getRevision() && snapshot.capturedAt().isBefore(s.getCapturedAt()))
      throw ApiException.conflict("stale_update","A newer update is already stored.");
    s.setSnapshotJson(json.writeValueAsString(snapshot));s.setRevision(r.revision());s.setCapturedAt(snapshot.capturedAt());s.setPublishedAt(clock.instant());shares.flush();return view(s);
  }
  @Transactional
  public void revokeProjection(String token,String header) {
    var s=shares.lockByToken(token).orElseThrow(ApiException::missing);authorize(s,header);s.setRevokedAt(clock.instant());shares.flush();
  }
  @Transactional
  public void revokeCommand(UUID command,String header) {
    var token=shares.findTokenByCommandId(command).orElseThrow(ApiException::missing);
    revokeProjection(token,header);
  }
}
