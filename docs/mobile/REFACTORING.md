# Mobile — Refactoring Register

Source: `build/jscpd/jscpd-report.json` (filtered to `app/android/`) + manual review of
`scripts/android-*.sh` (jscpd scans java/rust only, so the shell scripts are covered by hand).
Dated **2026-08-01**. Scope: `app/android/kotlin/` (12 Kotlin files, including 7 UI files) and
`scripts/android-*.sh`.

jscpd reports **0 duplicate clones** in `app/android/` — the Kotlin module is clean.
loccount does not list Kotlin; line counts below are from `wc -l`. The scripts are neither in jscpd
nor in loccount, so their `% duplicated` is `—` (manual candidates). The actionable duplication is
all in the scripts: repeated logging helpers, hand-copied version pins (already drifted — this is
[M-9](LIMITATIONS.md)), and a shared preamble.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---|---|---|---|
| `app/android/kotlin/MainActivity.kt` | 320 | 0.0 | — | Extract destination/back-stack state into a navigator only if another nested hierarchy appears. |
| `app/android/kotlin/ui/Components.kt` | 98 | 0.0 | — | Shared native status and section primitives; keep small. |
| `app/android/kotlin/ui/Model.kt` | 193 | 0.0 | — | Move JSON decoding behind typed repository/ViewModel once instrumentation tests can target it. |
| `app/android/kotlin/ui/Onboarding.kt` | 131 | 0.0 | — | No split needed. |
| `app/android/kotlin/ui/Screens.kt` | 322 | 0.0 | — | Home/Worlds/Activity can split when each gains independent tests. |
| `app/android/kotlin/ui/Settings.kt` | 1077 | 0.0 | — | Highest Kotlin debt: extract each settings destination and a typed settings repository; preserve preview-before-trust in one owner. |
| `app/android/kotlin/ui/Theme.kt` | 63 | 0.0 | — | No split needed. |
| `app/android/kotlin/NoderaBridge.kt` | 24 | 0.0 | — | None (no clones). |
| `app/android/kotlin/NoderaStorage.kt` | 213 | 0.0 | — | None (no clones). |
| `app/android/kotlin/NoderaWorker.kt` | 169 | 0.0 | — | None (no clones). |
| `scripts/android-apk.sh` | 729 | — | Generated-project patching, Compose dependencies and source guards now dominate; overlaps toolchain/e2e preamble and pins | Commit a conventional Android host or factor generated-project patch functions before adding more source-text guards. |
| `scripts/android-e2e.sh` | 150 | — | `android-apk.sh` (`say`/`die` helpers e2e:53,56 vs apk:64-65; `NODERA_ROOT` e2e:26; `ANDROID_HOME` e2e:27; `PATH` platform-tools e2e:28; `adb install -r` e2e:81 vs apk:357) | Consume the same `android-common.sh` (logging + preamble + `adb install` helper). |
| `scripts/android-toolchain.sh` | 147 | — | `android-apk.sh` (pins `NDK_VERSION`/`BUILD_TOOLS` tc:34-35 vs apk:34-35 — the two have drifted on the compile platform, M-9; logging `log`/`warn`/`die` tc:48-50 vs apk:64-65 / e2e:53,56; `NODERA_ROOT` tc:28; `ANDROID_HOME` tc:37) | Be the source of truth for the version pins that the generated Gradle project must also agree with; consume `android-common.sh`. |

**Adjacent (out of strict scope, noted for coordination).** The one jscpd clone near this category's
code is the 11-line JNI `getSystemService(name)` lookup duplicated between
`app/src/android/battery.rs:183-193` and
`app/src/android/network.rs:196-206` (only the service name string and error label
differ). `network.rs` is owned by mobile [Task.4](Task.4.md), but the file lives in the `app`
category; refactor in coordination with `docs/app/`. Not counted in the table because it is outside
this register's module set.

## Sequencing

Refactors ordered by payoff × readiness:

1. **Split native Settings by destination plus typed repository.** Current 1,077-line composable file
   is now higher-payoff than shell cleanup; it must keep preview identity and exact numeric round trips.
2. **Centralise the version pins** (`NDK_VERSION`, `BUILD_TOOLS`, `DEX_BUILD_TOOLS`, `COMPILE_SDK`)
   into one sourced file consumed by both `android-apk.sh` and `android-toolchain.sh`. **This is the
   elimination path for [M-9](LIMITATIONS.md)** — the two scripts already hand-copy these values and
   have drifted (generated project `compileSdk = 36` while the toolchain installs platform 34).
   Lowest-risk, highest-value: do it first.
3. **Extract the ANSI logging helpers** (`say`/`die`/`log`/`warn`/`ok`/`bad`) into
   `scripts/lib/android-common.sh`. The largest repeated block (3 functions × 3 scripts, and the same
   pattern is in `scripts/check-android-bytecode.sh` and `scripts/e2e-android-mesh.sh`). Purely
   mechanical, zero behaviour change.
4. **Source the shared preamble** (`NODERA_ROOT`, `ANDROID_HOME` default, `PATH` platform-tools) from
   the same common library. Repeated verbatim in all three scripts; centralising removes a class of
   drift and lets the pin file from (1) ride the same `source`.
5. **Factor the `adb install -r` reinstall** (with its "keeps the device's peer identity" rationale)
   into a helper in the common library, shared by `android-apk.sh --install` and `android-e2e.sh`.
   Same intent, same comment, duplicated — a single helper documents the `-r` reason once.
6. **(Adjacent, app-category)** Extract the JNI `getSystemService(name)` lookup in
   `src/android/{battery,network}.rs` into a shared helper. The only machine-detected clone near this
   category; coordinate with `docs/app/` since the files live there even though mobile Task.4 owns
   `network.rs`.
