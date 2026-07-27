# Mobile Task 1 — The Android build, and the worker inside it

<!-- AI-AGENT-INSTRUCTION: This file records HOW the APK is produced and why each pin exists. If a
     pin moves, the reason must move with it — the two version pins here were each the direct cause
     of a day's confusion. -->

**Status:** ✅ COMPLETED
**Category:** mobile · **Owns:** `scripts/android-apk.sh`, `rust/nodera-app/android/kotlin/`
**Last audit:** 2026-07-26
**Depends on:** [app 1](../app/Task.1.md), [worker 1](../worker/Task.1.md)

---

## Goal

One command produces a signed APK that contains the real peer worker, and one command proves the
installed result is a node other people can find.

## The pipeline

```text
  ./gradlew :worker:installDist          the worker and its dependency closure
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

At runtime `MainActivity.onCreate` calls `NoderaWorker.start`, which stages the jar to internal
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

## Commands

```bash
scripts/android-apk.sh                 # signed APK into ./build
scripts/android-apk.sh --install       # …and adb install it
scripts/android-apk.sh --skip-worker   # UI-only rebuild
scripts/android-e2e.sh                 # install, launch, and prove it is on the network
```

The signing key is a **development** key at `~/.nodera/android-release.jks`, generated on first run.
It exists so the APK installs, not so it can be published.
