# Mobile — Refactoring Register

Source: `build/jscpd/jscpd-report.json` (filtered to `rust/nodera-app/android/`) + manual review of
`scripts/android-*.sh` (jscpd scans java/rust only, so the shell scripts are covered by hand).
Dated **2026-07-28**. Scope: the mobile category's read-only modules —
`rust/nodera-app/android/` (4 Kotlin files) and `scripts/android-*.sh` (3 scripts).

jscpd reports **0 duplicate clones** in `rust/nodera-app/android/` — the Kotlin module is clean.
loccount does not list Kotlin; line counts below are from `wc -l`. The scripts are neither in jscpd
nor in loccount, so their `% duplicated` is `—` (manual candidates). The actionable duplication is
all in the scripts: repeated logging helpers, hand-copied version pins (already drifted — this is
[M-9](LIMITATIONS.md)), and a shared preamble.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---|---|---|---|
| `rust/nodera-app/android/kotlin/MainActivity.kt` | 63 | 0.0 | — | None (no clones). |
| `rust/nodera-app/android/kotlin/NoderaBridge.kt` | 24 | 0.0 | — | None (no clones). |
| `rust/nodera-app/android/kotlin/NoderaStorage.kt` | 213 | 0.0 | — | None (no clones). |
| `rust/nodera-app/android/kotlin/NoderaWorker.kt` | 169 | 0.0 | — | None (no clones). |
| `scripts/android-apk.sh` | 359 | — | `android-toolchain.sh` (pins `NDK_VERSION`/`BUILD_TOOLS` at apk:34-35 vs tc:34-35; apk's own comment says "Pinned to match"), logging `say`/`die` (apk:64-65 vs tc:48,50 / e2e:53,56), `NODERA_ROOT` (apk:28 vs tc:28 / e2e:26), `ANDROID_HOME` default (apk:40 vs tc:37 / e2e:27); `adb install -r` identity-preserving reinstall (apk:357 vs e2e:81, same comment) | Source the pins from one `scripts/lib/android-versions.sh`; move logging + preamble into `scripts/lib/android-common.sh`; extract the `adb install -r` helper. Eliminates M-9. |
| `scripts/android-e2e.sh` | 150 | — | `android-apk.sh` (`say`/`die` helpers e2e:53,56 vs apk:64-65; `NODERA_ROOT` e2e:26; `ANDROID_HOME` e2e:27; `PATH` platform-tools e2e:28; `adb install -r` e2e:81 vs apk:357) | Consume the same `android-common.sh` (logging + preamble + `adb install` helper). |
| `scripts/android-toolchain.sh` | 147 | — | `android-apk.sh` (pins `NDK_VERSION`/`BUILD_TOOLS` tc:34-35 vs apk:34-35 — the two have drifted on the compile platform, M-9; logging `log`/`warn`/`die` tc:48-50 vs apk:64-65 / e2e:53,56; `NODERA_ROOT` tc:28; `ANDROID_HOME` tc:37) | Be the source of truth for the version pins that the generated Gradle project must also agree with; consume `android-common.sh`. |

**Adjacent (out of strict scope, noted for coordination).** The one jscpd clone near this category's
code is the 11-line JNI `getSystemService(name)` lookup duplicated between
`rust/nodera-app/src/android/battery.rs:183-193` and
`rust/nodera-app/src/android/network.rs:196-206` (only the service name string and error label
differ). `network.rs` is owned by mobile [Task.4](Task.4.md), but the file lives in the `app`
category; refactor in coordination with `docs/app/`. Not counted in the table because it is outside
this register's module set.

## Sequencing

The top five refactors, ordered by payoff × readiness:

1. **Centralise the version pins** (`NDK_VERSION`, `BUILD_TOOLS`, `DEX_BUILD_TOOLS`, `COMPILE_SDK`)
   into one sourced file consumed by both `android-apk.sh` and `android-toolchain.sh`. **This is the
   elimination path for [M-9](LIMITATIONS.md)** — the two scripts already hand-copy these values and
   have drifted (generated project `compileSdk = 36` while the toolchain installs platform 34).
   Lowest-risk, highest-value: do it first.
2. **Extract the ANSI logging helpers** (`say`/`die`/`log`/`warn`/`ok`/`bad`) into
   `scripts/lib/android-common.sh`. The largest repeated block (3 functions × 3 scripts, and the same
   pattern is in `scripts/check-android-bytecode.sh` and `scripts/e2e-android-mesh.sh`). Purely
   mechanical, zero behaviour change.
3. **Source the shared preamble** (`NODERA_ROOT`, `ANDROID_HOME` default, `PATH` platform-tools) from
   the same common library. Repeated verbatim in all three scripts; centralising removes a class of
   drift and lets the pin file from (1) ride the same `source`.
4. **Factor the `adb install -r` reinstall** (with its "keeps the device's peer identity" rationale)
   into a helper in the common library, shared by `android-apk.sh --install` and `android-e2e.sh`.
   Same intent, same comment, duplicated — a single helper documents the `-r` reason once.
5. **(Adjacent, app-category)** Extract the JNI `getSystemService(name)` lookup in
   `src/android/{battery,network}.rs` into a shared helper. The only machine-detected clone near this
   category; coordinate with `docs/app/` since the files live there even though mobile Task.4 owns
   `network.rs`.
