# Prebuilt APK

`HMessaging-1.0.23.apk` is a signed release build, kept here so there is always a stable download
link. The signing key itself is **not** in this repository and never will be.

| | |
| --- | --- |
| Package | `com.hmessaging` |
| Version | 1.0.23 (versionCode 24) |
| Min / target SDK | 24 (Android 7) / 35 (Android 15) |
| Size | 2,551,228 bytes |
| SHA-256 | `be4ca70a8fb821db4f0512a465d4b07bd506e6eb5d42c08b24850fc06b203f7f` |
| Signature | APK Signature Scheme v2 |
| Certificate SHA-256 | `40:E7:3A:FE:EA:8C:91:F8:71:2F:BB:83:2C:06:B9:01:52:FB:88:AB:0E:A8:92:B6:A5:D5:30:C7:37:DD:D4:87` |

Verify what you downloaded before installing:

```bash
sha256sum HMessaging-1.0.23.apk
# expect be4ca70a8fb821db4f0512a465d4b07bd506e6eb5d42c08b24850fc06b203f7f
```

Signed with v2 only, not v1. The build-tools were unavailable on the machine that signed it, so
`apksig` did the signing directly; enabling v1 would have reordered the zip entries and broken the
4-byte alignment of `resources.arsc`, which makes installation fail on Android 11 and later. Every
platform this app supports (minSdk 24) verifies v2 natively.

This build has not been run on a physical device.

Each CI run also force-pushes its own (unsigned) APK to the `build-apk` branch.
