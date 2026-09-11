# HMessaging

An SMS messenger for Android, built around the features that only the **default SMS app** can
provide: blocking before delivery, scheduled sending, unlimited-length messages, forwarding, an
answering machine, and automatic OTP capture with a pop-up.

Kotlin · Jetpack Compose (Material 3) · Room · WorkManager · minSdk 24 / targetSdk 35 ·
English and Persian, RTL-aware.

---

## Features

### Requested

| Feature | Where it lives |
| --- | --- |
| **Receive SMS** | `sms/SmsDeliverReceiver.kt` handles `SMS_DELIVER` (default app) and falls back to `SMS_RECEIVED` when another app holds the role. Multipart messages are reassembled before anything else runs. |
| **Blocking** | `feature/block/BlockEngine.kt`. Rules match the **sender** or the **message text** by exact / starts-with / ends-with / contains / regex. Plus global switches for hidden numbers and for anyone outside your contacts. Blocked messages are quarantined, never notified, and never written to the system provider — and can be restored later. Optionally screens **phone calls** through the same list (`HmCallScreeningService`). |
| **Scheduled sending** | `feature/schedule/`. Exact alarms where permitted, with repeat (hourly / daily / weekly / monthly), an end date, re-arming after reboot, and a 30-minute WorkManager sweep that catches anything Doze or a reboot dropped. |
| **Long messages without a length limit** | `util/SmsText.kt` + `sms/SmsSender.kt`. Bodies of any length are split with `SmsManager.divideMessage` and sent as a concatenated multipart SMS, which the receiving phone reassembles transparently. A live counter shows characters used, segment count, and whether the body is GSM-7 or Unicode (Persian text is Unicode: 70 characters per segment). An optional mode sends numbered chunks (`1/3`, `2/3` …) for gateways that strip concatenation headers. |
| **SMS forwarding** | `feature/forward/ForwardEngine.kt`. Rules forward by sender pattern, body keyword, contact status, or everything, to one or more numbers, with a custom format. Guarded by a loop check (never forwards back to the originating number), an hourly ceiling, and no signature on forwarded text. |
| **Answering machine (auto-reply)** | `feature/autoreply/AutoReplyEngine.kt`. Prioritised rules with active days, an active time window (including windows that wrap past midnight), a per-sender cooldown, and a daily cap. A separate **away-until** switch answers around the clock for a set period. Short codes and alphanumeric sender IDs are never answered. |
| **Automatic OTP copy with a pop-up** | `feature/otp/`. Detects codes in English and Persian (Persian/Arabic-Indic digits included), stores them, copies to the clipboard, and raises a pop-up that shows over the lock screen with a countdown. Where "display over other apps" is not granted, it degrades to a heads-up notification whose tap target opens the same pop-up — because from Android 10 onward only a foreground app may write to the clipboard. |

### Added

- **Notification direct reply**, mark-as-read, and block straight from the notification.
- **Message templates / quick replies** with placeholders (`{name}`, `{sender}`, `{body}`, `{time}`, `{date}`).
- **Dual-SIM support** — per-message SIM choice and a default.
- **Delivery reports**, retry on a failed message, and a visible send status per message.
- **Pin, archive, mute** conversations; full-text **search**; export a conversation as text.
- **Backup and restore** to JSON (rules, templates, schedules, settings, optionally messages).
- **Import the phone's existing SMS history** when you switch to the app.
- **App lock** via biometrics or device credential.
- **Statistics** — received, sent, failed, last 7 days, blocked, most frequent senders.
- **Material You** dynamic colour, light/dark/system theme, signature, OTP retention.

---

## Build

```bash
# Android Studio Ladybug or newer, or:
./gradlew :app:assembleDebug
```

Requires the Android SDK (compileSdk 35) and JDK 17+. Point `local.properties` at your SDK:

```properties
sdk.dir=/path/to/Android/sdk
```

### What has and has not been verified

This tree was written in an environment with no access to Google Maven, so the Android Gradle
Plugin and the AndroidX artifacts could not be fetched and **no `assembleDebug` has ever run**.

What *was* verified, by running the real Kotlin 2.0.21 compiler against the Android API 35 class
library with hand-written stubs for the AndroidX surface in use:

- All 40 files outside `ui/` — the SMS layer, all five rules engines, the Room entities and DAOs,
  preferences, the repository, notifications, scheduling, backup and the DI graph — **type-check
  with zero errors and zero warnings**. That pass caught two genuine bugs: `Telephony.Sms.Intent`
  (the real class is `Intents`, which would have broken receiving outright) and a suspend function
  passed to `let`.
- The 33 Compose files in `ui/` are **not** type-checked, because Compose and the AndroidX
  runtime are unavailable here. They parse cleanly and every cross-module import and string
  resource resolves, but the first real build is where their type errors will surface.

Treat that first build as a review step.

### Signing a release build

The release build is signed with the project's own key. Copy
`keystore.properties.example` to `keystore.properties`, point it at the keystore and fill in the
passwords — the file is gitignored and must never be committed:

```bash
cp keystore.properties.example keystore.properties
$EDITOR keystore.properties
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

CI reads the same credentials from `HM_KEYSTORE_FILE`, `HM_KEYSTORE_PASSWORD`, `HM_KEY_ALIAS` and
`HM_KEY_PASSWORD`. Without any of them the release build still assembles, just unsigned.

### Building the APK on GitHub Actions

`.github/workflows/build-apk.yml` assembles the release APK on a runner that has the Android SDK
and uploads it as a build artifact, so an APK can be produced without a local Android install.
To have that build come out signed, add four repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `HM_KEYSTORE_BASE64` | `base64 -w0 hmessaging-release.jks` |
| `HM_KEYSTORE_PASSWORD` | the keystore password |
| `HM_KEY_ALIAS` | `hmessaging` |
| `HM_KEY_PASSWORD` | the key password |

Losing the keystore means never being able to ship an update that Android accepts as the same app.
Keep a backup somewhere safe and out of the repository.

---

## Set-up on the device

1. Grant SMS, contacts, phone and notification permissions when prompted.
2. Accept the **"Set as default SMS app"** banner. Android delivers incoming messages only to the
   default SMS app, so blocking, auto-reply, forwarding and OTP capture do nothing without it.
3. Optional, in **Settings → Permissions and roles**:
   - *Display over other apps* — lets the OTP pop-up appear while you are in another app.
   - *Exact alarms* — makes scheduled messages go out on the minute rather than whenever the
     system batches them.
   - *Call screening* — extends the block list to incoming calls.

---

## Architecture

```
sms/          receivers, sender, SIM handling, system-provider mirror, importer
feature/      block · autoreply · forward · otp · schedule  (the rules engines)
data/         Room entities + DAOs, DataStore preferences, repository
notify/       notification building and inline actions
backup/       JSON export/restore
ui/           Compose screens and ViewModels, one package per screen
di/           AppGraph — a hand-rolled singleton graph shared by activities,
              receivers and services
```

Every inbound message takes one path, `sms/IncomingMessagePipeline.kt`:

```
block? → store (+ mirror to the system SMS provider) → OTP capture → notify → auto-reply → forward
```

The order is deliberate. Blocking runs before anything is stored or shown. OTP capture runs before
the notification so a code never produces two alerts. Auto-reply and forwarding run last, each
wrapped so a radio failure there can never cost you the message itself.

Room is the source of truth for the UI; the platform SMS provider is kept in sync so other apps,
backup tools, and the next messaging app you install all see the same history.

## Limitations

- **SMS only.** `MmsDeliverReceiver` exists because Android will not offer the default-SMS role to
  a package that cannot receive `WAP_PUSH_DELIVER`, but MMS is acknowledged and dropped rather than
  downloaded. Group MMS threads are not supported.
- No RCS, no encryption beyond what the carrier provides — SMS is plaintext on the wire.
- Forwarding and auto-reply send real SMS and cost real money; both ship disabled, with rate limits.
