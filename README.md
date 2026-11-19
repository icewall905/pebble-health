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
- Home-screen widget, background refresh every 30 minutes, offline cache
- Dark and light themes

## Install with Obtainium

1. In Obtainium, *Add app* → `https://github.com/icewall905/pebble-health`
2. Releases contain a single signed `PebbleHealth.apk`.
3. Verify the signing certificate SHA-256:
   `50aa6dc33376908cfaccd09c21d2495539acc0714f6246b32d594b394633205e`

## Connect

On the hub dashboard open **More → API tokens**, create a token with the scopes
`summary:read` and `history:read`, then enter the hub address (https only) and token in
the app. The token is encrypted with a key held in the Android keystore.

The app uses these hub endpoints: `/api/v1/dashboard`, `/api/v1/daily`,
`/api/v1/intraday`.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Releases are built, signed and published by `.github/workflows/build.yml` when a `v*`
tag is pushed. The signing key lives outside the repository (GitHub secrets
`PH_KEYSTORE_BASE64`, `PH_KEYSTORE_PASSWORD`, `PH_KEY_ALIAS`, `PH_KEY_PASSWORD`).

## License

GPL-3.0-or-later.
