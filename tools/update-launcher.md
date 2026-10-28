# Update the launcher from this computer

`update-launcher.py` selects the highest stable version tag already in your local checkout. It makes
no GitHub API calls, fetches or clones. The cached Gradle distribution runs directly with `--offline` (so the wrapper cannot download itself); the distribution and
build dependencies must already be cached. The only network connection is ADB to your TV.
The built APK is refused if it declares `android.permission.INTERNET`.
Release tags currently contain source, not prebuilt APKs: the computer builds the source with JDK 17,
Android SDK 34/build-tools 34.0.0 and the Gradle version pinned by the repository's wrapper. Git, Java and the Android SDK
must already be installed; this script does not install tools, enable debugging or start Shizuku.

Read-only check (no TV connection):

```sh
tools/venv/bin/python -I tools/update-launcher.py
```

Update an **already installed** `com.gathin.tvhome`, signed with your original build key:

```sh
TV_HOST=<tv ip> tools/venv/bin/python -I tools/update-launcher.py --install
# Pin a locally available tag for a reproducible run:
TV_HOST=<tv ip> tools/venv/bin/python -I tools/update-launcher.py --install --release v2.7.0 --work-dir work/update-2.7
```

Update your checkout and fetch tags separately beforehand if wanted; this tool never does that.
Use `--checkout /path/to/repo` to select another local checkout. Dirty and untracked files are
ignored: the selected tag is exported into a fresh directory, leaving your checkout untouched.
No local stable tags, a missing selected tag or missing cached dependencies stops the update.

Set JAVA_HOME to JDK 17 and ANDROID_HOME to the SDK beforehand. The same Python entry point works on
Windows (`tools/venv/Scripts/python.exe`); set environment variables in PowerShell with `$env:NAME`.
A new private folder under `~/.cleanyourtv/updates/` (or `--work-dir`) holds release source, build
metadata, the previous APK and external config/settings backups. Keep that folder private; do not
commit it. A supplied work directory must not already exist.

The updater verifies package/version/minSdk and signing certificates, refuses downgrades/split APKs,
and installs with `pm install -r`. It never uninstalls, clears app data, disables packages, changes
Home or forces the launcher to stop. Settings and theme files stay on the TV. It compares external
config after installation and reports helper/screensaver setting changes for manual review.
Press Home and check the physical remote, HDMI and the screensaver after updating. Turn debugging
off when finished; this is an explicit one-time computer-side tool, not an on-TV service or scheduler.
A failed install stops; it does not bypass verification. Backup APKs are for manual recovery; Android
may refuse an older version, so the script does not promise an automatic downgrade.

## Forks and local changes

A release rebuild replaces launcher code. On-TV preferences/config remain, but fork features need
an explicit trusted local `--patch path/to/customizations.patch` (repeatable). It is applied on a
fresh export of the tagged local source before building; a conflict stops before any TV mutation. Keep patches locally,
review them for the new release and never include credentials or device configuration. There is no
automatic merge, reset of an existing checkout, unsigned APK download or override of a signing failure.
The APK permission check also applies to patched builds. Building executes the trusted release's
Gradle scripts and any patch you explicitly supply, on this computer.

## Validation

Unit tests cover hostile tags/paths, local tag selection/export, default read-only behavior, package/signature/version/SDK
mismatches, INTERNET rejection and install-failure handling. Real-TV v2.7.0 validation is recorded
in the PR, including any local patch used; private configuration is not published.
