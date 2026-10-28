# Release validation

- Upstream base: CleanYourTV launcher 2.6, `0bb7f7edbf53349c7975a29495515453ed850526`.
- Android metadata verified on device: `versionName=2.6`, `versionCode=7`; application ID retained as `com.fyrus.cleanyourtv.netflix`.
- `testDebugUnitTest`: 165 tests, zero failures. `assembleDebug`: successful.
- Portable installer regression test passes with fake ADB: endpoint/model/device validation, staged backup/install, preservation of other Accessibility services, Home success and rollback on failure, baseline preservation, explicit disable approval, optional app restore, and local-folder ADB download using a fixture ZIP.
- Existing ADB detection exercised on Windows; no SDK/JDK/platform-tools download was needed on this PC.
- Real MiTV-AFKR0 Android 11: dedicated ADB port 5038 connected; device-specific backup completed; `install -r` succeeded; Home key injection resumed FyrusLauncher.
- Real restore cycle completed using the new core: original Google TV/Accessibility restored, fresh backup created, 2.6 installed and Home redirect re-enabled. Wi-Fi profiles and disabled package set matched the snapshot.
- The already-approved disabled apps remained PatchWall, Play Games and Play Movies. Xiaomi TV+ remained enabled. No additional system package changes, Wi-Fi edits, reset or app-data deletion occurred.
- Release archive generated from a public file allowlist and checked for private paths/device identifiers. Runtime logs, backups, ADB keys, SDKs, build cache and signing keys are excluded.

Physical reboot on another Mi Box and every firmware variation have not been tested. The installer stops when its model/setup/Wi-Fi checks cannot establish the supported state. The missing-ADB download path was tested with a local fixture, not by unnecessarily downloading another copy on the PC.
