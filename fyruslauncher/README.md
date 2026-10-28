# FyrusLauncher 2.6 for Mi Box S2

FyrusLauncher combines CleanYourTV launcher 2.6 with the custom Netflix, location wallpaper and portable Windows installer developed for a Xiaomi Mi Box S 2nd Gen.

The upstream launcher at the repository root remains unchanged. This directory is a separate branded build so the custom release can be reviewed without replacing upstream defaults.

## User release

Extract the release ZIP and run `FyrusLauncher/START.bat`. Follow steps 1–6. The release contains an APK; users need only ADB, not an Android SDK, JDK or Gradle. Existing ADB is reused. If absent, official platform-tools are downloaded inside the extracted folder after confirmation.

The installer verifies MiTV-AFKR0, user 0 and completed setup, creates a device-specific backup, installs with `install -r`, and asks before enabling an Accessibility Home redirect. System Home packages are kept enabled. Backup and rollback are available before the redirect is activated. Optional removal of PatchWall, Play Games and Play Movies requires a separate confirmation and retains Xiaomi TV+.

The default Android resolver remains Google TV on the tested firmware. Home is redirected through Accessibility. Android 11 on MiTV-AFKR0 is the tested target; the installer stops when a model, identity, setup state or Wi-Fi format cannot be verified. Physical remote and reboot behavior should also be checked on each new device.

All installer downloads, backups and logs remain inside the package. It does not install an SDK/JDK, modify the registry or change global PATH. Official Windows ADB still uses its shared user-profile debugging key. A dedicated local ADB server port (5038) avoids shutting down another tool's default server.

## Preserved custom behavior

- Seven New on Netflix posters, rounded glass frames, compact metadata and subtle background lighting; no Continue Watching shortcut or fabricated viewing history.
- Netflix Card and Location Wallpaper switches, mutually exclusive; both may be off.
- Both off: the bundled Aurora wallpaper and focus-dependent application-color ambient glow.
- Location wallpaper stays full screen while the app grid scrolls; its description fades out below and returns at the top. Netflix poster scrolling retains its previous behavior.
- Glass dock, Wi-Fi icon without SSID, and a crossed Wi-Fi icon when disconnected.
- Optional five-minute screensaver setting and the custom Mi Box Home service.

## Build and package

Use an existing JDK 17/21 and Android SDK 34. These are developer requirements only. Nothing installs them automatically:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\fyruslauncher\build-release.ps1 -JavaHome 'C:\path\to\jdk-21' -Sdk 'C:\path\to\Android\Sdk'
```

The wrapper and dependency cache for this developer command go under `fyruslauncher/.build-cache`. The output is a clean release ZIP under `fyruslauncher/release`, without device backups, keys, IP addresses or logs. The app retains `com.fyrus.cleanyourtv.netflix` so the same signing key can update an existing custom install without deleting its data. Do not commit signing keys. Builds signed with a different key cannot update an existing installation with `install -r`; the installer stops instead of uninstalling it.

Run the installer regression checks after building:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\fyruslauncher\tests\test_portable_installer.ps1 -PackageRoot .\fyruslauncher\release\FyrusLauncher
```

The test uses a fake ADB device, including failure rollback; it does not change a TV.

## Migration provenance

Upstream 2.6 is commit `0bb7f7edbf53349c7975a29495515453ed850526`. The previous custom project was 2.5/versionCode 6. Its custom changes were ported with three-way file merges against the matching 2.5-era base `6c8a7bddb861a2dee186261d7f4f290cf1094884`. Upstream 2.6 Config, adapters, input support, tests and versionCode 7 are retained. Manifest queries were combined, and MainActivity conflicts were resolved to preserve both upstream features and custom settings. The upstream 2.6 glass dock supports the same custom translucent style.

The original project license and font license are retained. See the repository root `LICENSE` and `launcher/FONT-LICENSE-OFL.txt`.
