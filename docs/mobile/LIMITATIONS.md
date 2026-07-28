# Mobile — Open Limitations

<!-- AI-AGENT-INSTRUCTION: One row per gap that is real TODAY. A row leaves only when its exit test
     passes; move it to LIMITATIONS.fixed.md with the evidence. Never delete a row silently. -->

**Category:** mobile · **Last audit:** 2026-07-27

| id | Limitation | Why it is not closed | Exit test |
|---|---|---|---|
| M-1 | An arbitrary shared-storage folder may be picked but unusable | Android 11+ withholds raw `File` access to shared storage regardless of the SAF grant, and the worker writes with `java.io.File`. The app detects and reports this; it cannot remove it without a storage layer that speaks `content://`. | A folder outside app-specific storage is picked and the worker writes a world archive into it |
| M-2 | The node stops when Android stops the app | The worker is a thread in this process because an app may not spawn a VM. A foreground service would extend, not fix, this. | The node survives the screen being off for an hour with the app backgrounded |
| M-3 | One ABI per APK | The build targets `aarch64`; `--target` switches it, but nothing produces a universal APK or an App Bundle. | One artifact installs on arm64 and armv7 devices |
| M-5 | Java 21 type-pattern switches must never re-enter the worker's closure | ART has no `SwitchBootstraps` and D8 cannot desugar it, so any new `case Type v ->` in shared code is a latent crash on Android only. Guarded by a script, not by the compiler | `scripts/check-android-bytecode.sh` runs in CI |
| M-4 | The LAN tunnel lane is untested on Android | The worker joins the multicast group on the phone (`Watching 224.0.2.60:4445`), but no test drives a real game through it from a handset. | An unmodified Minecraft client reaches a LAN world through a phone-hosted tunnel |
| M-NET-1 | The synchronised services list is read once, at worker boot | `SyncedServices.load` runs at `HeadlessPeerMain:103` and nowhere else; on Android the worker restarts only with the app process, so a tracker store added now takes effect next launch. | A store added in the app changes which tracker the worker dials, without restarting the app |
| M-NET-2 | `NODERA_CONTROL_PORT` and `NODERA_P2P_PORT` cannot be set on Android | `envInt` reads `System.getenv` only, while every other reader falls back to `System.getProperty` — and properties are the only channel Android has. Both ports are pinned to their compiled-in defaults. | The phone binds a P2P port chosen in settings |
| M-NET-3 | The Restart button is a silent no-op on a phone | `restart_worker` notifies a signal whose only consumer, `daemon::supervise`, is `#[cfg(desktop)]`. Every `RESTART_REQUIRED_KEYS` setting is therefore unreachable on Android. | Either those settings apply live on Android, or they are not offered there |
| M-NET-4 | `minSdk = 24` but the worker is dexed at `--min-api 26` | `gen/android/app/build.gradle.kts:22` and `scripts/android-apk.sh:156` disagree, and the script's own comment claims they match. On API 24–25 the APK installs and the worker fails at runtime. | The two values agree, or the APK refuses to install below the dex floor |
