# Mobile — Open Limitations

<!-- AI-AGENT-INSTRUCTION: One row per gap that is real TODAY. A row leaves only when its exit test
     passes; move it to LIMITATIONS.fixed.md with the evidence. Never delete a row silently. -->

**Category:** mobile · **Last audit:** 2026-07-26

| id | Limitation | Why it is not closed | Exit test |
|---|---|---|---|
| M-1 | An arbitrary shared-storage folder may be picked but unusable | Android 11+ withholds raw `File` access to shared storage regardless of the SAF grant, and the worker writes with `java.io.File`. The app detects and reports this; it cannot remove it without a storage layer that speaks `content://`. | A folder outside app-specific storage is picked and the worker writes a world archive into it |
| M-2 | The node stops when Android stops the app | The worker is a thread in this process because an app may not spawn a VM. A foreground service would extend, not fix, this. | The node survives the screen being off for an hour with the app backgrounded |
| M-3 | One ABI per APK | The build targets `aarch64`; `--target` switches it, but nothing produces a universal APK or an App Bundle. | One artifact installs on arm64 and armv7 devices |
| M-5 | Java 21 type-pattern switches must never re-enter the worker's closure | ART has no `SwitchBootstraps` and D8 cannot desugar it, so any new `case Type v ->` in shared code is a latent crash on Android only. Guarded by a script, not by the compiler | `scripts/check-android-bytecode.sh` runs in CI |
| M-4 | The LAN tunnel lane is untested on Android | The worker joins the multicast group on the phone (`Watching 224.0.2.60:4445`), but no test drives a real game through it from a handset. | An unmodified Minecraft client reaches a LAN world through a phone-hosted tunnel |
