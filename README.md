<p align="center">
  <img src="docs/assets/banner.svg" alt="VAG Performance Monitor — VAG telemetry through an ordinary ELM327" width="100%">
</p>

<p align="center">
  <b>English</b> · <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <a href="https://github.com/nchalexz/VAG-PerformanceMonitor/releases"><img alt="Version 0.5.0" src="https://img.shields.io/badge/version-0.5.0-E5484D?style=flat-square"></a>
  <a href="LICENSE"><img alt="License MIT" src="https://img.shields.io/badge/license-MIT-38BDF8?style=flat-square"></a>
  <a href="#install"><img alt="Android 7.0+" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white"></a>
  <a href="build.gradle.kts"><img alt="Kotlin 2.2" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=flat-square&logo=kotlin&logoColor=white"></a>
  <a href="build.gradle.kts"><img alt="Jetpack Compose Material 3" src="https://img.shields.io/badge/Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white"></a>
  <a href="#security-and-privacy"><img alt="Google Mobile Services not required" src="https://img.shields.io/badge/GMS-not%20required-F5B33C?style=flat-square"></a>
</p>

<p align="center">
  <strong>Extended telemetry for Volkswagen, Audi, Škoda, Seat and Cupra through an ordinary ELM327.</strong><br>
  Dashboard, home-screen widget and car screen on one connection. No GMS, no account, no cloud.
</p>

<p align="center">
  <a href="#interface">Interface</a> ·
  <a href="#android-auto">Android Auto</a> ·
  <a href="#install">Install</a> ·
  <a href="#what-already-works">Features</a> ·
  <a href="#what-is-tested-what-is-not">Status</a> ·
  <a href="#build">Build</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="#documentation">Documentation</a>
</p>

> [!WARNING]
> **0.5.0 is a pre-release test build, not a verified release.**
> It runs on an Android 15 emulator against a software ELM327 stub, but it has **not been tested in a
> car**, on a real phone, on an Android Auto head unit or on live Bluetooth/USB adapters. No signal
> has been confirmed on a vehicle. See [What is tested](#what-is-tested-what-is-not).

> [!NOTE]
> 0.5.0 contains the dashboard, the VAG UDS catalog and a read-only telemetry provider for
> DashGrid. It is still not verified in a car, on a head unit or with live adapters; the DashGrid
> cockpit was checked on an emulator with VPM's demo values.

> [!NOTE]
> **По-русски.** 0.5.0 — тестовая сборка для телеметрии VAG: 129 сигналов, приборка, виджет и
> отдельная AA-сборка. Проверено на эмуляторе Android 15 с программной заглушкой ELM327; на
> реальном автомобиле, телефоне и головном устройстве — нет. Сборки `standard` и `aa` используют
> один `applicationId`, поэтому ставьте только одну. Полный перевод и подробности —
> [README.ru.md](README.ru.md).

## What this is

VAG Performance Monitor is a standalone Android app for continuous VAG telemetry. It grew out of the
[VAG Diag](https://github.com/gdm0991/vagdiag) diagnostics core, but solves a different problem:
not a one-off fault scan, but gauges that stay in view while you drive.

One telemetry service feeds the dashboard, the home-screen widget and the car screen. Those three
consumers share one ELM327 connection; the diagnostics screen still opens its own, so do not run a
block scan and telemetry at the same time.

The app has its own `applicationId`, name and icon, and installs next to VAG Diag. Google Mobile
Services are not required, there is no sign-up, and vehicle data never leaves the device.

Three principles shaped the app:

- **Honest data.** If a signal is unsupported, stale or not read yet, the UI shows “—” instead of
  zero.
- **One telemetry connection.** Dashboard, widget and car screen read one state stream and one
  adapter; diagnostics is a separate consumer.
- **Read-only UI.** The telemetry interface never writes to a control module. The inherited manual
  DTC clear is the only write path, and it requires an explicit tap.

## Interface

<p align="center">
  <img src="docs/assets/screen-driving.png" width="31%" alt="Dashboard: three analog gauges and five tiles">
  <img src="docs/assets/screen-performance.png" width="31%" alt="Extended dashboard: category tabs and adaptive metric grid">
  <img src="docs/assets/screen-data.png" width="31%" alt="Data catalog: OBD-II and VAG UDS signals with requests and values">
</p>

<p align="center"><sub>Screenshots from the Android 15 emulator at 1920×720, Demo Mode. They are not a car and not proof of compatibility.</sub></p>

| Part | What it does |
|---|---|
| **Dashboard** | Coolant, boost and oil temperature in three analog-digital gauges; speed, RPM, gear, instant consumption and fuel level in five tiles. Opens from the gauges icon on the diagnostics screen. |
| **Extended dashboard** | The same three gauges smaller, a category bar (Engine, Temperatures, Fuel, Gearbox, Turbo, Diagnostics) and an adaptive grid — five columns on a wide screen. Tapping a gauge opens its category. |
| **Data catalog** | All 129 signals with search, category filter, value, request (PID/DID), ECU, protocol and verification status. |
| **Day theme** | The same composition with separate light tokens, not an inverted image. See [screen-day.png](docs/assets/screen-day.png). |

Swipe horizontally between the two dashboards. Theme and label language are set in
**Settings → Cluster**: Auto / Day / Night and System / English / Russian. English is the default;
a Russian system switches the labels automatically. The selected category survives restarts, while
the dashboard always opens first.

## Android Auto

<p align="center">
  <img src="docs/assets/android-auto.png" width="100%" alt="Mockup of the VAG Performance Monitor ListTemplate screen with status and signal rows">
</p>

<p align="center"><sub>Mockup of the actual <code>ListTemplate</code> composition, not a head-unit screenshot. A real screenshot is not available: the emulator ships stub Android Auto, and neither DHU nor a head unit was tested.</sub></p>

Android Auto is a separate **`aa`** build, not a mode of the standard one. It replaces `standard`
(both share one `applicationId`) and adds a `CarAppService` from Car App Library 1.7.0.

- The screen is a template list: connection state plus up to eight parameters from the selected
  profile. Values update from the same `Telemetry` state.
- The stock host does not allow arbitrary Canvas content, so this is a list, not a copy of the
  dashboard. The full dashboard remains in the regular build.
- `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` is used for sideloading and AARE. Publishing on Google
  Play would require a host allowlist and category review.
- The build has not been tested on a head unit or in the Desktop Head Unit. If a host does not list
  the app, that is a host limitation, not a missing `CarAppService`.
- To test locally, install `*-AA.apk`, connect the phone to a DHU or head unit and open
  “VAG Performance Monitor” from the host app list.

More: [docs/AA.md](docs/AA.md) and [docs/AARE_DASHGRID.md](docs/AARE_DASHGRID.md).

## One telemetry — three surfaces

<p align="center">
  <img src="docs/assets/surfaces.svg" alt="0.5.0 mockup of three surfaces: dashboard, widget and Android Auto" width="100%">
</p>

<p align="center"><sub>A 0.5.0 mockup, not screenshots: the dashboard, widget and car screen read one state.</sub></p>

| Surface | What it shows | Connection |
|---|---|---|
| **Phone dashboard** | Full-screen gauges and tiles with demo/live switching. | Shared `Telemetry` state |
| **Home-screen widget** | Up to four parameters or a compact two-parameter layout. | Shared `Telemetry` state |
| **Android Auto / AARE** | A template list in the `aa` build. On DashGrid 1.3.0 the cockpit is drawn by DashGrid from VPM’s read-only snapshot; VPM’s own `CockpitActivity` stays unexported. | Shared `Telemetry` state / snapshot |

All three read one `StateFlow` from the process-wide `Telemetry` object, and the widget is refreshed by
`TelemetryService`. A second OBD session is not opened. The diagnostics screen is the exception: it
owns its own `Elm327`, so the two should not scan at the same time.

## What already works

**Telemetry**

- 129 signals: 41 standard OBD-II PIDs, 4 calculated values, 1 virtual signal and 83 VAG-specific
  UDS `22 <DID>` signals for MQB TDI.
- Subscription engine with fair polling for mixed OBD/UDS, adaptive rates, exponential backoff and
  data-age tracking.
- Calculated torque, power, boost and instant l/100 km. They become “no data” when inputs are
  missing; consumption is undefined at standstill instead of infinite.
- One process-wide repository: dashboard, widget and car screen read the same `StateFlow`.
- Last-value cache, saved adapter and auto-connect on app start.

**Dashboard and UI**

- Dashboard and Extended Dashboard plus an overlay data catalog; swipe, gauge-to-category tap and
  back navigation.
- Three analog-digital gauges and five tiles, then an adaptive five-column grid: the composition of
  the reference mockups.
- Day and night themes with separate tokens; Auto follows the system, with a manual override.
- English and Russian labels. English by default; Russian on a Russian system; manual override.
- Unsupported, stale or missing values show “—” and a status dot, never a zero.
- Demo Mode starts on the mockup values (86 °C, 0.62 bar, 95 °C, 96 km/h, 1840 rpm, gear 5, 48 %,
  21 %, 19 %, 162 kPa, 100 kPa, 42.5 g/s, 14.6 V, 58 %) and is always marked **DEMO**.

**VAG-specific data**

- UDS `22 <DID>` reader over ELM327 with per-module headers, 11-bit CAN, flow-control retries and a
  short per-DID cache; the next OBD request returns to header `7DF`.
- 83 signals derived from the open **OBDb/Audi-Q3** set (CC BY-SA 4.0, commit `b51e835`): boost
  actual/specified, rail pressure, injection deviations, DSG oil and clutch temperatures, clutch
  pressures, DPF soot, exhaust temperatures, battery sensor and engine/cluster oil temperature.
- Read-only: only `22` and `3E` are sent. Signals that need the extended session `10 03` are
  excluded; there is no coding, adaptation or actuator test.
- Every VAG signal is marked **decoded**, not “confirmed on car”. The vehicle still has to prove it.

**Settings and profiles**

- Adapter choice: Wi-Fi, Bluetooth SPP or USB serial, with connect, demo and disconnect in one place.
- Auto-connect to the saved adapter at launch.
- Editable profiles: VAG MQB and a generic OBD-II profile, with a manual signal selection.
- Cluster theme and label language, saved between launches.
- A VIN field exists, but there is no automatic VIN lookup yet.

**Widget and Android Auto**

- “Telemetry VAG” widget: up to four parameters or a compact two-parameter layout, per-instance
  configuration, light/dark theme and tap-to-open.
- `aa` build with Android Auto through Car App Library 1.7.0: connection state and up to eight
  profile parameters in a template list.
- The `standard` build has no car dependencies and does not declare `CarAppService`.
- A foreground service with a notification keeps the process alive and refreshes the widget while
  telemetry is needed.

**Diagnostics and AARE**

- ELM327 transports: Wi-Fi, Bluetooth SPP and USB serial (CH340, FTDI, CP210x, PL2303).
- The inherited VAG Diag screen: connect, scan modules, read and manually clear DTCs, OBD-II engine
  data, live data and a report.
- The full dashboard is an ordinary Activity, but `CockpitActivity` is `exported="false"`, so
  another app cannot launch it. VPM stays standalone: its phone dashboard, widget and diagnostics
  need no host. On a car screen DashGrid 1.3.0 draws its own cockpit from the read-only bridge
  (`call("snapshot")` returns the current values, no OBD opened by DashGrid). The bridge is
  contract v1; the service and the cockpit Activity stay `exported="false"`.

**Verification**

- 61 JVM tests: decoding, UDS bit fields, catalog integrity, payload parsing, polling schedule,
  backoff, staleness, fuel consumption, demo-model consistency and the read-only bridge snapshot.
- A fake ELM327 transport test covers OBD→UDS header switching and negative UDS answers.
- Demo mode opens every screen without an adapter or a car.
- Dashboard, Extended Dashboard, catalog, settings, themes and languages were checked on an
  Android 15 emulator at 1920×720. The real car, phone and head unit were not.

## Install

The two builds share one `applicationId` and **replace each other** when installed. Pick one:

| Build | What is inside | Size |
|---|---|---|
| `standard` | Dashboard, widget, diagnostics; no Android Auto. | ≈18.8 MB |
| `aa` | The same plus Android Auto (Car App Library). | ≈20.1 MB |

Requirements: Android 7.0+ (API 24), an ELM327 over Wi-Fi, Bluetooth SPP or USB serial, and network,
Bluetooth, USB-host and notification permissions. Google Mobile Services are not required.

1. Download the APK you need from [Releases](https://github.com/nchalexz/VAG-PerformanceMonitor/releases).
2. Allow installs from unknown sources when Android asks, then open the file.
3. The app installs separately from VAG Diag.

Through `adb`:

```sh
# standard build
adb install -r VAG-Performance-Monitor-0.5.0-standard.apk

# Android Auto build
adb install -r VAG-Performance-Monitor-0.5.0-AA.apk
```

<details>
<summary>SHA-256 and signing certificate</summary>

```text
aaa19bc2be04bc6eb1537f0473af565200b7e0d6f417be369de03351c2172dbb  VAG-Performance-Monitor-0.5.0-standard.apk
0438e1dfc8e1b5a906d2ee31bacd3c4ab38f1a514999fe2db2b546ab92b2ad47  VAG-Performance-Monitor-0.5.0-AA.apk
certificate SHA-256: b00777ed843ab301f23876f68b67d60738a9fe84667bcc67cadf33620b5be73d
```

The APKs are signed with the Android Debug key (`CN=Android Debug`). Check with
`apksigner verify --print-certs <file>.apk`.

</details>

> [!NOTE]
> These are debug builds signed by the CI runner's `CN=Android Debug` key. Its certificate differs
> from 0.4.1's, so updating from 0.4.1 needs an uninstall first (app settings are lost). The two
> builds cannot be installed at the same time, and a switch to a release signature will also need an
> uninstall.

## Quick start

### Demo on the phone

1. Open the app; the diagnostics screen appears.
2. Tap the gauges icon in the top bar.
3. Swipe between Dashboard and Extended Dashboard and open categories: the Demo badge marks simulated data; no adapter or car is needed.
4. Open the gear icon to choose a profile, signal set and adapter.
5. Add the “Telemetry VAG” widget from the launcher and pick up to four parameters.

### With an ELM327

1. Connect the adapter over Wi-Fi, Bluetooth or USB.
2. Open **Vehicle and adapter**, choose the adapter type and address, then tap **Connect**. The
   diagnostics screen can also connect.
3. Return to the dashboard: the badge changes to **LIVE** and the header shows the channel and
   voltage.
4. Compare RPM, speed and coolant temperature with the car’s own cluster.

If a module does not support a signal, it stays “—”. That is expected behaviour, not a rendering
bug.

### Widget

1. Long-press an empty spot on the launcher and choose **Widgets**.
2. Find **VAG Performance Monitor → Telemetry VAG**.
3. Pick a title and up to four parameters, then save.

> [!CAUTION]
> Keep the dashboard on while driving, but do not configure it on the move. Searching, switching
> screens and reading DTCs belong to a parked car.

## What is tested, what is not

| Check | How | Result |
|---|---|---|
| `standard` debug build | `./gradlew :app:assembleStandardDebug` | **yes** |
| `aa` debug build | `./gradlew :app:assembleAaDebug` | **yes** |
| 61 JVM tests | `./gradlew :app:testStandardDebugUnitTest` | **yes** |
| Android 15 emulator: Dashboard, Extended, catalog, settings, themes/language, LIVE via ELM327 stub | emulator | **yes** |
| VAG UDS decode and OBD/UDS header switching | fake ELM327 transport, JVM test | **yes** |
| Widget placed and configured | Android 15 emulator launcher | **yes** |
| Widget live value updates | live connection | **no** |
| Real phone | hardware | **no** |
| DashGrid 1.3.0 native cockpit | Android 15 emulator, contract v1 `call("snapshot")` | **yes** |
| Android Auto head unit / DHU | Car App Library host | **no** |
| AARE / DashGrid | actual host and display | **no** |
| Real car | VAG MQB vehicle | **no** |
| Bluetooth and USB on live adapters | hardware | **no** |

Unit tests, emulator runs and renders are **not** compatibility proof. The catalog has 128 signals
marked “decoded” and one “described”; none is confirmed on a vehicle. The 83 VAG DIDs have never
answered on a real MQB TDI yet. The exact open points
are in [TEST_PLAN.md](TEST_PLAN.md) and [COMPATIBILITY.md](COMPATIBILITY.md).

## Build

Needs JDK 17+ and Android SDK with platform 36 and build-tools 36.0.0:

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties

# standard build
./gradlew :app:assembleStandardDebug

# Android Auto build
./gradlew :app:assembleAaDebug
```

Outputs:

```text
app/build/outputs/apk/standard/debug/app-standard-debug.apk
app/build/outputs/apk/aa/debug/app-aa-debug.apk
```

Tests:

```sh
./gradlew :app:testStandardDebugUnitTest
```

<details>
<summary>Release signing</summary>

Signing reads environment variables; no passwords are stored in the repository:

```sh
export VAGDIAG_KEYSTORE=/path/to/keystore.jks
export VAGDIAG_STOREPASS=...
export VAGDIAG_KEYALIAS=...
export VAGDIAG_KEYPASS=...
./gradlew :app:assembleStandardRelease :app:assembleAaRelease
```

Without `VAGDIAG_KEYSTORE`, the release APK is unsigned.

</details>

## Architecture

```text
CockpitActivity     AppWidget     CarAppService (aa)     SettingsActivity
        └──────────────┴───────────────┴──────────────────┘
                 Telemetry (singleton, StateFlow)
                              │
                       TelemetryEngine  ──►  SignalCatalog
                              │
                       SignalSource
                       ├── DemoSignalSource   — no car required
                       └── Elm327SignalSource — OBD-II mode 01 and UDS 22 <DID>
                              │
                           Elm327  ──►  Transport (Wi-Fi / Bluetooth / USB)
```

`Telemetry` is the only owner of the telemetry connection and engine. Every surface subscribes to its
state and never opens a second telemetry session. `TelemetryService` runs alongside it for the
foreground status, notification and widget refresh.

The VAG Diag screen keeps its own `Elm327` and is outside this tree. Commands are serialized inside
one adapter object, but the two connections can still compete for the bus.

The catalog knows nothing about the engine, the engine knows nothing about Android, and the UI knows
nothing about the transport. That is why decoding and polling can be tested with plain JVM tests.

More: [ARCHITECTURE.md](ARCHITECTURE.md).

## Signal catalog

| Group | Signals |
|---|---:|
| Engine | 34 |
| Temperatures | 13 |
| Fuel | 20 |
| Emissions | 4 |
| Electrical | 1 |
| Diagnostics | 27 |
| Transmission | 30 |
| **Total** | **129** |

Breakdown: 41 measured OBD-II PIDs, 4 calculated values, 1 virtual signal and 83 VAG UDS signals.
The VAG set is derived from OBDb/Audi-Q3 (CC BY-SA 4.0); every entry is marked “decoded”, and none
is confirmed on a car. See [SIGNAL_CATALOG.md](SIGNAL_CATALOG.md) and
[tools/README.md](tools/README.md).

## Limitations

- No hardware verification: no real phone, car, head unit or live Bluetooth/USB adapter.
- Android Auto is a template list only. Google Play publication needs a host allowlist and category
  review; sideloading and AARE use `ALLOW_ALL_HOSTS_VALIDATOR`.
- AARE/DashGrid: the read-only bridge (contract v1) is in 0.5.0 and was verified with DashGrid
  1.3.0 on an emulator. The standard Android AppWidget has still not been placed on DashGrid, and
  no DashGrid head-unit run was possible.
- Profiles are manual; there is no VIN-based selection.
- VAG DIDs come from one open Audi-Q3 set. They are decoded, not confirmed on an MQB TDI; some
  blocks will refuse a DID or answer only after `10 03`, which this app does not send.
- Only `22` reads and `3E` keep-alive are sent. There is no ODX/VCDS import, no session change,
  no coding or adaptation, and no DPF regeneration.
- No trip recording, history, graphs or export.
- No dashboard editor; the five tiles and three gauges are fixed for now.
- No KWP2000 or TP2.0; BLE adapters are not supported.
- No automatic reconnect after a drop; auto-connect works at app start only.
- The `standard` and `aa` builds share one `applicationId` and cannot be installed together.
- Diagnostics opens its own adapter connection; do not scan modules while telemetry is running.

The next versions and priorities are in [ROADMAP.md](ROADMAP.md) and [BACKLOG.md](BACKLOG.md).

## Security and privacy

The app is a monitor. The telemetry interface only reads. The diagnostics screen inherited from VAG
Diag includes manual DTC clearing — the only write operation; it runs on an explicit tap and never
automatically.

Network access is used only for the Wi-Fi adapter. Vehicle data is not sent to third-party services;
there are no analytics, trackers or crash reporters. The foreground service shows a persistent
notification and does not hide its work.

The `aa` build uses `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` for sideloading and AARE. A Google Play
release would need a host allowlist. More: [SECURITY.md](SECURITY.md).

## Documentation

| File | About |
|---|---|
| [README.ru.md](README.ru.md) | Full Russian translation of this file |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Layers, engine, shared connection, widget and AA |
| [RESEARCH.md](RESEARCH.md) | Sources studied, commit SHAs, licences, decisions |
| [SIGNAL_CATALOG.md](SIGNAL_CATALOG.md) | Catalog counts and signal origins |
| [COMPATIBILITY.md](COMPATIBILITY.md) | Cars, adapters, protocols and surfaces |
| [SECURITY.md](SECURITY.md) | Write boundaries, permissions, dependencies |
| [TEST_PLAN.md](TEST_PLAN.md) | Automated tests and manual scenarios |
| [ROADMAP.md](ROADMAP.md) | Stages and their state |
| [BACKLOG.md](BACKLOG.md) | Prioritized work |
| [docs/UI.md](docs/UI.md) | Dashboard design system, components, themes and navigation |
| [docs/WIDGET.md](docs/WIDGET.md) | Widget sizes, themes and refresh |
| [docs/AA.md](docs/AA.md) | Android Auto build, templates and testing |
| [docs/AARE_DASHGRID.md](docs/AARE_DASHGRID.md) | Widget hosting and the read-only telemetry bridge |
| [docs/SPEC.md](docs/SPEC.md) | Full technical specification |
| [CHANGELOG.md](CHANGELOG.md) | Change history |
| [docs/releases/0.5.0.md](docs/releases/0.5.0.md) | 0.5.0 release notes |
| [docs/releases/0.4.1.md](docs/releases/0.4.1.md) | 0.4.1 release notes |
| [docs/releases/0.4.0.md](docs/releases/0.4.0.md) | 0.4.0 release notes |
| [docs/releases/0.3.0.md](docs/releases/0.3.0.md) | 0.3.0 release notes |
| [tools/README.md](tools/README.md) | Regenerating the open VAG signal set |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Code, data and asset provenance |

## Contributing

The project is open to help, but data beats enthusiasm:

- **Test on a car.** The checklist is in [TEST_PLAN.md](TEST_PLAN.md). Record results in
  [COMPATIBILITY.md](COMPATIBILITY.md), not in a chat thread.
- **Add signals carefully.** A new signal needs a verified PID/DID, formula and source. Until it is
  read on a car, its status stays “described” or “decoded”.
- **Report bugs.** Include adapter model, Android version, screen, steps to reproduce and, if
  possible, `adb logcat`.
- **Translate.** The English README is the main one; [README.ru.md](README.ru.md) is maintained
  alongside it.

## License

MIT. The diagnostics core is based on [VAG Diag](https://github.com/gdm0991/vagdiag) (MIT, commit
`c2d565f`). The catalog, telemetry engine, dashboard, widget and car screen were written for this
project. Titillium Web is used under the SIL Open Font License; AndroidX, Compose and Car App Library
are Apache 2.0; `usb-serial-for-android` is MIT. Details are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Android Auto is a Google trademark; this project is not affiliated with Google.

<p align="center"><sub>Built for VAG — and for everyone who finds a temperature needle insufficient.</sub></p>
