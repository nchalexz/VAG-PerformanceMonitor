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

## Titillium Web

- Source: https://github.com/google/fonts/tree/main/ofl/titilliumweb
- Licence: SIL Open Font License 1.1 — full text in
  `app/src/main/assets/licenses/ofl-titillium-web.txt`.

## AndroidX / Jetpack Compose, Material Icons

- Source: https://developer.android.com/jetpack/androidx
- Licence: Apache License 2.0 (see the dependency metadata).

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
