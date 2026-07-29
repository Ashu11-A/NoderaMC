# Mobile — Open Limitations

<!-- AI-AGENT-INSTRUCTION: One row per gap that is real TODAY. A row leaves only when its exit test
     passes; move it to LIMITATIONS.fixed.md with the evidence. Never delete a row silently. -->

**Category:** mobile · **Last audit:** 2026-07-28 · Open rows: **4** · Retiring rows: **2**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

| id | Limitation | Why it is not closed | Exit test | Status |
|---|---|---|---|---|
| M-1 | An arbitrary shared-storage folder may be picked but unusable | Android 11+ withholds raw `File` access to shared storage regardless of the SAF grant, and the worker writes with `java.io.File`. The app detects and reports this (`NoderaStorage.kt:171-191` probes with a real write); it cannot remove it without a storage layer that speaks `content://`. | A folder outside app-specific storage is picked and the worker writes a world archive into it | OPEN |
| M-2 | The node stops when Android stops the app | The worker is a thread in this process because an app may not spawn a VM (`NoderaWorker.kt` runs `main` on a daemon thread). A foreground service would extend, not fix, this. | The node survives the screen being off for an hour with the app backgrounded | OPEN |
| M-3 | One ABI per APK | The build targets `aarch64`; `--target` switches it, but nothing produces a universal APK or an App Bundle. | One artifact installs on arm64 and armv7 devices | OPEN |
| M-4 | The LAN tunnel lane is untested on Android | The worker joins the multicast group on the phone (`Watching 224.0.2.60:4445`), but no test drives a real game through it from a handset. | An unmodified Minecraft client reaches a LAN world through a phone-hosted tunnel | OPEN |
| M-NET-2 | The app-to-worker P2P port path has no physical-phone exit evidence yet | Mobile Settings exposes random/fixed P2P ports; Rust writes the validated values to `nodera-worker.properties`; a context/setup gate starts Kotlin only after the handoff; Java reads `NODERA_P2P_PORT` environment-first/property-second. Control is intentionally excluded so Android's Rust client and Java worker agree on `25610`. `AndroidPortPropertyTest` proves a real worker reports the requested property port in `NODERA-STATE.self_route`; five app tests cover parity, control isolation, replacement and both startup orders. A signed debug APK builds, but no physical phone selected a one-port range and passed the exact state assertion. | The phone binds a P2P port chosen in settings | RETIRING — headless proof green; physical state proof pending |
| M-NET-4 | `minSdk = 24` but the worker is dexed at `--min-api 26` | The two values are now ONE constant: `scripts/android-apk.sh` defines `DEX_MIN_API=26`, dexes with it, patches the generated `minSdk` to it, re-reads the file and `die`s if they still differ — so an APK that installs where the worker cannot run is no longer buildable. What is missing is a run: `gen/` is generated, absent from a fresh checkout, and building it needs the NDK plus a Tauri Android init this session could not perform. | The two values agree, or the APK refuses to install below the dex floor | RETIRING — agreement enforced by construction; one `scripts/android-apk.sh` run to confirm |
