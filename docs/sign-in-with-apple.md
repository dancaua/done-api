# Sign in with Apple — configuration

The native iOS integration uses `ro.done.app`. The backend already verifies Apple's identity token signature, issuer, audience and one-use challenge nonce, exchanges the authorization code, stores an encrypted refresh token and revokes Apple authorization before deleting an account. No private key belongs in the app. Placeholder configuration never bypasses verification.

1. Enable **Sign in with Apple** for App ID `ro.done.app` in Apple Developer. Add the capability to the provisioning profile (automatic signing can recreate it). The iOS target includes `com.apple.developer.applesignin = [Default]`.
2. Create a Sign in with Apple key for the primary App ID, download the `.p8` once, and use a server secret file or encrypted runtime variable. Use the real Team ID and Key ID.
3. Replace the values in `deploy/apple.env.example`. Generate `APPLE_ENCRYPTION_KEY` with `openssl rand -base64 32`; keep the value stable to decrypt existing refresh tokens. This is separate from `JWT_SECRET`. `APPLE_PRIVATE_KEY_PATH` must point to an actual readable PEM file inside the deployed server/container. Alternatively, `APPLE_PRIVATE_KEY_BASE64` accepts Base64 of the complete PEM file (including BEGIN/END lines) and takes precedence over the path. Never commit it.
4. Set `APPLE_ENABLED=true` only after these values are real. In DigitalOcean App Platform set `APPLE_PRIVATE_KEY_BASE64` and `APPLE_ENCRYPTION_KEY` as encrypted **runtime** environment variables. A mounted key file is supported for Docker deployments. A local path on a Mac is not a deployed path.
5. Native iOS needs no Services ID/redirect URL. Optional browser account deletion requires a Services ID, registered return URL `https://<PUBLIC_ORIGIN>/delete-account`, `APPLE_WEB_CLIENT_ID` and `APPLE_WEB_REDIRECT_URI`.

The iOS flow requests `POST /api/v1/auth/apple/challenge`, passes its **already hashed** nonce unchanged to AuthenticationServices, then sends `challengeId`, `identityToken`, `authorizationCode`, optional first-authorization `displayName` and chosen `language` to `/auth/apple`. Apple supplies name/email only when authorized; private relay addresses and missing email are supported. Apple password is never sent to DONE. The native Apple subject is kept with the session in device-only Keychain storage, survives refresh rotation, and is checked at restore/foreground and on native revocation notifications. Revoked/not-found authorization closes the local session; temporary Apple availability errors do not erase cached data.

Existing password accounts are explicitly linked through `/me/identities/apple` after fresh password authentication. Identical email addresses never trigger automatic merging. Apple-only deletion reauthenticates through `/auth/apple/delete-login`, checks the returned account ID against the currently signed-in account before replacing credentials, then calls `DELETE /me`; server revocation must succeed first.

## Verification with real credentials

On a signed physical device/TestFlight: first sign-in with Share My Email; first sign-in with Hide My Email; returning sign-in where name is absent; cancellation; existing-account linking; wrong-Apple-account deletion (must leave original account unchanged); successful Apple-only deletion and revoked authorization; restart with saved Keychain session. Mock gateway tests exercise the server protocol; they do not replace a real Apple authorization test.

## Public launch

`/terms` (alias `/disclaimer`), `/privacy`, `/support` explain manual timers, no appliance connection/control/sensing, estimated status, notification limits and shared-link visibility in all nine languages. The same disclosure is available in iOS authentication, onboarding and Settings/help. These are scope/safety explanations, not a guarantee against claims or a blanket waiver of mandatory consumer rights.

Replace mock operator, address, support/privacy email, hosting/processors and retention configuration with actual operational facts. Have a qualified lawyer review the applicable jurisdictions, privacy lawful basis/transfers and retention, and distribution terms before public launch. Review marketing metadata/screenshots so they do not imply physical appliance control or guaranteed completion detection. Confirm that production deletion, shared-link revocation, backup expiry and support workflows match the notices. Review Apple's server-to-server account-change notification requirements for the target distribution; this integration currently relies on server authentication/revocation and does not introduce an Apple account-change webhook.

References: [Apple account deletion](https://developer.apple.com/help/app-review/guideline-reference/5-1-1-account-deletion), [Sign in with Apple](https://developer.apple.com/sign-in-with-apple/), [EU unfair contract terms](https://europa.eu/youreurope/citizens/consumers/unfair-treatment/unfair-contract-terms/index_en.htm).


## Local and DigitalOcean setup

The local signing key is kept in ignored `secrets/` with mode 0600. `config/local.properties` and `.env` provide matching local runtime settings. Do not add private keys or encryption keys to application resources, the Swift app, Git or Docker build arguments. The `.dockerignore` excludes secrets and local configuration.

A local-only `secrets/apple-digitalocean.env` contains the runtime settings ready to enter into DigitalOcean. Set `APPLE_PRIVATE_KEY_BASE64` and `APPLE_ENCRYPTION_KEY` as encrypted secrets. `APPLE_ENABLED=true`, `APPLE_CLIENT_ID=ro.done.app`, `APPLE_TEAM_ID` and `APPLE_KEY_ID` are separate runtime variables. Preserve the encryption key across deploys to decrypt stored Apple refresh tokens. Do not place a Mac file path in cloud configuration; the Base64 option needs no deployed file. APNs remains independent and can stay disabled. Native auth needs the primary App ID capability and signing profile updated in Apple Developer.

Real key parsing and backend startup can be checked locally without sending the private key to Apple. Actual authorization and account access still require a signed iPhone/TestFlight run with the real App ID/key permissions.
