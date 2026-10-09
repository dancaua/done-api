# Integrarea Swift

API: `/api/v1`, JSON, `URLSession`. Backendul este implementat; conectarea UI welcome/sign in/sign up este etapa următoare.

## Auth

- Register: email, password, displayName. Login: email, password.
- Salvează `accessToken`, `refreshToken`, `refreshExpiresAt` și contul din răspuns în Keychain/cache separat.
- Trimite access token numai pe rutele protejate. La 401, un singur actor face refresh, înlocuiește tokenul vechi, apoi reia cererile protejate.
- Nu porni refresh-uri paralele și nu refolosi refresh-ul după un răspuns consumat/pierdut: replay revocă întreaga familie. Reautentificarea este calea sigură dacă noul refresh nu a fost salvat.
- Un iPhone și un iPad au sesiuni separate. Watch poate folosi relay-ul de comenzi existent prin iPhone.
- `logout`, `logout-all`, schimbarea parolei și ștergerea contului au efect imediat asupra JWT-urilor.

Apple folosește `SignInWithAppleButton`/`ASAuthorizationController`. Cere challenge înaintea autorizării, setează `request.nonce = challenge.nonce` fără încă un hash, apoi trimite identity tokenul și codul ca stringuri UTF-8. `displayName` este opțional și disponibil de obicei la prima autorizare. Identitatea și emailul sunt verificate de backend din token. Conturile locale cu același email cer asociere explicită după login local.

## Timestampuri

Acceptă fracțiuni de secundă și formatul fără fracțiuni. De exemplu:

```swift
let decoder = JSONDecoder()
decoder.dateDecodingStrategy = .custom { decoder in
    let container = try decoder.singleValueContainer()
    let text = try container.decode(String.self)
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    let standard = ISO8601DateFormatter()
    standard.formatOptions = [.withInternetDateTime]
    if let date = fractional.date(from: text) ?? standard.date(from: text) {
        return date
    }
    throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid API timestamp")
}
```

`startedAt` este momentul serverului când comanda reușește. `expectedEnd` este opțional: `nil` pentru cronometru. Pentru timp scurs folosește `completedAt ?? canceledAt ?? now`, minus start; pentru countdown folosește deadline-ul absolut. `serverTime` permite compensarea unui ceas local diferit.

## Snapshot și comenzi

După login, reactivare și o comandă reușită, citește `GET /state`. `revision` crește pentru schimbări de date și milestone-uri. Timerul se actualizează local din timestampuri chiar dacă revision nu se schimbă.

Snapshotul conține toate aparatele și sesiunile active, plus 100 de sesiuni recente și 100 de evenimente recente. Istoricul complet este paginat; `hasMoreSessions` indică trunchierea listei recente. `GET /me/export` conține toate datele contului, fără parole sau tokenuri.

Folosește UUID-ul comenzii Swift ca `Idempotency-Key`; păstrează payloadul neschimbat la retry. Nu genera altă cheie după timeout pentru aceeași acțiune. După un răspuns cached, recitește snapshotul: acel răspuns poate reflecta o stare anterioară. Nu reexecuta cozi offline mai vechi de 30 de zile.

- creare aparat: `kind` = washer/dryer/dishwasher/oven/hob/custom; nume obligatoriu pentru custom, opțional pentru restul;
- program din sugestii: `programId`;
- durată proprie: `program: {name, minutes}`, `saveProgram` implicit true;
- cronometru: `mode: stopwatch`, fără programId, program opțional cu minutes 0;
- extend: `{minutes: 10}`; complete/collect/cancel: fără corp;
- rename/edit program: `version` din ultima citire;
- profil: PATCH numai câmpurile schimbate.

Starea aparatului: idle/running/due/done. Starea sesiunii istorice: running/due/done/collected/canceled. Pentru cooking, complete trece direct în collected, cu completedAt și collectedAt egale; pentru rufe/vase/uscător cere collect separat. UI poate identifica sesiunile confirmate prin completedAt.

Backendul produce evenimente persistente pentru jumătate, 5 minute, due și remindere. La conectare, aplicația trebuie să rescheduleze notificările locale și să actualizeze WidgetKit/ActivityKit din snapshot. Workerul backend nu trimite APNs în această etapă.

Prima integrare va cere conexiune pentru comenzi noi. Timerul deja pornit poate fi afișat offline din cache. Importul datelor locale existente, dacă este necesar, trebuie definit explicit la conectarea aplicației; JSON-ul local DoneState nu se trimite ca o înlocuire nevalidată a DB.

## Preferred language

The Apple onboarding stores one of `en`, `ro`, `es`, `it`, `fr`, `de`, `pl`, `hi`, `ja` locally.
Send this code as `language` when registering or signing in with Apple for the first time.
Existing Apple accounts keep their saved preference at login; change it with an idempotent
`PATCH /api/v1/me` body such as `{ "language": "ja" }`. The field is optional on writes;
omitting it preserves the current preference. `UserView` in authentication, profile, state,
and export responses includes `language`. Unsupported codes are rejected with HTTP 400.
Existing accounts migrate to `ro`; new accounts without a choice default to `en`.

The Apple app currently remains local: profile sync will be wired with the authentication UI.
The client translates built-in programs and generated messages using shared resources; custom
appliance and program names are user content and must never be machine-translated. Backend
error `code` and semantic enum values should be mapped through client localization when API
integration is added; server prose is not the application's translation source.

## Households

`GET /state` și `GET /me/export` conțin `households: [HouseholdView]`. Fiecare `ApplianceView` include `householdId`. Păstrează toate casele în cache și selectează local casa vizibilă, cu fallback la prima casă dacă cea selectată a fost ștearsă. Notificările, widgeturile și Watch primesc activitatea tuturor caselor; un deep link la aparat selectează casa sa înaintea ecranului de detalii.

- `GET /households`, `GET /households/{id}`: lista și detaliile, inclusiv applianceCount.
- `POST /households`: `{ "name": "Casa de vacanță" }`.
- `PATCH /households/{id}`: `{ "name": "Brașov", "version": 0 }`.
- `DELETE /households/{id}`: numai casă goală; cel puțin una trebuie păstrată.
- `POST /appliances`: adaugă `householdId` pentru destinația selectată.
- `PATCH /appliances/{id}/household`: `{ "householdId": "UUID", "version": 0 }`; fără restart de timer sau pierdere de istoric.

Folosește `Idempotency-Key` pentru toate scrierile. Filtrele opționale `?householdId=UUID` există pe GET /appliances, GET /activity, POST /activity/read-all și GET /statistics. Citirea activității dintr-o casă nu marchează evenimentele altor case. Istoricul urmează apartenența curentă a aparatului după o mutare.

Casa implicită vine cu `localizationKey: household.default`; afișează traducerea din catalogul local. Pentru casele redenumite/create, cheia este null și numele este conținut introdus de utilizator. Pentru aparate cu același nume în case diferite, folosește și numele casei în Siri/notificări.

Coduri de conflict: `duplicate_household`, `household_limit`, `household_not_empty`, `last_household`, `stale_version`, `duplicate_name`. Referințele lipsă sau ale altui cont răspund 404. După un retry, recitește GET /state; răspunsuri idempotente create înainte de V5 pot să nu conțină householdId.

Aplicația Apple implementează deja local acest model și migrarea datelor proprii. Autentificarea și sincronizarea ei cu backendul rămân etapa de integrare descrisă mai sus.

## Preferințe per aparat și programe

Trimite opțional `notificationsEnabled` la POST /appliances; default true. ApplianceView include flagul în listă, detalii, snapshot și export. PATCH /appliances/{id}/notifications primește `{ "notificationsEnabled": false, "version": 0 }`, cu Idempotency-Key. Preferința generală a contului și permisiunea OS rămân obligatorii pentru livrare.

Clientul local implementează deja switchul la adăugare și în setările aparatului, redenumire și ștergere de programe cu confirmare. La mute recalculează notificările fără acel aparat; celelalte aparate își păstrează alertele. Re-enable nu retrimite milestone-uri trecute. Nu opri timerul, istoricul, widgetul sau Live Activity când notificările sunt muted.

Folosește PATCH /appliances/{id} cu nume și versiune pentru redenumire; DELETE /appliances/{id}/programs/{programId} pentru eliminarea programului. Păstrează snapshotul programului din sesiune chiar dacă referința salvată dispare. UI-ul trebuie să citească lista curentă, să elimine programele șterse din selecții/Siri/Watch și să permită program custom când lista este goală.

În cache-ul Swift vechi, absența preferinței per aparat înseamnă true. O valoare false salvată explicit, inclusiv la nivel general, se păstrează. Numele personalizate rămân conținut al utilizatorului și nu se traduc.


## Paritate actualizată și deploy

Contractul actual și regulile programelor măsurate, presetelor imuabile, statisticilor rolling, traducerilor și partajării sunt în [frontend-parity.md](frontend-parity.md). Exemplele reale de DTO sunt în [contract-examples.json](contract-examples.json), fără tokenuri de autentificare. Configurația publică, paginile și deploy-ul HTTPS sunt descrise în [deployment.md](deployment.md).

La integrare folosește `GET /statistics/rolling` pentru ecranul nativ. Endpointul calendaristic `/statistics` rămâne disponibil pentru compatibilitate; nu reproduce filtrele rolling ale iOS.

Noile comenzi: `POST /sessions/{id}/measured-program` cu `{ "name": "Programul meu" }`, `POST /sessions/{id}/repeat`, `POST /sessions/{id}/share`, `GET /session-shares`, `DELETE /session-shares/{token}`. Răspunsurile sesiunilor includ `programSnapshot`, `measuredProgram`, `sourceProgramId`, `timingMode`, `isOpen`. Schimbarea preferinței de limbă re-traduce activitatea prin cheile/argumentele semantice. `PATCH /me` poate persista `onboarded`.
