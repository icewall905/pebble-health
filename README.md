# Pebble Health (Android)

A native Android companion for a self-hosted **Pebble health hub** (the `health-hub`
service in [GravelEx](https://github.com/icewall905/gravelex)). It reads your own hub
over HTTPS and shows a daily overview, detailed charts and trends for data recorded by a
Pebble watch and uploaded by GravelEx.

Not affiliated with Core Devices, Pebble or any wearable vendor. Not a medical device.

## Features

- **Today**: steps ring and goal, readiness, stress, sleep timeline, heart rate, intensity
  minutes, calories, pulse ox, watch battery, weight, last activity and the hub's AI insight
- **Detail views** with Day / Week / Month / Year ranges for steps, heart rate (zones,
  resting trend) and calories; sleep nights with restful segments; stress, readiness,
  SpO₂, weight and battery history
- **Activities** grouped by week, with heart-rate and cadence charts
- **Trends**: weekly averages for 12 weeks to a year, step-goal calendar
- **Insights** compares recent measurements with personal baselines, shows coverage and dated readings,
  and brings together weekly intensity planning, a backtested step estimate, and the hub’s AI Patterns briefing.
  The most recent report is saved on the device for offline use.
- Home-screen widget, background refresh every 30 minutes, offline cache
- Dark and light themes

## Requirements

You need a running health hub that receives data from GravelEx. The
[self-hosting guide](https://github.com/icewall905/gravelex/blob/master/docs/SELF-HOSTING.md)
covers the server, the phone export, the recommended watch settings and the HRV watch app.
The app only reads from the hub, so it shows nothing until the hub has data.

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
