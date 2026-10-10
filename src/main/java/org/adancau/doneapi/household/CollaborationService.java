package org.adancau.doneapi.household;

import java.time.*;
import java.util.*;
import org.adancau.doneapi.account.AccountDtos.MutationResult;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.adancau.doneapi.site.SiteProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollaborationService {
  private final org.adancau.doneapi.push.PushService push;
  private final HouseholdAccess access;
  private final HouseholdMemberRepository members;
  private final HouseholdInvitationRepository invites;
  private final HouseholdRepository homes;
  private final UserRepository users;
  private final MutationService mutations;
  private final Clock clock;
  private final SiteProperties site;

  public CollaborationService(
      HouseholdAccess a,
      HouseholdMemberRepository m,
      HouseholdInvitationRepository i,
      HouseholdRepository h,
      UserRepository u,
      MutationService x,
      Clock c,
      SiteProperties s,
      org.adancau.doneapi.push.PushService p) {
    access = a;
    members = m;
    invites = i;
    homes = h;
    users = u;
    mutations = x;
    clock = c;
    site = s;
    push = p;
  }

  private HouseholdEntity owner(UUID actor, UUID home) {
    var h = access.home(actor, home);
    if (!h.getUserId().equals(actor)) throw ApiException.missing();
    return h;
  }

  private HouseholdInvitationEntity invitation(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw ApiException.missing();
    var i = invites.findByTokenHash(Crypto.hash(token)).orElseThrow(ApiException::missing);
    if (i.getRevokedAt() != null
        || i.getRedeemedAt() != null
        || !i.getExpiresAt().isAfter(clock.instant()))
      throw ApiException.conflict("invite_expired", "This invitation is no longer available.");
    return i;
  }

  private CollaborationDtos.Invitation view(HouseholdInvitationEntity i, String token) {
    var h = homes.findById(i.getHouseholdId()).orElseThrow(ApiException::missing);
    var u = users.findById(h.getUserId()).orElseThrow(ApiException::missing);
    return new CollaborationDtos.Invitation(
        i.getId(),
        h.getId(),
        h.getName(),
        h.getLocalizationKey(),
        u.getDisplayName(),
        site.origin().replaceAll("/$", "") + "/invite/" + token,
        i.getExpiresAt());
  }

  @Transactional(readOnly = true)
  public List<CollaborationDtos.Member> members(UUID actor, UUID home) {
    var h = access.home(actor, home);
    var list = new ArrayList<CollaborationDtos.Member>();
    var u = users.findById(h.getUserId()).orElseThrow(ApiException::missing);
    list.add(
        new CollaborationDtos.Member(
            u.getId(), u.getDisplayName(), "owner", h.getCreatedAt(), true));
    for (var m : members.findByHouseholdId(home)) {
      var user = users.findById(m.getUserId()).orElseThrow(ApiException::missing);
      list.add(
          new CollaborationDtos.Member(
              user.getId(),
              user.getDisplayName(),
              "member",
              m.getJoinedAt(),
              m.isNotificationsEnabled()));
    }
    return list;
  }

  public CollaborationDtos.Invitation create(UUID actor, UUID home, UUID key) {
    owner(actor, home);
    return mutations.executeShared(
        actor,
        home,
        key,
        "household.invite:" + home,
        Map.of(),
        CollaborationDtos.Invitation.class,
        u -> {
          owner(actor, home);
          if (members.countByHouseholdId(home) >= 9)
            throw ApiException.conflict("member_limit", "Maximum 10 people per home.");
          long pending =
              invites.findByHouseholdIdOrderByCreatedAtDesc(home).stream()
                  .filter(
                      i ->
                          i.getRedeemedAt() == null
                              && i.getRevokedAt() == null
                              && i.getExpiresAt().isAfter(clock.instant()))
                  .count();
          if (pending >= 5)
            throw ApiException.conflict("invite_limit", "Revoke an existing invitation first.");
          String token = Crypto.randomToken();
          var i = new HouseholdInvitationEntity();
          i.setHouseholdId(home);
          i.setTokenHash(Crypto.hash(token));
          i.setCreatedAt(clock.instant());
          i.setExpiresAt(clock.instant().plus(Duration.ofDays(7)));
          invites.saveAndFlush(i);
          return view(i, token);
        });
  }

  @Transactional(readOnly = true)
  public CollaborationDtos.Invitation preview(UUID actor, String token) {
    users.findById(actor).orElseThrow(ApiException::unauthorized);
    return view(invitation(token), token);
  }

  public CollaborationDtos.Joined accept(UUID actor, String token, UUID key) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw ApiException.missing();
    var candidate = invites.findByTokenHash(Crypto.hash(token)).orElseThrow(ApiException::missing);
    var home = homes.findById(candidate.getHouseholdId()).orElseThrow(ApiException::missing);
    return mutations.executeSharedChecked(
        actor,
        home.getId(),
        key,
        "household.join:" + candidate.getId(),
        Map.of(),
        CollaborationDtos.Joined.class,
        () -> {
          var i = invites.findByTokenHash(Crypto.hash(token)).orElseThrow(ApiException::missing);
          if (actor.equals(i.getRedeemedBy())
              && members.existsByHouseholdIdAndUserId(home.getId(), actor)) return;
          invitation(token);
        },
        u -> {
          var i = invites.findByTokenHash(Crypto.hash(token)).orElseThrow(ApiException::missing);
          if (actor.equals(i.getRedeemedBy())
              && members.existsByHouseholdIdAndUserId(home.getId(), actor))
            return new CollaborationDtos.Joined(home.getId());
          invitation(token);
          if (access.homes(actor).contains(home.getId()))
            throw ApiException.conflict("already_member", "You already belong to this home.");
          if (access.homes(actor).size() >= 20)
            throw ApiException.conflict("household_limit", "Maximum 20 homes.");
          if (members.countByHouseholdId(home.getId()) >= 9)
            throw ApiException.conflict("member_limit", "Maximum 10 people per home.");
          var m = new HouseholdMemberEntity();
          m.setHouseholdId(home.getId());
          m.setUserId(actor);
          m.setJoinedAt(clock.instant());
          members.saveAndFlush(m);
          i.setRedeemedAt(clock.instant());
          i.setRedeemedBy(actor);
          push.refreshHome(home.getId());
          return new CollaborationDtos.Joined(home.getId());
        });
  }

  @Transactional(readOnly = true)
  public List<CollaborationDtos.InviteSummary> invitations(UUID actor, UUID home) {
    owner(actor, home);
    return invites.findByHouseholdIdOrderByCreatedAtDesc(home).stream()
        .map(
            i ->
                new CollaborationDtos.InviteSummary(
                    i.getId(),
                    i.getCreatedAt(),
                    i.getExpiresAt(),
                    i.getRedeemedAt() != null,
                    i.getRevokedAt() != null))
        .toList();
  }

  public MutationResult revoke(UUID actor, UUID home, UUID id, UUID key) {
    owner(actor, home);
    return mutations.executeShared(
        actor,
        home,
        key,
        "household.invite.revoke:" + id,
        Map.of(),
        MutationResult.class,
        u -> {
          owner(actor, home);
          var i =
              invites
                  .findById(id)
                  .filter(v -> v.getHouseholdId().equals(home))
                  .orElseThrow(ApiException::missing);
          i.setRevokedAt(clock.instant());
          return new MutationResult(u.getRevision());
        });
  }

  public MutationResult remove(UUID actor, UUID home, UUID member, UUID key) {
    access.home(actor, home);
    return mutations.executeShared(
        actor,
        home,
        key,
        "household.member.remove:" + home + ":" + member,
        Map.of(),
        MutationResult.class,
        u -> {
          var h = access.home(actor, home);
          if (!actor.equals(h.getUserId()) && !actor.equals(member)) throw ApiException.missing();
          if (member.equals(h.getUserId()))
            throw ApiException.conflict("owner_cannot_leave", "The owner cannot leave the home.");
          members.findByHouseholdIdAndUserId(home, member).ifPresent(members::delete);
          members.flush();
          push.refreshHome(home);
          return new MutationResult(u.getRevision());
        });
  }

  public MutationResult settings(UUID actor, UUID home, UUID key, CollaborationDtos.Settings r) {
    return mutations.executeShared(
        actor,
        home,
        key,
        "household.member.settings:" + home,
        r,
        MutationResult.class,
        u -> {
          var m =
              members.findByHouseholdIdAndUserId(home, actor).orElseThrow(ApiException::missing);
          m.setNotificationsEnabled(r.notificationsEnabled());
          push.refreshHome(home);
          return new MutationResult(u.getRevision());
        });
  }
}
