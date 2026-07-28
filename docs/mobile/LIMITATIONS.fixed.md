# Mobile — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: Append only, newest first. Every row names the evidence that retired it.
     A row here must never reappear in LIMITATIONS.md without a new id. -->

**Category:** mobile · **Last audit:** 2026-07-28

No limitations retired in the 2026-07-28 sweep — every open M-* row was re-confirmed still live in
code (see [`LIMITATIONS.md`](LIMITATIONS.md)). New rows land below as their exit tests go green.

| id | Was | Retired by | Evidence |
|---|---|---|---|
| M-8 | The worker booted on Android but died the moment it encoded its first peer-to-peer message, taking the app with it | Java 21 **type-pattern switches** compile to an `invokedynamic` on `java.lang.runtime.SwitchBootstraps`. ART does not implement it (`BootstrapMethodError` → `ClassCastException`) and D8 cannot desugar it (below API 26 it substitutes a stub that throws "Instruction is unrepresentable in DEX V35"). Every such switch in the worker's closure is now an `instanceof` chain | Phone `sent` 0 → 465 on the first encode, then a full session: phone `received=21534 peers=1`, Linux peer listing `0f0b34b4-… 10.0.0.104:25620`; `scripts/check-android-bytecode.sh` reports 0 invoke-custom sites |
| M-0 | "The Java worker cannot run on Android: `SwitchBootstraps.typeSwitch` is absent from ART and D8 cannot desugar it; rocksdbjni/zstd-jni ship desktop natives." | Measuring instead of asserting. The blocker was the **D8 version** (build-tools 34's R8 8.2 refuses Java 21 class files); build-tools 35 dexes the whole closure. The native libraries are never loaded on the worker's path. The one real incompatibility — virtual threads that exist and throw — is handled by `Threads`. | `NODERA-OK 2 0.1.0` and a full `NODERA-STATE` from `127.0.0.1:25610` on the device; `ThreadsTest` |
| M-5 | The app crashed a few seconds after launch | `fresh_seed()` read `/dev/urandom` with `fs::read`, which reads to EOF | Native heap 2.5 GB → 14 MB; `a_fresh_seed_is_bounded_and_unpredictable` |
| M-6 | The first-run setup reappeared on every launch | Settings resolved to `/` on Android, so nothing persisted | `dirs_config()` Android arm + `SettingsHandle::reload()`; setup asked once across force-stops |
| M-7 | System back closed the app instead of going up | `TauriActivity` sets `handleBackNavigation = false` | `MainActivity` overrides it; back from Settings › Appearance returns to the list with the app still running |
