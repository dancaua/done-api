# Backend security and password recovery

This document describes the implemented controls, their limits, and the production configuration. It is not a guarantee against every attack. iOS remains local-only; no client authentication flow was changed in this work.

## HTTP authorization

`SecurityConfiguration` lists permitted routes **and HTTP methods**. Unknown routes/methods are denied, including for authenticated callers. The route contract is in `openapi.json` and tests check every private controller mapping without authentication.

| Routes | Access |
| --- | --- |
| POST `/api/v1/auth/register`, `/login`, `/refresh`, `/apple/challenge`, `/apple`, `/apple/delete-login`, `/forgot-password`, `/reset-password` under the auth prefix | Public, subject to admission/account limits and DTO validation |
| Public pages, their explicitly listed assets, public config/localization, health/liveness/readiness | GET/HEAD only |
| Account/profile/export/password/Apple linking, logout, households, appliances/programs, sessions/actions/history, activity, statistics, state and owned sharing | Valid bearer JWT and owner authorization in the service/repository |
| GET/HEAD `/api/shares/{token}` | Public read capability |
| POST `/api/shares` | Anonymous projection creation only when explicitly enabled; disabled in production by default |
| PUT/DELETE `/api/shares/{token}`, DELETE `/api/shares/by-command/{id}` | Separate private writer capability checked in `SharingService`; a public link does not grant write access |

JWT uses a fixed HS256 algorithm, issuer/audience checks, expiration/issuance constraints, and a database session check. Logout, password change and recovery invalidate existing access tokens through session revocation. Refresh tokens remain hashed, rotated and protected against reuse. Owner IDs come from the authenticated principal, never from request bodies. Existing owner filters and relational integrity constraints are retained.

Authentication uses explicit headers, not browser cookies. CSRF is disabled for that reason. No cross-origin browser access is enabled. If cookie authentication or another web origin is introduced, review CSRF/CORS deliberately rather than enabling a wildcard.

## Admission and resource limits

The admission filter is registered once per request, inside both Spring Security chains, **before JWT decoding/database work/BCrypt**. Rejections return structured errors, `Cache-Control: no-store` and, for 429/temporary overload, `Retry-After`.

| Control | Default |
| --- | --- |
| All requests per instance | 3,000/minute |
| Per remote IP | 240/minute |
| Writes per IP | 60/minute |
| Auth operations per IP | 40/5 minutes |
| Registration per IP | 5/5 minutes |
| Recovery request + reset per IP | 10/5 minutes |
| Authenticated account | 180/minute per instance, across IPs |
| Account exports | 2/5 minutes per account; one concurrent export per instance |
| Concurrent requests / expensive auth operations | 48 / 4 |
| In-memory buckets | 20,000, with expiration and fail-closed capacity checks |
| Persistent password login attempts | 10/5 minutes per normalized account |
| Persistent recovery requests | 3/5 minutes and 10/day per normalized address; throttled requests still return generic 202 |
| Request body | 65,536 bytes, including chunked requests |
| Headers / authorization header | 16 KB / 8,192 characters |
| JSON nesting / number / field name / string | 32 / 100 / 1,024 / 32,768 characters; duplicate keys and trailing documents rejected |
| PostgreSQL statement / lock / idle transaction | 15 / 5 / 20 seconds |
| SMTP connect/read/write | 5 seconds each |

`app.rate.*` contains configurable IP/global/concurrency defaults; account/export caps are intentionally small constants in `AccountRateFilter`. Production refuses to start with the admission limiter disabled. In-memory limits reset on process restart and apply per instance; account login/recovery limits live in PostgreSQL and are shared across instances. A multi-instance deployment must additionally enforce aggregate limits at a trusted edge gateway, or replace the local admission store with a shared low-latency limiter.

The configured stack exposes only Caddy. PostgreSQL/API ports are internal. Caddy limits bodies, headers and request duration. Tomcat limits connections, threads, queues, keepalive and upload stalls. Containers have CPU/memory/process budgets. Caddy's error logger redacts URI/headers; access logging is not enabled. Do not enable request/body/header debug logs for authentication, reset, or share routes.

**Volumetric DDoS must be handled upstream.** Use the hosting provider's DDoS service/CDN/WAF, rate rules on authentication/recovery, and a firewall allowing origin traffic only from the selected edge. Trust only that provider's verified proxy ranges; currently Tomcat trusts only the stack's Caddy IP and Caddy ignores client-supplied forwarding headers. Do not blindly trust `X-Forwarded-For` or publish the API port. Avoid destructive load testing against production.

## SQL injection and database permissions

Repository JPQL uses bound parameters and every new JDBC query uses placeholders. User text is never concatenated into SQL or used as an identifier/order expression. Negative tests store SQL-looking appliance names literally and verify that users/tables/ownership remain intact. Existing UUID validation, DTO validation, unknown-field rejection, owner predicates and FK constraints cover the tested access paths. This is code review plus regression evidence, not proof that all future code will be safe.

Production uses `done_runtime` for JPA/JDBC, with SELECT/INSERT/UPDATE/DELETE and sequence access only. It has no superuser, role/database creation, schema creation, ownership or RLS-bypass privileges. Flyway connects separately as the schema owner via `MIGRATION_DATABASE_USER` / `MIGRATION_DATABASE_PASSWORD`. An after-migrate callback removes runtime access to Flyway history. A production startup check rejects an overly privileged runtime principal.

`deploy/provision-runtime-role.sh` safely quotes the password with PostgreSQL `format(%L)`. It is idempotent, runs for a fresh production DB, and is also run by `scripts/production.sh` for existing volumes. Set **different** random `DATABASE_PASSWORD` (owner) and `RUNTIME_DATABASE_PASSWORD` values. Existing migrations V1–V9 retain their bytes and TIMESTAMP columns; V10 adds recovery tables. No Flyway repair, old migration rewrite or schema timestamp conversion is performed. JDBC recovery/rate/cleanup timestamps are bound explicitly as UTC LocalDateTime so TIMESTAMP comparisons do not depend on the host JVM timezone.

Do not grant additional roles, table ownership, schema CREATE or access to migration history to the runtime principal. Back up before upgrading an existing deployment and validate on a restored staging database first, especially if its V1 checksum differs from this repository's documented baseline.

## Password recovery contract

### Request a link

`POST /api/v1/auth/forgot-password`, no authentication:

```json
{"email":"alex@example.com"}
```

202 for known, unknown, Apple-only and account-throttled addresses:

```json
{
  "code":"recovery_requested",
  "detail":"If this address has a password account, a recovery email will arrive shortly."
}
```

Malformed input is 400; IP limits are 429; globally disabled recovery or a full delivery queue is 503. No response includes a reset token or identifies whether an account exists. HTTP processing normalizes, rate-limits and enqueues the address; account lookup and email delivery happen in the worker, avoiding an existence-dependent SMTP delay. This minimizes enumeration timing differences; it is not a claim of network-level constant-time execution. The existing registration endpoint still signals duplicate email conflicts and would require a separate registration/email-verification flow to eliminate that enumeration channel.

### Use the link

Email uses the account's language: en, ro, es, it, fr, de, pl, hi or ja. The link derives exclusively from trusted `PUBLIC_ORIGIN`, never Host/forwarded headers or a caller redirect:

```text
https://your-domain/reset-password?lang=ro#token=<43-character-capability>
```

The 256-bit random capability is valid for 15 minutes, stored only as SHA-256, and used once. The browser reads the fragment, immediately removes it from history, and keeps it only in memory. The reset page has a stricter CSP, no third-party scripts, `no-store` and `no-referrer`; loading it does not consume the token (email link scanners therefore cannot reset passwords). Do not add analytics to it. Reloading after the fragment has been cleared requires reopening the original email link.

`POST /api/v1/auth/reset-password`:

```json
{"token":"<43-character-capability>","newPassword":"A new long private password!"}
```

Success: **204**, no access/refresh token and no automatic login. Password requirements match registration: 12–64 UTF-16 code units, maximum 72 UTF-8 bytes for BCrypt. A valid reset atomically changes the hash, removes all recovery tokens/pending reset emails for that account, and revokes all sessions. Concurrent reuse has exactly one winner. Invalid/expired/used tokens return 400 with `code: invalid_reset_token`. An authenticated password change or account deletion also cancels recovery tokens and queued reset requests.

A separate confirmation email says that the password changed; it never contains the password. Apple-only accounts do not gain local password authentication through this flow; use Apple's account recovery. A linked account that already has a password may recover that password normally.

### Delivery and retention

`recovery_mail_queue` stores address, kind, timestamps, attempts and a claim lease, **no plaintext reset tokens or passwords**. Requests coalesce per address/kind. The queue is bounded to 1,000 entries, expires after one hour and allows at most three attempts with short backoff. Workers claim with `SKIP LOCKED` and a two-minute lease; a dedicated scheduler prevents slow SMTP from holding up session jobs. SMTP runs outside database transactions. A worker crash can cause duplicate email delivery; successful password reset invalidates all links. A delivery retry generates a fresh token, while previously sent links remain valid until expiry or successful reset.

Expired tokens/queue records are purged by housekeeping. Unknown/Apple-only addresses are discarded after worker lookup. Failed delivery increments `done.recovery.delivery.failures` without logging SMTP exceptions, addresses or message contents. Configure an internal metrics exporter/alerting for repeated failures and monitor mail deliverability with the SMTP provider. The automatic SMTP health probe is disabled so unauthenticated public health requests cannot open connections/login to the mail provider; HTTP readiness does not certify email deliverability. A 202 means accepted for processing, not guaranteed delivery.

## Local and production setup

For a local Java process:

```sh
docker compose --profile mail up -d mailpit
# In .env (loaded by scripts/dev.sh):
# RECOVERY_ENABLED=true
# RECOVERY_FROM=security@done.example
# SMTP_HOST=localhost
# SMTP_PORT=1025
./scripts/dev.sh
```

Mailpit inbox: `http://127.0.0.1:8025`. Web recovery: `http://127.0.0.1:8080/forgot-password`. For the local Docker API use `docker compose --profile mail up -d --build`; its SMTP host is the internal `mailpit` service. Mailpit ports bind to loopback only. Default local recovery is disabled until configured.

Production requires RECOVERY_FROM, SMTP_HOST/PORT/USERNAME/PASSWORD and verified STARTTLS (default port 587) or TLS (port 465, SMTP_SSL=true and SMTP_STARTTLS=false). SMTP authentication, certificate hostname validation, request limiting and delivery must stay enabled. Production configuration/preflight rejects missing settings. Use a verified sending domain and configure SPF/DKIM/DMARC with the provider. Real provider credentials, domain/operator details, backups and edge DDoS protection are deployment inputs; no public deployment or live email delivery was performed by this change.

## Regression checks and dependency audit

```sh
./scripts/test.sh
node scripts/check-site.mjs
./mvnw dependency:tree -DoutputType=json -DoutputFile=target/dependencies.json
python3 scripts/audit-dependencies.py
```

The dependency audit sends only third-party Maven coordinates/version numbers to OSV and saves findings under ignored `target/`. Exit 1 means advisories; exit 2 means unavailable/incomplete and must not be treated as a clean scan. OSV coverage and dependency checks do not replace configuration review, continuous upgrades or a penetration test. Dependabot configuration requests weekly Maven/Docker update PRs when enabled by GitHub for the repository.

Tests cover route authorization, owner isolation, real loopback SMTP delivery, token hashing/expiration/replay/concurrent use, session revocation, recovery cancellation, retry, account enumeration responses, rate-limit windows/concurrency/capacity, spoofed proxy headers, chunked oversized bodies, malformed JSON, SQL-looking text, JWT claim/signature rejection and security headers. Public pages and recovery copy have complete key sets in all nine languages.

References: [OWASP password recovery](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html), [SQL injection prevention](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html), [DoS prevention](https://cheatsheetseries.owasp.org/cheatsheets/Denial_of_Service_Cheat_Sheet.html), [Spring request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html), [Caddy server limits](https://caddyserver.com/docs/caddyfile/options), [OSV API](https://google.github.io/osv.dev/api/).

### Patched dependency baseline

The initial OSV scan reported 10 advisories across three runtime artifacts. The POM pins Tomcat **11.0.25** (from 11.0.24) and the Jackson 3 BOM **3.1.7** (from 3.1.5), staying on the existing release lines. These versions include the reported fixes; some advisories concern optional features this application does not enable, but the library versions are patched regardless. Keep the overrides until Spring Boot's managed versions include the same or newer fixes. See the [Jackson upstream advisory](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) and [Apache disclosure](https://lists.apache.org/thread/9v114xlpgbzrrbzz5vf9f6r2q4wnxwwj).

### Validation record — 9 October 2026

Java 21 + PostgreSQL 16: **82 tests passed**, including the existing application E2E suite and the new security/recovery checks. The final OSV query checked **107 runtime Maven packages and returned no matching advisories** after the patch overrides. Caddy configuration and the production preflight/Compose file validate; the container starts with a read-only filesystem/non-root user and `done_runtime`, whose effective DDL/admin/Flyway-history privileges were checked as absent. The browser recovery pages were inspected in Romanian and all nine language catalogs passed key/syntax checks. SMTP sending was exercised against a real loopback test SMTP server, not a live provider.
