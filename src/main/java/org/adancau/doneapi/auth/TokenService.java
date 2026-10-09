package org.adancau.doneapi.auth;

import java.time.*;
import java.util.*;
import org.adancau.doneapi.account.UserViews;
import org.adancau.doneapi.common.Crypto;
import org.adancau.doneapi.config.AppProperties;
import org.adancau.doneapi.persistence.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final JwtEncoder encoder;
  private final AppProperties props;
  private final Clock clock;
  private final AuthSessionRepository sessions;
  private final RefreshTokenRepository refresh;
  private final UserViews views;

  public TokenService(
      JwtEncoder encoder,
      AppProperties props,
      Clock clock,
      AuthSessionRepository sessions,
      RefreshTokenRepository refresh,
      UserViews views) {
    this.encoder = encoder;
    this.props = props;
    this.clock = clock;
    this.sessions = sessions;
    this.refresh = refresh;
    this.views = views;
  }

  public AuthDtos.Tokens create(UserEntity user) { return create(user,"password"); }
  public AuthDtos.Tokens create(UserEntity user, String method) {
    var s = new AuthSessionEntity();
    s.setUserId(user.getId());
    s.setAuthenticationMethod(method);
    s.setCreatedAt(clock.instant());
    s.setExpiresAt(clock.instant().plus(props.auth().refreshTtl()));
    sessions.save(s);
    return issue(user, s);
  }

  public AuthDtos.Tokens issue(UserEntity user, AuthSessionEntity session) {
    String raw = Crypto.randomToken();
    var r = new RefreshTokenEntity();
    r.setSessionId(session.getId());
    r.setTokenHash(Crypto.hash(raw));
    r.setExpiresAt(session.getExpiresAt());
    refresh.save(r);
    var now = clock.instant();
    var claims =
        JwtClaimsSet.builder()
            .issuer(props.auth().issuer())
            .audience(List.of(props.auth().audience()))
            .subject(user.getId().toString())
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(now.plus(props.auth().accessTtl()))
            .id(UUID.randomUUID().toString())
            .claim("sid", session.getId().toString())
            .build();
    String access =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return new AuthDtos.Tokens(
        "Bearer",
        access,
        props.auth().accessTtl().toSeconds(),
        raw,
        session.getExpiresAt(),
        views.view(user));
  }
}
