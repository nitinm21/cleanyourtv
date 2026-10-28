# TCL 40" Google TV (G13, Google TV 14), India

TCL `G13` set, product `G13_2K_GB`, device `G13`, MediaTek mt5867, ~1 GB RAM. Google TV 14 (SDK 34),
build `TCL/G13_2K_GB/G13:14/UKN2.241110.001/AU13:user/release-keys`, security patch 2026-06-01.
Retail listing: TCL 101 cm (40 inch) 2K QHD Google TV, V5C series, India.

| File | What it is |
|---|---|
| `debloat.txt` | The 7 packages disabled on this set, grouped and commented |

**How much testing is behind it:** one set, 23 October 2026. All 7 packages are disabled on the live
set (`pm list packages -d` verified) and the TV boots and plays normally; storage use dropped from 66%
to 53% after also running `cmd package trim-caches`.

**Not done here: the launcher half fails on this firmware build, unlike a near-identical unit.**
`examples/tcl-43s5k/` is the *same* device/product (`G13`/`G13_2K_GB`, `ro.board.platform mt5867`,
Google TV 14) just one firmware sub-build older (`UKN2.241110.001 AU10` there vs `AU13` here,
security patch 2025-12-01 vs 2026-06-01) and `launcher/build-and-install.sh` worked on it, surviving
reboots. On this AU13 unit it fails even when their exact documented sequence is followed precisely
(this was tried first with the wrong order, see below, then redone correctly with the same result):

1. Install the APK, then `am start -W -a android.intent.action.MAIN -c android.intent.category.HOME
   -n com.gathin.tvhome/.MainActivity` — returns `Status: ok`, and the launcher actually renders
   correctly (screenshot-verified: dock, clock, ambient glow all present).
2. `pm disable-user --user 0 com.google.android.apps.tv.launcherx` (stock launcher disabled only
   *after* step 1 confirmed the new launcher runs, per `build-and-install.sh`'s own comment: "Android
   drops the preference whenever the set of HOME apps changes").
3. `cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity` (set *after* disabling,
   also per that script).
4. `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
   still returns `com.google.android.tungsten.setupwraith/.RecoveryActivity`, not the new launcher.

The RoleManager HOME role is correctly granted throughout (`cmd role get-role-holders
android.app.role.HOME` returns `com.gathin.tvhome`), so this isn't a missed grant — something in this
build's Home-routing still prefers `setupwraith` over the role holder once `launcherx` is gone. An
earlier, less careful attempt (preference set *before* disabling, no explicit `am start` first) showed
a likely mechanism: `logcat` at that moment showed a `BootModeAppToForeground` log line (from the
certified Google TV `com.android.libraries.tv.tvsystem` library, bundled in `launcherx`) explicitly
re-launching `com.google.android.apps.tv.launcherx` **by package name** — a different code path from
RoleManager entirely. Whether that specific path is still what blocks the *correctly-ordered* attempt
above is unconfirmed; what's confirmed is that both orderings land on `setupwraith`, not the new
launcher, on this build.

Given two attempts with the proven sequence both failed the same way, further retries weren't
attempted on this unit — each one carries the reboot/boot-splash risk below. Worth trying: a firmware
update if TCL ships one past AU13, or comparing notes with another AU13 (or later) unit to see if
it's this sub-build specifically rather than this one set.

**Recovery, if you hit this too:** disabling `launcherx` here does not immediately break anything
visible — but the next *reboot* gets stuck on the "Google TV" boot splash forever, because nothing can
satisfy Home during boot. This looked alarming (stuck splash, no ADB reachable over the TLS wireless-debugging
transport that was open before the reboot) but was not a brick: `sys.boot_completed` was already `1`,
and ADB was reachable again on **plain TCP port 5555** without needing a new wireless-debugging pairing.
`pm enable com.google.android.apps.tv.launcherx` followed by `input keyevent KEYCODE_HOME` resolved it
immediately. If this happens to you: don't wipe or re-flash anything, just get ADB back (try port 5555
first) and re-enable the stock launcher.

Apply: `TV_HOST=<tv ip> tools/disable.sh examples/tcl-g13-google-tv/debloat.txt`
Undo: `TV_HOST=<tv ip> tools/restore.sh examples/tcl-g13-google-tv/debloat.txt`
