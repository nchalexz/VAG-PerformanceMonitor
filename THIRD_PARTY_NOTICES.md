# Third-party notices

## VAG Diag (base of the diagnostics core)

- Source: https://github.com/gdm0991/vagdiag
- Commit used as the base: `c2d565fb27deedb645b6ee38ea0fd724cb269d64`
- Licence: MIT
- Reused: `app/src/main/java/io/github/nchalexz/aaperfmon/core/` (ELM327 link, transports,
  UDS helpers, DTC codes) and the surrounding Android project scaffold.
- The dashboard UI (`cockpit/`) is written for this project and is not from VAG Diag.

```
MIT License

Copyright (c) 2026 VAG Diag contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## OBDb/Audi-Q3 signal set

- Source: https://github.com/OBDb/Audi-Q3
- Commit: `b51e835ab75b8bed9ad17f5a31ca4d1d13f22676` (2026-11-21)
- Licence: Creative Commons Attribution-ShareAlike 4.0 International
  (CC BY-SA 4.0), https://creativecommons.org/licenses/by-sa/4.0/
- Reused: 83 VAG MQB TDI signal descriptions — CAN headers, UDS DIDs, bit
  offsets, lengths, signedness, byte order, multipliers/dividers/addends and
  names. The selected source commands are vendored in
  `tools/obdb-audi-q3.json`; the generated Kotlin is
  `app/src/main/java/io/github/nchalexz/aaperfmon/telemetry/VagSignals.kt`.
- The derived signal data is distributed under CC BY-SA 4.0. The application
  code around it remains MIT; the data is attributed here and in the generated
  file header.
- No OBDb data was confirmed on a vehicle for this project. The formulas are
  “decoded”, not “confirmed”.

## Titillium Web

- Source: https://github.com/google/fonts/tree/main/ofl/titilliumweb
- Licence: SIL Open Font License 1.1 — full text in
  `app/src/main/assets/licenses/ofl-titillium-web.txt`.

## AndroidX / Jetpack Compose, Material Icons

- Source: https://developer.android.com/jetpack/androidx
- Licence: Apache License 2.0 (see the dependency metadata).

## AndroidX Car App Library

- Source: https://developer.android.com/training/cars/apps
- Licence: Apache License 2.0.
- Used only by the `aa` product flavor (`androidx.car.app:app` and
  `app-projected`, version 1.7.0). The `standard` build does not include it.

## USB-serial drivers

- `com.github.mik3y:usb-serial-for-android` (MIT) — pulled from JitPack as the base project did.

## AA Performance Monitor (this fork)

- Source: https://github.com/nchalexz/AAPerformanceMonitor
- Licence: MIT
- Added for this project: `telemetry/` (catalog, decoders, engine, sources,
  dashboards), `cockpit/` (dashboard and Live Data UI), `core/AdapterHolder.kt`,
  tests in `app/src/test/`, project documentation.
- The demo model in `DemoSignalSource` is synthetic. It is not a recording of
  a real car and must not be treated as compatibility evidence.
- The visual dashboard follows the MIB/Porsche cluster style described in the
  specification; the implementation is original code, not copied assets.

## AndroidX Car App Library (AA flavor only)

- Source: https://developer.android.com/jetpack/androidx/releases/car-app
- Version: 1.7.0 (`androidx.car.app:app`, `androidx.car.app:app-projected`)
- Licence: Apache License 2.0
- Used only in the `aa` product flavor; the `standard` APK does not include it.
