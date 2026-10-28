# Xiaomi Mi TV 3S 48: native source popup and API 19 follow-up

Independent observations from 2026-10-21, with an owner follow-up on 2026-10-23
reporting several days of household use. This supplements the earlier
[Mi TV 3S contribution, PR #686](https://github.com/Gathin23/cleanyourtv/pull/686),
which covers this model's package-manager behavior and vendor playback routes.
The additional findings here are a working native source popup with PatchWall
hidden, API 19 port constraints, a bounded HDMI-on-boot experiment and the
owner's later casting feedback. It does not duplicate that PR's debloat list.

The reference launcher requires API 26; this TV is API 19 and cannot install
that APK. Launcher observations below came from a separate local compatibility
port, not an upstream release or a `config.json` that makes upstream run on
KitKat. This is a device note, with no APKs, vendor libraries, launcher changes
or changes to the normal PROMPT procedure.

## Identify the exact firmware

| Field | Observed value |
|---|---|
| Manufacturer | `Xiaomi` |
| Model | `MiTV3S-48` (48-inch Mi TV 3S) |
| Device | `hancock` |
| Android | `4.4.4`, SDK `19` |
| Firmware / `ro.build.display.id` | `7.7.21.1653` |
| Primary ABI | `armeabi-v7a` |
| RAM visible to Linux | `MemTotal: 774392 kB` |
| Stock launcher | `com.mitv.tvhome` |
| Native settings | `com.xiaomi.mitv.settings`; AOSP recovery entry `com.android.settings` |
| Native TV player | `com.xiaomi.mitv.tvplayer` |

These values were read from the TV. Do not infer compatibility from the retail
name alone, or apply this to a different Mi TV 3S size or newer Xiaomi firmware.
The owner described the set as 1 GB RAM / 8 GB storage; the measured RAM above
is what the kernel exposes, not a storage measurement.

ADB worked after the owner enabled debugging and used a DHCP address. It
closed again after a reboot in a later test. Rediscover the current address
and check the actual connection instead of assuming the debugging toggle
guarantees a listening port. Turn debugging off after the work is complete.

## Package-state context

The session confirmed the `pm block --user 0` / `pm unblock --user 0` behavior
also described in PR #686. This changes per-user visibility without deleting
the APK or clearing data. Record and inspect `blocked` as well as `enabled`:
`enabled` can remain unchanged while `blocked=true`. Restore original states,
not a blanket enabled state. The matching undo for the tested ad package is:

```sh
adb -s <tv-ip>:5555 shell pm unblock --user 0 com.miui.systemAdSolution
```

Eight ad/push/reporting/ACR packages were hidden in this household's session:
`com.miui.systemAdSolution`, `com.xiaomi.mitv.tvpush.tvpushservice`,
`com.miui.tv.analytics`, `com.xiaomi.devicereport`, `com.xiaomi.statistic`,
`com.xiaomi.mitv.osstatistic`, `com.xiaomi.mitv.kuyun.acr` and
`com.kuyun.common.androidtv`. This is a state-history record, not an additional
apply file or a claim about every package in another owner's list.
`com.xiaomi.mitv.advertise` was already `enabled=2` and was left untouched.
The owner confirmed no boot ad on the tested restart; the session did not
isolate which existing or new setting suppressed each advertisement.

Kept enabled: Xiaomi/AOSP settings, `com.mstar.tv.service`,
`com.xiaomi.mitv.tvplayer`, `com.mitv.videoplayer`, both gallery packages,
`com.xm.webcontent`, the keyboard, Bluetooth, networking, media/providers,
installer/app store, parental controls and casting. Firmware updaters were not
part of the eight-package change. No boot-animation property was changed.

## Native settings and source selection survive without the content desktop

Opening an app called "TV" or launching an arbitrary settings activity was
not a working replacement for the native source popup. The tested popup entry
is an explicit broadcast to Xiaomi's settings receiver:

```sh
adb -s <tv-ip>:5555 shell am broadcast \
  -a com.xiaomi.mitv.settings.INPUTSOURCE_POPUP \
  -n com.xiaomi.mitv.settings/.SettingsBroadcastReceiver
```

The physical remote could then move between HDMI entries and confirm a choice,
with the stock launcher hidden. An app using this route should first check
that the receiver exists, is enabled/exported and does not require a permission
the app lacks. On an unknown firmware, fall back to native settings.

For general settings, the local port tried `android.settings.SETTINGS`
restricted to `com.xiaomi.mitv.settings`, then that package's launch intent,
then generic settings. Do not substitute a newer Xiaomi activity name without
checking this firmware's manifest.

Direct HDMI 1 playback was also tested:

```sh
adb -s <tv-ip>:5555 shell am start \
  -a com.xiaomi.mitv.tvplayer.EXTSRC_PLAY \
  -n com.xiaomi.mitv.tvplayer/.ExternalSourceActivity --ei input 23
```

The `input` extra must be an **integer**. Value `23` identified HDMI 1 on this
firmware. Keep the native TV player and MStar TV services; do not assume that
Android's later `TvInputManager` routing or source IDs from another model apply.

## Home handover and recovery

The local port used its own package, `com.gathin.tvhome.legacy`. It was installed
and opened first, before changing any HOME claimant. Navigation, app launching,
settings and input selection were checked while the original desktop was still
available. These observations do not supply a supported upstream API 19 build.

Only after that check was `com.mitv.tvhome` hidden with the same firmware-specific
`pm block --user 0` operation. The owner confirmed the physical Home key opened
the replacement and that source selection still worked after a restart.

Before removing a replacement Home, restore the original:

```sh
adb -s <tv-ip>:5555 shell pm unblock --user 0 com.mitv.tvhome
adb -s <tv-ip>:5555 shell am start -n com.android.settings/.Settings \
  --es :android:show_fragment com.android.settings.HomeSettings
```

Choose the original desktop in that UI and check the actual remote's Home
button before removing or otherwise changing the replacement. Preserve the
initial package-state backup; do not blanket-unblock unrelated packages.
The old `pm` implementation and HomeSettings UI should not be replaced with
assumed support for modern `cmd package set-home-activity` commands.

## Screensaver and casting follow-up

Android's screensaver switch and Xiaomi's screensaver timeout in native
settings were separate controls. The latter was changed from four minutes to
disabled and its persisted UI state rechecked. Turning off only Android's
dream switch was not taken as proof that the vendor saver was off. Gallery
and USB photo functions were kept.

On 2026-10-23 the owner reported several days of use with noticeably improved
responsiveness, working AirPlay and working Android casting again. This is
household feedback, not a quantified speed benchmark or proof that one
particular disabled package fixed casting. The casting components found in
the package inventory and preserved included `com.xiaomi.mitv.smartshare`,
`com.duokan.airkan.tvbox`, `com.xiaomi.dlnatvservice` and `com.xiaomi.upnp`.
Keep the receiver stack while replacing the content desktop. The feedback
did not identify the sending-device models, individual Android casting
protocols, DRM apps or a full receiver interoperability matrix. Bluetooth
was preserved but not comprehensively tested.

## API 19 compatibility findings for a separate port

Lowering `minSdk` alone is insufficient, and is not a proposed change to this
repository. A reproducible old-Android launcher belongs in a separate public
source project under the contribution rules; this note does not provide that
project or ask maintainers to merge a second launcher.

The local compatibility work found these constraints:

- Use API 19-compatible Activity, scrolling/grid, focus and drawing paths.
  App discovery needs ordinary `CATEGORY_LAUNCHER` entries; a TV-only category
  does not cover these older apps. Persist app order independently of the
  fixed first source tile, and recheck focus after scrolling and returning.
- Avoid unconditional newer APIs and resources: newer network callbacks,
  Material themes, platform vector drawables and clipping helpers need an
  older implementation. Date/file APIs also need an API audit, not just a
  lower manifest requirement. Check the final APK with API-level lint.
- The tested APK was single DEX and had a V1 signature verified for API 19.
  A current V2-only APK is not a KitKat installation candidate.
- In the optional network-wallpaper experiment, old HTTPS needed a per-request
  TLS provider. It retained certificate and hostname validation, rather than
  trusting every certificate or changing the system trust store. The final
  wallpaper build used Conscrypt `2.5.3` and its `armeabi-v7a` native library;
  it was no longer dependency-free. This is not a dependency proposal for the
  offline reference launcher.
- Decode one cached still image within GPU/memory limits. The local cap was
  about 2.1 million pixels and at most 2048 pixels per decoded dimension using
  `RGB_565` sampling. Cancel obsolete downloads, do network work off the UI
  thread, and fall back to a plain background. Avoid per-tile hardware-layer
  churn: one local focus implementation left traversed icons grey until fixed.

These are porting findings, not new options in the reference APK. The upstream
launcher's minimum Android version and offline behavior remain unchanged.

## Optional HDMI-on-boot experiment and its limits

The local port's opt-in experiment read the vendor class
`com.mstar.android.tvapi.common.TvManager`, called `getInstance()`, then
`setTvosCommonCommand("GetInputSourceStatus")`. It returned a `short[]`; index
`23` was observed as `1` with the cable box on/outputting and `0` with that box
off and its HDMI cable still connected. `isSignalStable(int)` was not a usable
per-port check from Home on this firmware. These are vendor details, not an
Android SDK contract.

The experiment was off by default, gated to Xiaomi / `hancock` / SDK 19 and
designed to fail closed on missing methods, unexpected data or unreadable boot
identity. It consumed `/proc/sys/kernel/random/boot_id` once per full boot before
starting a background query window of at most 30 seconds, and required two
consecutive positive samples before using the explicit HDMI 1 intent above.
Any key, click or departure from Home cancelled it. Returning Home in the same
boot, or waking from standby, was not a new attempt. There was no added boot
receiver or persistent service; bounded polling was confined to that local
experiment and is not being proposed for the reference launcher.

In the 2026-10-23 follow-up, the owner confirmed using HDMI signal detection
to enter the source in full-screen playback automatically. **Both reboot
branches were not captured in ADB logs:** the television closed ADB after
reboot. Do not label no-signal retention, every standby mode,
or every cable box as fully verified. Some boxes continue reporting a signal
while nominally off. Validate on/off states on the target hardware before
offering such a feature, and keep an accessible off switch.

## Validation scope

- Basic port: install/hash check, app launch, order save/cancel/restart,
  settings entry and 20 settings/Home cycles were recorded. Sampled PSS was
  about 9.6-10.9 MiB in that early minimal build. This is not a performance
  guarantee or a measurement of the later wallpaper/HDMI build.
- Later local wallpaper/focus build: cached images, manual change, process
  restart and unchanged non-focused icon pixels were checked. Sampled PSS
  was roughly 14-18 MiB with wallpaper, not a long-duration memory test.
- Owner confirmations: physical Home, native source selection and no boot ad
  on the tested restart; fixed focus colours/source-first layout; later,
  direct TV picture on boot. These are separate from automated checks.
- Owner follow-up on 2026-10-23: several days of improved responsiveness,
  HDMI-triggered full-screen playback, AirPlay and Android casting working.
- Not established: stock upstream APK support, a measured speed-up, long-term
  stability, fully logged HDMI boot branches, standby/CEC behavior,
  future firmware updates or other Mi TV 3S firmware variants.

No TV was reconnected or changed for the documentation contribution. Only
sanitized model/firmware findings are shared; personal backups and device or
network identifiers are excluded.
