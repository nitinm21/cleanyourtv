# Frigate Wall

A CleanYourTV module card that opens a full-screen, six-camera Frigate wall. The grid fetches low-resolution
snapshots every five seconds, so the Chromecast does not decode six video streams. Press OK on a tile to
enlarge it and play that camera's go2rtc RTSP stream; Back returns to the snapshot wall. If RTSP is
unreachable or does not start, the enlarged view falls back to snapshots.

The Android app icon uses Frigate's bird mark under its local-use permission; the app is an independent,
unofficial viewer and is not affiliated with or endorsed by Frigate, Inc. See
[`FRIGATE-BRAND-LICENSE.md`](FRIGATE-BRAND-LICENSE.md).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Fetches Frigate's camera list and snapshots, and the selected go2rtc RTSP stream. No telemetry, ads, or cloud service. |
| Cleartext HTTP | Supports a local Frigate URL such as `http://frigate-host:5000`. Use HTTPS when available. |

There is no background service. Snapshot requests stop when the app is not visible.

## Setup

Build and install the app as a normal Android TV app:

```bash
cd addons/frigate-wall
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

On first launch, enter a Frigate URL and choose **Connect**. Like Frigate Glance, the app reads camera
names from `/api/config`, sorts discovered names alphabetically, and ignores `_replay_` debug cameras.
The app reads Frigate camera configuration from `/api/config`; Frigate web-login authentication is not currently supported. Keep that endpoint available only to trusted clients.

To choose a specific camera order, push a `config.json` to the app's external files folder before
launching it. See [`config.example.json`](config.example.json). The example loads every camera automatically.
For a custom order, add a `cameras` array with IDs from your Frigate instance. Set `newCameras` to `true`
to append cameras Frigate adds later. When `cameras` is set, Wall preserves that order and appends newly
discovered cameras alphabetically.

```bash
adb push config.json /sdcard/Android/data/io.github.gathin.frigatewall/files/config.json
```

The CleanYourTV Home launcher discovers the **Frigate Wall** module card. Choose **Show** when Home asks
whether to add it.

Use **Refresh** in the camera wall header to fetch Frigate's current camera and live-stream config again.
Open the TV's **Settings** button to edit the Frigate URL, RTSP host and credentials. **Save & refresh
cameras** applies the local connection settings; these settings do not edit Frigate's server-side configuration.

Live video uses go2rtc's RTSP port `8554`, like Frigate Glance. Wall tries the selected camera's configured
stream and falls back to a Frigate snapshot refreshed once a second if live video cannot start. By default
Wall uses the hostname from `frigateUrl`; set optional `rtspHost` in `config.json` or in the app's connection
screen if RTSP is reachable at a different hostname or IP. Wall selects the first stream listed under that
camera's `live.streams` in Frigate's `/api/config`, or the camera id if no alias is listed. Make port `8554`
reachable from the TV. Video is muted and released when returning to the grid or leaving the app; the grid
remains snapshots only.

To require authentication for clients connecting to go2rtc, set a dedicated RTSP username and password in
Frigate's config and environment:

```yaml
# Frigate docker-compose.yml
environment:
  FRIGATE_GO2RTC_RTSP_USERNAME: "tv_wall"
  FRIGATE_GO2RTC_RTSP_PASSWORD: "use-a-long-unique-secret"

# Frigate config.yml
go2rtc:
  rtsp:
    username: "{FRIGATE_GO2RTC_RTSP_USERNAME}"
    password: "{FRIGATE_GO2RTC_RTSP_PASSWORD}"
```

Restart Frigate, then enter those same credentials in Wall's connection screen. Wall saves them in its
app-private preferences, not in the external `config.json`. These RTSP credentials are separate from camera source credentials and Frigate web-login credentials.
Credentials are stored in the app's private preferences on the TV, not encrypted with a separate key; do
not reuse a sensitive password. Keep go2rtc reachable only from trusted clients using your network
firewall or VPN, and do not expose RTSP port 8554 directly to the public internet.

## Tested on

Chromecast with Google TV 4K (Android 14 / SDK 34), Frigate 0.18.2.
