# Mobile — Open Limitations

<!-- AI-AGENT-INSTRUCTION: One row per gap that is real TODAY. A row leaves only when its exit test
     passes; move it to LIMITATIONS.fixed.md with the evidence. Never delete a row silently. -->

**Category:** mobile · **Last audit:** 2026-08-04 · Open rows: **3** · Retiring rows: **1**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

| id | Limitation | Why it is not closed | Exit test | Status |
|---|---|---|---|---|
| M-1 | An arbitrary shared-storage folder may be picked but unusable | Android 11+ withholds raw `File` access to shared storage regardless of the SAF grant, and the worker writes with `java.io.File`. The app detects and reports this (`NoderaStorage.kt:171-191` probes with a real write); it cannot remove it without a storage layer that speaks `content://`. | A folder outside app-specific storage is picked and the worker writes a world archive into it | OPEN |
| M-3 | One ABI per APK | The build targets `aarch64`; `--target` switches it, but nothing produces a universal APK or an App Bundle. | One artifact installs on arm64 and armv7 devices | OPEN |
| M-4 | The LAN tunnel lane is untested on Android | The worker joins the multicast group on the phone (`Watching 224.0.2.60:4445`), but no test drives a real game through it from a handset. | An unmodified Minecraft client reaches a LAN world through a phone-hosted tunnel | OPEN |
| M-NET-2 | The app-to-worker P2P port path has no physical-phone exit evidence yet | Mobile Settings exposes random/fixed P2P ports; Rust writes the validated values to `nodera-worker.properties`; a context/setup gate starts Kotlin only after the handoff; Java reads `NODERA_P2P_PORT` environment-first/property-second. Control is intentionally excluded so Android's Rust client and Java worker agree on `25610`. `AndroidPortPropertyTest` proves a real worker reports the requested property port in `NODERA-STATE.self_route`; five app tests cover parity, control isolation, replacement and both startup orders. A signed debug APK builds, but no physical phone selected a one-port range and passed the exact state assertion. | The phone binds a P2P port chosen in settings | RETIRING — headless proof green; physical state proof pending |
