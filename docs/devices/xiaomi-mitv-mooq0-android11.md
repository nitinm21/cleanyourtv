# Xiaomi MiTV-MOOQ0 (croods): P1 43-inch EU firmware

Android TV 11, build `RTM4.220307.164.8835`, board `m7632`, 1.7 GiB RAM. The existing
[MOOQ0 list](../../examples/xiaomi-mitv-mooq0/debloat.txt) targets Android TV 10. We applied a
selected subset on this Android 11 build and the TV remained usable, including after a reboot. This
is not a claim that every list entry was tested: optional apps were kept by choice and some package
names in the list are not installed on this firmware.

## Launcher and TV functions

Projectivy is the preferred launcher. The MediaTek TV input and core TV services, Bluetooth remote
pairing, and hotkey service were kept enabled. The owner also kept Miracast, Mi Cast, and the USB
media players available; playback and mirroring were not separately re-tested on this firmware.

## Netflix

Disabling Netflix for user 0 did not survive a reboot on this build. Removing the app for user 0
kept it absent through one reboot; the system APK remains on the TV. This is a one-reboot check, not
a multi-day result. That removal is recorded only as an owner's observation; the shared list remains
disable-only and does not recommend or document an uninstall procedure.

`NetflixObserver` is not a separate installed package here: its receiver and launcher components
are inside `/system/priv-app/TvServices/TvServices.apk`, package `com.mediatek.tv.service`. The
Netflix key receiver handles global keycode 265. The boot receiver updates its key count; inspection
of this APK did not find code that re-enables Netflix. Keep `com.mediatek.tv.service` enabled because
it also provides core TV and remote functions.
