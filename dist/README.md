# Prebuilt APK

`HMessaging-1.0.18.apk` is a signed release build, kept here so there is always a stable download
link. The signing key itself is **not** in this repository and never will be.

| | |
| --- | --- |
| Package | `com.hmessaging` |
| Version | 1.0.18 (versionCode 19) |
| Min / target SDK | 24 (Android 7) / 35 (Android 15) |
| Size | 2,443,424 bytes |
| SHA-256 | `41fd808cf8a7274d91d23fec18426af53bce1b4c71f0401c0b9e594d01160868` |
| Signature | APK Signature Scheme v2 |
| Certificate SHA-256 | `40:E7:3A:FE:EA:8C:91:F8:71:2F:BB:83:2C:06:B9:01:52:FB:88:AB:0E:A8:92:B6:A5:D5:30:C7:37:DD:D4:87` |

Verify what you downloaded before installing:

```bash
sha256sum HMessaging-1.0.18.apk
# expect 41fd808cf8a7274d91d23fec18426af53bce1b4c71f0401c0b9e594d01160868
```

Signed with v2 only, not v1. The build-tools were unavailable on the machine that signed it, so
`apksig` did the signing directly; enabling v1 would have reordered the zip entries and broken the
4-byte alignment of `resources.arsc`, which makes installation fail on Android 11 and later. Every
platform this app supports (minSdk 24) verifies v2 natively.

This build has not been run on a physical device.

Each CI run also force-pushes its own (unsigned) APK to the `build-apk` branch.
