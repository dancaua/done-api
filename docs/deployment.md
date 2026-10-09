# Pregătire deploy DONE

## Ce rulează

O singură aplicație Spring Boot: REST, paginile `/privacy`, `/support`, `/contact`, `/delete-account`, statusul `/share/{token}` și workerul de evenimente. PostgreSQL 16 stochează datele; Flyway V1–V9 rulează la startup, Hibernate validează. iOS rămâne local până la integrarea ulterioară.

Operatorul, adresa, emailurile și hostingul sunt mock numai în mediul local. Profilul `prod` refuză mockurile și originurile fără HTTPS. Nu sunt publicate date inventate.

## Local, pentru review

```bash
./scripts/dev.sh
# sau
./scripts/init-env.sh
docker compose up --build
```

Paginile apar la `http://127.0.0.1:8080`. Selectorul are toate cele 9 limbi. Ștergerea web folosește un cont existent: login, export opțional, confirmare explicită, DELETE /me. Nu creează un cont pentru ștergere și nu salvează credentiale în localStorage/cookies. Ștergerea unui cont backend nu șterge automat datele locale ale unui iPhone încă neconectat; acest lucru este explicat în pagină.

## Producție cu HTTPS

Necesită host Linux cu Docker/Compose, domeniu real și DNS spre host, porturile 80/443 disponibile. Configurația production este separată de cea locală: API-ul și DB nu publică porturi, Caddy termină TLS, iar API-ul are filesystem read-only și UID 10001.

```bash
cp .env.production.example .env.production
chmod 600 .env.production
# Completează datele reale și secretele folosind un secret manager.
# JWT_SECRET: base64 a minimum 32 octeți random.
# APPLE_ENCRYPTION_KEY: base64 a exact 32 octeți random, dacă Apple este activ.
python3 scripts/preflight.py --env-file .env.production
./scripts/production.sh
```

Nu comite `.env.production` sau cheia P8. Secretele sunt injectate la runtime, nu copiate în imagine. Păstrează cheia de criptare Apple stabilă, într-un secret manager cu backup separat; rotația ei fără recriptare ar împiedica revocarea identităților existente. JWT și cheia Apple trebuie să fie distincte.

Compose rezervă subnetul `172.30.80.0/24`; dacă hostul îl folosește deja, alege alt subnet și actualizează și IP-ul Caddy și `TRUSTED_PROXY_REGEX`. Numai Caddy este proxy de încredere; nu activa forwarding global pentru adrese arbitrare. Caddy elimină încrederea în header-ele forwarded ale clientului. Nu activa access logs care includ URL-uri de partajare sau tokenuri.

După deploy, verifică `/actuator/health/readiness`, `/actuator/health/liveness`, cele patru pagini, login, lifecycle, partajare și ștergerea unui **cont de test creat pentru această verificare**. Preflight verifică valorile și lungimea cheilor; nu probează DNS, Apple, emailurile sau caracterul random al secretelor. Exemplele production sunt intenționat incomplete și trebuie să eșueze preflight.

Pentru alt hosting, folosește imaginea Docker/JAR cu `SPRING_PROFILES_ACTIVE=prod`, PostgreSQL 16 și un reverse proxy HTTPS explicit de încredere. Păstrează probele readiness numai după conectarea DB. Serviciul are graceful shutdown de 20 secunde și containerul așteaptă 30 secunde.

## Apple nativ și ștergere web

Sign in with Apple este dezactivat implicit până la credențiale reale. Configurează App ID/Team ID/Key ID/P8 și cheia de criptare. Pentru ștergere web a conturilor Apple, înregistrează un **Services ID** grupat cu App ID-ul nativ, domeniul și return URL-ul exact `https://domeniu/delete-account`, apoi setează:

```dotenv
APPLE_WEB_CLIENT_ID=services_id_real
APPLE_WEB_REDIRECT_URI=https://domeniu_real/delete-account
```

Backendul acceptă numai App ID-ul nativ sau Services ID-ul configurat, verifică nonce/audience/issuer/semnături și păstrează clientul identității pentru revocare. Loginul de ștergere (`/auth/apple/delete-login`) cere identitate deja asociată; nu înregistrează utilizatori noi. Ștergerea cere parola curentă sau login Apple verificat în ultimele 5 minute, inclusiv pentru un cont cu ambele metode. Revocarea Apple trebuie să reușească înainte de eliminarea contului.

[Testele criptografice](../src/test/java/org/adancau/doneapi/AppleGatewayTests.java) verifică native/web cu server Apple simulat local; un cont Apple real și callbackul public trebuie validate pe domeniul final. Referințe: [configurarea web Apple](https://developer.apple.com/help/account/capabilities/configure-sign-in-with-apple-for-the-web), [gruparea identificatorilor](https://developer.apple.com/help/account/capabilities/group-apps-for-sign-in-with-apple/), [ștergerea contului](https://developer.apple.com/support/offering-account-deletion-in-your-app/).

## Backup, restore și ștergere

Producția necesită backup automat pe storage criptat, în afara hostului și a volumului live. Pentru o arhivă PostgreSQL cu permisiuni restrictive:

```bash
BACKUP_DIR=/cale/storage-criptat ./scripts/backup.sh
```

Scriptul face pg_dump și verifică arhiva; nu configurează un scheduler, nu criptează singur storage-ul și nu restaurează peste DB live. Operatorul stabilește frecvența, verifică backupurile, aplică rotația în limita `BACKUP_RETENTION_DAYS` și configurează alerte pentru eșec. Păstrează separat un registru operațional protejat al cererilor de ștergere pentru reconcilierea backupurilor restaurate, cu retenție limitată.

Restore-ul se testează periodic într-o bază izolată, cu aceeași versiune PostgreSQL și cod compatibil. Înainte ca o restaurare să servească trafic, reaplică ștergerile și revocările ulterioare backupului din registrul operațional. Nu reactiva automat identități, tokenuri sau linkuri șterse. Fără această procedură validată, nu declara politica de backup pregătită pentru producție.

Fă backup înainte de migrare. Nu edita migrări Flyway deja aplicate și nu activa `clean` sau Hibernate create/update. După o migrare incompatibilă, rollbackul codului necesită verificarea compatibilității schemei; restaurarea unui backup cere reconciliere înainte de acces public.

## Verificare reproductibilă

```bash
./scripts/test.sh                  # PostgreSQL 16 izolat, Testcontainers
./scripts/update-openapi.sh        # contract generat din rute + records
node scripts/check-site.mjs        # sintaxă/cataloguri publice
./mvnw -B verify
```

Numărul efectiv de teste și verificările rulate sunt în README. Testele nu folosesc DB reală a aplicației. Pentru `TEST_DATABASE_URL`, folosește exclusiv o bază dispensabilă: este golită între cazuri.

## Status lansare

Codul backend și paginile sunt implementate. Nu s-a făcut deploy public. Datele operatorului/domeniul/hostingul rămân mock până sunt furnizate. Apple real este condiționat de credențiale și configurația domeniului. APNs, Live Activity push, resetarea parolei prin email și conectarea iOS nu sunt activate. [Matricea de paritate](frontend-parity.md) precizează acoperirea și fluxurile.

Pentru App Store trebuie adăugată și ștergerea **în aplicație**, la conectarea iOS; pagina web este complementară. Referințe: [privacy URL Apple](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy), [cerințele de ștergere Apple](https://developer.apple.com/support/offering-account-deletion-in-your-app/). Textele locale mock nu reprezintă identificarea unui operator real.


## Decizie de schemă păstrată: TIMESTAMP în V1

La 8 octombrie 2026, proprietarul proiectului a ales păstrarea modificării V1 de la `TIMESTAMPTZ` la `TIMESTAMP`, cu discutarea schimbării schemei separat. Verificarea clean din folderul canonical trece cele 63 de teste pe o bază PostgreSQL 16 nouă cu această variantă. JDBC/Hibernate folosește UTC; migrările ulterioare existente pot conține în continuare TIMESTAMPTZ.

**Un deploy peste o bază care are V1 originală deja aplicată se oprește la validarea checksum-ului Flyway.** Nu s-a rulat repair, nu s-a modificat schema/datele unei baze existente și nu se recomandă un repair automat pentru a masca o schimbare de tip SQL. Înainte de acel deploy trebuie decisă o migrare compatibilă cu starea reală a bazei și cu istoricul Flyway. Nu elimina această verificare și nu goli baza pentru a ocoli problema. Această decizie este separată de functionalitatea REST, validată pe baze noi.

## Security configuration update

Read [security.md](security.md) before the next deployment. `scripts/production.sh` now provisions the restricted `done_runtime` role even on an existing volume, then starts the API with separate Flyway credentials. Add `RUNTIME_DATABASE_PASSWORD` and the SMTP sender/credentials to `.env.production`; the preflight and production startup guards enforce the new requirements. The owner password is still required for migrations. A fresh deploy runs the role script automatically too. Do not expose PostgreSQL/API ports or trust arbitrary forwarding headers. Caddy has body/header/time budgets and containers have resource limits; volumetric DDoS protection remains an upstream provider responsibility.
