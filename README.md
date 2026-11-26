# Pebble Health (Android)

A native Android companion for a self-hosted **Pebble health hub** (the `health-hub`
service in [GravelEx](https://github.com/icewall905/gravelex)). It reads your own hub
over HTTPS and shows a daily overview, detailed charts and trends for data recorded by a
Pebble watch and uploaded by GravelEx.

Not affiliated with Core Devices, Pebble or any wearable vendor. Not a medical device.

## Features

- **Today**: upload, battery and coverage status; the AI briefing; a timeline from 18:00
  yesterday to midnight with sleep, workouts, heart rate over intensity zones, steps, stress
  and SpO₂ (tap or drag to read every lane); readiness with each input; sleep against your
  need and usual times; recovery signals (HRV against its usual range, overnight pulse,
  pulse during sleep, SpO₂, stress); activity; eight tiles comparing the last week with
  your usual; training, the latest workout and a year heatmap. Cards pair up on tablets
- **Detail views** with Day / Week / Month / Year ranges for steps, heart rate (full-day
  timeline, zones, resting trend) and calories; sleep night by night with restful segments
  and gaps; recovery (HRV, overnight and sleep pulse); stress, readiness, SpO₂, weight and
  battery history
- **Activities** grouped by week, with heart-rate and cadence charts
- **Log workout** with phone GPS, Bluetooth chest-strap heart rate, laps and strength sets.
  Review routes, splits and charts, save GPX files, and send workout summaries to your hub.
- **Trends**: weekly averages for 12 weeks to a year, step-goal calendar
- **Insights** compares recent measurements with personal baselines, shows coverage and dated readings,
  and brings together weekly intensity planning, a backtested step estimate, and the hub’s AI Patterns briefing.
  The most recent report is saved on the device for offline use.
- Home-screen widget, background refresh every 30 minutes, offline cache
- Dark and light themes

## Requirements

The dashboard needs a running health hub that receives data from GravelEx. The
[self-hosting guide](https://github.com/icewall905/gravelex/blob/master/docs/SELF-HOSTING.md)
covers the server, the phone export, the recommended watch settings and the HRV watch app.
Phone workout recording and local history are available without a hub.

- Android 10 (API 29) or newer
- The hub reachable over **HTTPS** from the phone, through a reverse proxy or a VPN

## Install with Obtainium

1. In Obtainium, tap *Add app* and enter `https://github.com/icewall905/pebble-health`.
2. Each release contains one signed `PebbleHealth.apk`, plus `SHA256SUMS` and
   `signature.txt`.
3. Check that the signing certificate SHA-256 matches [`docs/signing-sha256.txt`](docs/signing-sha256.txt):
   `50aa6dc33376908cfaccd09c21d2495539acc0714f6246b32d594b394633205e`

## Connect

1. On the hub dashboard, open **More → API access**. Create a token with the scopes
   `summary:read` and `history:read`, then copy it. It is shown only once.
2. In the app, enter the hub base URL (for example `https://health.example.net`) and the
   token. The app checks both with `/api/v1/dashboard` before saving them.

The token is encrypted with an AES-GCM key kept in the Android Keystore. To revoke access,
delete the token on the hub.

The app reads these hub endpoints (documented in the hub's
[API.md](https://github.com/icewall905/gravelex/blob/master/health-hub/API.md)):

| Endpoint | Used for |
|---|---|
| `/api/v1/dashboard?days=` | Today, sleep, HRV, readiness, stress, activities, insights |
| `/api/v1/daily?from&to` | Week, month and year charts, and trends |
| `/api/v1/intraday?date=` | Day charts (HR, steps, sleep) |
| `/api/v1/insights` | Personal baselines, evidence charts, coverage and activity planning (`history:read`) |

Metrics marked "Estimate" come from wrist data that the hub derives in documented ways.
See [METRICS.md](https://github.com/icewall905/gravelex/blob/master/health-hub/METRICS.md).
Empty cards mean the hub has no data for them yet; they do not mean zero. The self-hosting
guide's timeline lists when each metric first appears.

## Record a workout

Open **Activities → Log workout**, choose a sport, and turn on phone GPS if you want a route.
Wear your Bluetooth chest strap and tap **Find chest strap** to select it. Workout history
and GPX export are available offline. If the strap includes beat-to-beat intervals or its
energy counter in Bluetooth heart-rate notifications, those readings are saved with the
workout. Use **Export chest strap CSV** in the workout details to export them.

Workout summaries sync using your existing Pebble Health dashboard sign-in in the app.
If you have only signed in through a browser, open **Settings → Dashboard sign-in** once.
The app requests the upload token and queues unsynced local workouts automatically. Use
**Sync now** from a saved recording to retry. GPS coordinates and strength sets stay on the phone.

To clean up duplicate watch activities, open the unwanted activity and choose
**Remove from dashboard**. This excludes it from training totals, and **Show removed**
lets you restore it later. **Delete from phone** removes the local recording itself.

### Strength recording

Choose **Strength** to search or enter an exercise, pin up to five favorites, and use
the set-count and weight shortcuts. Check the reps per set before logging. Multiple
identical sets can be recorded together, or logged one by one as you finish them.
Use **Set rest** to start a 30, 60 or 90 second break. Pebble Health shows when rest is
complete and posts a phone alert if notifications are allowed. Pebble Health Workout shows the
current exercise, sets logged and countdown alongside heart rate and zones.

### Start from your watch

On Android, open **Record workout** and tap **Ready for watch start** before locking
your phone. This waits for one watch-started workout for up to an hour. It does not
record a route before you select a sport. Open Pebble Health Workout, press **Select**, and
choose a sport. During recording, **Select** opens controls for pause, resume, laps
or strength rest and repeat-set, and finishing or discarding. Discard requires a
second confirmation and deletes the unsaved recording. If Android blocks an
unprepared start, tap the start notification to open the workout on your phone.

A watch-started recording runs without the screen. The phone holds a wake lock only while
recording, so GPS, chest-strap heart rate, checkpoints and the watch stats keep flowing with
the screen off, and a locked screen never ends a recording. The app declares the companion
device background permissions, which lets the recording start while Pebble Health is in the
background. If a phone still refuses that start, the notification carries **Start** and
**Not now** buttons that work from the lock screen.

To open the live phone display automatically, tap **Enable auto-open live display**
under **Record workout** and allow **Display over other apps** in Android settings.
This applies when you pick a sport or accept an automatic walk or run prompt on the
watch. The live display can appear over the lock screen. If you leave this permission
off, tap the phone's recording notification to open the live display.

### Watch heart rate during recording

With watch mirroring enabled and Pebble Health Workout open on a watch with a
heart-rate sensor, the phone uses wrist readings when the Bluetooth strap stops sending
heart rate. It switches back as soon as strap readings resume. Wrist readings count
toward the workout's heart-rate chart and zones. The chest strap CSV contains strap
samples only.

### Browse from the watch

On the home screen, **Up** opens **Live stats** with heart rate and phone GPS and chest
strap status. It also reads the wrist sensor on a compatible watch while the screen
is open, even before a workout or when the phone is out of range. During a recording,
press **Up** again for the workout display.
**Down** opens **Menu** from home, live stats or a workout. **History** shows the eight most
recent phone and synced dashboard activities. Scroll with **Up** and **Down**, and use
**Select** to see an entry's details. The last fetched list is available if the phone is
out of range. In **Media Control**, press **Up** for the previous track, **Select** to play
or pause, and **Down** for the next track. **Back** returns to the menu. These controls
use the Android phone's media player over PebbleKit 2. Open the player on your phone
first. From home, **Select** opens the sport picker or controls for the recording
in progress.

### Automatic walk and run detection

Auto-detection is enabled by default on watches with Pebble Health. Open **Pebble
Health Workout** once to start its background worker. PebbleOS allows one worker
at a time and may ask if you want to replace another app's worker.

When PebbleOS recognizes a walk or run, the default **On detect: Start** requests
a phone workout with GPS. Use **Ready for watch start** before locking your phone,
as described above. If the watch says **Check phone to start**, check for the
start notification or open **Record workout** on your phone.

On the watch, press **Down** and open **Menu → Settings**. Choose **On detect:
Ask** to show the full-screen prompt. Press **Select** to start or **Back** to
dismiss it. The prompt closes after two minutes. Change **Auto-detect** to **Off**
to disable the worker. Both choices are saved on the watch. Pebble's built-in activity logging and GravelEx sync continue independently.

### Live map and watch stats

GPS recordings show the OpenStreetMap street map by default during recording and review.
Tap **Hide street map** to switch to the route preview and remember that choice. Tile requests
reveal the map viewport to OpenStreetMap. The recorded route and GPX remain local.

In **Settings → Workouts**, select the Pebble phone app that manages your watch. Tap
**Install Pebble Health Workout on Pebble** to install the companion declaration used by PebbleKit 2.
In GravelEx, enable **PebbleKit 2** under Watch App Permissions and allow Pebble Health if
prompted. Enable **Launch Pebble Health Workout on start** in Pebble Health. The recording screen
shows whether the watch is receiving stats. The watch receives active time, distance, live
pace and heart rate from the recording phone. Use **Set max HR for zones** before recording
to show your current zone on the phone and watch. Pebble Health Workout fills your active zone
and marks your position within it. Strap battery appears when the sensor
exposes a readable Bluetooth Battery Level. Use **Open Pebble Health Workout** on the recording
screen to retry launching it. The bundled app targets Aplite, Basalt, Chalk, Diorite,
and Emery. To rebuild it with the Pebble SDK, run `pebble build` in `watchapp/` and copy
`watchapp/build/watchapp.pbw` to `app/src/main/assets/Pebble-Health-Workout.pbw`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "HTTP 401" or "403" | The token was revoked, expired, or lacks `summary:read` or `history:read`. Create a new token. |
| Cannot connect | Open the URL in the phone's browser. It must be HTTPS with a valid certificate. |
| Values are old | Pull down to refresh. If they are still old, check GravelEx's export (Test/Sync) and the hub's **More → Collection health**. |
| Widget not updating | Turn off battery optimisation for the app. The widget refreshes every 30 minutes through WorkManager. |

## Build

Requirements: JDK 21 and an Android SDK with platform 37 and build-tools 37.0.0.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug   # debug build, package org.tdgs.ph.debug
./gradlew :app:lintRelease :app:assembleRelease       # unsigned release APK
```

`versionCode` is the git commit count and `versionName` is the nearest git tag. Clone with
full history (`fetch-depth: 0`).

Code layout (`app/src/main/java/org/tdgs/ph/`):

| Path | Contents |
|---|---|
| `data/` | API client (OkHttp), JSON parsing, models, encrypted settings, repository with offline cache |
| `ui/screens/` | Today, metric details, activities, trends, insights, settings and setup |
| `ui/components/` | Charts and cards |
| `workout/` | Phone recording, local history, charts, GPX, and upload setup |
| `widget/` | Glance home-screen widget and the WorkManager refresh job |

## Releasing your own fork

Releases are built, signed and published by [`.github/workflows/build.yml`](.github/workflows/build.yml)
when a `v*` tag is pushed. To publish your own signed builds:

1. Generate a keystore outside the repository and keep a private backup. If you lose it,
   updates can no longer be installed over the existing app.
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Write the certificate fingerprint to `docs/signing-sha256.txt`, in lower case without
   colons. CI refuses to publish an APK signed with any other key.
   ```bash
   keytool -list -v -keystore release.jks -alias release | awk '/SHA256:/{print tolower($2)}' | tr -d ':' > docs/signing-sha256.txt
   ```
3. Add the repository secrets:
   ```bash
   base64 -w0 release.jks | gh secret set PH_KEYSTORE_BASE64
   gh secret set PH_KEYSTORE_PASSWORD
   gh secret set PH_KEY_ALIAS        # e.g. release
   gh secret set PH_KEY_PASSWORD
   ```
4. Tag and push: `git tag v0.2.0 && git push origin v0.2.0`. CI then runs tests and lint,
   signs and verifies the APK, and creates the GitHub release that Obtainium tracks.
5. If you change `applicationId`, also update the package check in `build.yml`.

## License

GPL-3.0-or-later.
