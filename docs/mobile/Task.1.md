# Mobile Task 1 — The Android build, and the worker inside it

<!-- AI-AGENT-INSTRUCTION: This file records HOW the APK is produced and why each pin exists. If a
     pin moves, the reason must move with it — the two version pins here were each the direct cause
     of a day's confusion. The build pins (NDK_VERSION, BUILD_TOOLS, DEX_BUILD_TOOLS) live in this
     script and in scripts/android-toolchain.sh; a drift between the two is limitation M-9. -->

**Status:** ✅ COMPLETED
**Category:** mobile · **Owns:** `scripts/android-apk.sh`, `app/android/kotlin/`
**Last audit:** 2026-08-01
**Depends on:** [app 1](../app/Task.1.md), [worker 1](../peer/Task.1.md)

---

## Goal

One command produces a signed APK that contains the real peer worker, and one command proves the
installed result is a node other people can find.

## Status detail

Done. The pipeline below is live; `scripts/android-apk.sh` produces a signed
`build/nodera-release.apk` whose `assets/nodera-worker.jar` is the dexed `:worker` closure, and
`scripts/android-e2e.sh` proves the installed app is a node the tracker returns to an independent
querier. The two pins (JDK 21, build-tools 35 for `d8`) are enforced by the script.

Physical re-audit on 2026-08-01 installed the 201 MiB debug APK on Android 15. The worker persisted
its existing identity, bound `10.0.0.104:39957`, and passed all five E2E checks. The runner now reads
identity and tracker state from `NODERA-STATE` instead of depending on log phrases removed from the
worker, then asks the tracker independently for that exact worker id.

The former SDK provisioning drift, **M-9**, is retired: toolchain and generated project both target
SDK 36 and install build-tools 35 for worker dexing.

## The pipeline

```text
  ./gradlew :peer:installDist          the worker and its dependency closure
        │
        ├─ d8 (build-tools 35) ─────────► classes.dex … classes5.dex
        ├─ jar resources, minus foreign natives
        │                                (VERSION, META-INF/services/… must survive)
        └─ zip ─────────────────────────► assets/nodera-worker.jar   (11 MB)
                                                   │
   cargo tauri android build ──────────────────────┤
        │                                         │
        └─ libnodera_app_lib.so (arm64) ──────────┤
                                                   ▼
                          zipalign → apksigner → build/nodera-release.apk
```

At runtime `MainActivity.onCreate` starts the Rust core; its two-signal gate calls
`NoderaWorker.start` after context and persisted settings are ready. Kotlin stages the jar to internal
storage, marks it **read-only** (API 29+ refuses to load a writable dex), loads it with a
`DexClassLoader`, and calls `main` on a background thread. The worker binds `127.0.0.1:25610` and
the Rust side connects to it exactly as on desktop.

## The two pins, and what each one cost

| Pin | Symptom when wrong |
|---|---|
| **JDK 21** for Gradle | `BUG! exception in phase 'semantic analysis' … Unsupported class file major version 69` |
| **build-tools 35** for `d8` | `Unsupported class file major version 65` — misread for months as "ART cannot run Java 21 pattern switches" |

## Things that are not obvious and are load-bearing

* **`d8` emits classes only.** The jar's resources must be merged back in by hand or the worker dies
  on a missing `VERSION` and finds no SLF4J provider.
* **Foreign natives are stripped.** `librocksdbjni-*.so`, `libzstd-jni-*.so` for desktop ABIs are
  60 MB of an APK that cannot load them; the worker reaches `online` without them.
* **Environment variables cannot be set for one's own process**, so the worker reads its `NODERA_*`
  settings from a system property when the environment has none. That is what lets the app configure
  the worker it hosts.
* **`gen/` is disposable.** Tauri regenerates it, so the build script copies the Kotlin in, injects
  the battery permission, the `documentfile` dependency and the R8 keep rules on every run.

## Files

| Path | Role |
|---|---|
| `scripts/android-apk.sh` | build → dex → align → sign → install |
| `scripts/android-toolchain.sh` | one-time SDK/NDK/target provision |
| `app/android/kotlin/NoderaWorker.kt` | stages + loads the dex worker in-process |
| `app/android/kotlin/MainActivity.kt` | native Compose host that starts the shared core before drawing |
| `app/gen/android/app/build.gradle.kts` | generated Gradle project (compileSdk 36, minSdk 26) |

## Commands

```bash
scripts/android-apk.sh                 # signed APK into ./build
scripts/android-apk.sh --install       # …and adb install it
scripts/android-apk.sh --skip-worker   # UI-only rebuild
scripts/android-e2e.sh                 # install, launch, and prove it is on the network
```

The signing key is a **development** key at `~/.nodera/android-release.jks`, generated on first run.
It exists so the APK installs, not so it can be published.

## Testing

`scripts/android-e2e.sh` — five checks from both ends (install, process alive, worker identity and
tracker reachability from `NODERA-STATE`, independent `nodera-query` newly returns the exact phone
UUID after a successful clean baseline). See
[`TESTING.md`](TESTING.md) §4.1 for the last-run result.

## Limitations

Owns **M-9** (compileSdk/platform drift). See [`LIMITATIONS.md`](LIMITATIONS.md).
