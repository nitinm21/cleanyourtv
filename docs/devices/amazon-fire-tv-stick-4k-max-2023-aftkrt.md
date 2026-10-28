# Amazon Fire TV Stick 4K Max, 2nd generation (AFTKRT / karat)

Owner report recorded on 23 October 2026. Fire OS 8.1.7.4, Android 11 / SDK 30, build RS8174.3648N. System incremental 0031877644420; boot-image fingerprint incremental 0031877644288. Kernel 5.10.43-android12-9-ge1cb3cb5ad98, 32-bit Android app userspace (`armeabi-v7a`).

This is a device note rather than another tested-list folder. The owner confirmed the configured setup works after reboot and marked usage testing complete. The elapsed testing duration was not independently recorded, so this note does not claim a several-day-tested package list.

## Relationship to the existing Fire OS 8 example

The [2023 Fire TV Stick 4K example](../../examples/amazon-fire-tv-stick-4k-2023/debloat.txt) includes AFTKRT observations on Fire OS 8.1.8.5. This unit is the 4K Max 2nd generation on the older 8.1.7.4 build. Do not infer that all packages, protected-package decisions or launcher behavior match between those firmware versions.

## Observed cleanup

Twenty-two selected packages were disabled for user 0 without root, not uninstalled. Their disabled state survived two instrumented normal-reboot checks. They were a subset of the existing Fire OS example's reporting, advertising, shopping, tutorial and optional-app entries. Amazon Kids, Amazon Music and Luna-related packages were owner choices, not mandatory removals.

At that stage the stock launcher, native Settings, Appstore, keyboard, remote/Bluetooth, accounts, Alexa, casting, OTA and screensaver services were kept. No protected-package guard was bypassed for the no-root cleanup. Eight unwanted launcher tiles were hidden separately; hiding them did not disable their services.

The owner turned off Device Usage Data, Collect App Usage Data and Interest-based Ads in the native Privacy Settings menu, plus both Featured Content autoplay options. Resolution stayed Auto. Animation scales were left stock because the existing Fire OS 8 example documents broken side-menu navigation at 0.5; this note does not claim that issue was independently reproduced on 8.1.7.4.

The owner's [cleanup report and exact 22-package list](https://github.com/wojciechsacewicz/fire-tv-karat-cleanup/blob/main/CLEANUP.md) are maintained separately. For this repository, Fire OS remains debloat-only; there is no launcher or root procedure in this contribution.

## Validation and limits

The initial package changes and accessibility-based launcher setup survived reboot. An open-source AirPlay receiver was tested with iPhone mirroring and PIN pairing; its automatic startup after reboot was also observed, with a delay while the boot-broadcast queue advanced.

The owner later confirmed the final configured setup was working after another reboot and marked usage acceptance complete. That final setup includes an independently configured third-party Home outside this repository's supported launcher flow. Its success must not be attributed to `launcher/build-and-install.sh` or used to widen generic Fire OS Home support.

No speedup, playback resolution or DRM quality was measured. The later owner acceptance is distinct from the earlier instrumented checks. No private device identifiers, account data, screenshots or raw logs are included.
