# Frontend — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: Source is jscpd (`build/jscpd/jscpd-report.json`, filtered to `app/`)
     plus manual review of every .rs file in the shell crate, every .kt file under
     app/android/kotlin/, and scripts/android-*.sh (jscpd scans java/rust only, so the shell scripts
     are covered by hand). jscpd flags ZERO duplicated blocks in `app/` and ZERO in
     `app/android/`, so almost every row below is a manual candidate and carries "—" or 0.0 under
     % duplicated. Re-run the jscpd filter before adding rows; promote a row to a numbered task only
     with an owning limitation row. This register is reference material, not a task-status surface. -->

Merged on **2026-08-05** from the former **app** and **mobile** registers; every line count
and duplication percentage below is as it was written there. The desktop rows carry a superseding
audit of **2026-08-01** taken after the shared-core and launcher split; the Kotlin and shell rows
were audited on the same date. Line counts are direct `wc -l` results; `loccount` does not list
Kotlin, and the scripts are in neither tool, so their `% duplicated` is `—`.

Scope: `app/` (excluding `target/`), `library/rust/nodera-core/`, `app/ui/`, `app/android/kotlin/`
and `scripts/android-*.sh`. `web/` is not yet in scope — [task 18](Task.18.md) and
[task 19](Task.19.md) are what give it a shape worth measuring.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---|---|---|
| `library/rust/nodera-core/src/core.rs` | 1245 | — | self, 3× | `NoderaCore` is the shared façade but now also contains full launch orchestration. Extract launch execution into `launch/coordinator.rs` while preserving one public handle. **Added 2026-08-05**: `start_play`'s failure paths repeat `close the tunnel → read `closed_or_absent` → clear `session_id` → publish a failure with a remedy → return` three times. "A launch failure always closes its tunnel" is a rule with three copies; a `fail_and_close(...)` helper states it once. Not taken this round — it sits inside the observation loop where `state` and `tunnel` are moved, and the reduction is not worth doing untested against a live launch. |
| `app/src/lib.rs` | 1121 | — | — | Shell still combines command delegates, plugin registration, tray, setup and generated handler list. Extract command modules without moving shared behavior back out of `nodera-core`. |
| `app/android/kotlin/ui/Settings.kt` | 1077 | 0.0 | — | Highest Kotlin debt: extract each settings destination and a typed settings repository; preserve preview-before-trust in one owner. |
| `library/rust/nodera-core/src/launch/mod.rs` | 856 | — | — | Types, coordinator, cancellation-safe tunnel lease and tests share one file. Split types/coordinator/lease after launch ABI settles; do not split the state transitions across owners. |
| `app/ui/src/components.tsx` | 842 | — | — | Controls, data displays, pagination, dialogs and world art are one primitive catalogue. Split by controls/data/overlays without duplicating token strings. |
| `app/ui/src/TrackerStores.tsx` | 1127 | — | self, 4× | **Added 2026-08-05.** The screen carries its own button family — `PrimaryButton`, `SecondaryButton`, `GhostButton`, `DangerButton` — four components differing only in a class string, over 27 call sites. `components.tsx`'s own `Button` docstring already records that "TrackerStores.tsx carried a seventh set of its own"; that consolidation was never finished. **It cannot be finished by deleting them**: the four carry `--tracker-store-*` custom properties, and `app/ui/tests/tracker-stores-style.test.mjs` asserts each of those declarations reaches the built CSS from a class selector. Collapsing the four into one variant-taking component keeps every declaration and is what landed. Adopting `components.tsx`'s `Button` outright needs that test rewritten in the same change. |
| `app/ui/src/Settings.tsx` | 793 | — | — | Settings orchestration and all basic configuration sections remain coupled. Extract one component per section while retaining a single settings document owner. |
| `scripts/android-apk.sh` | 729 | — | Generated-project patching, Compose dependencies and source guards now dominate; overlaps toolchain/e2e preamble and pins | Commit a conventional Android host or factor generated-project patch functions before adding more source-text guards. |
| `app/android/kotlin/ui/Screens.kt` | 322 | 0.0 | — | Home/Worlds/Activity can split when each gains independent tests. |
| `app/android/kotlin/MainActivity.kt` | 320 | 0.0 | — | Extract destination/back-stack state into a navigator only if another nested hierarchy appears. |
| `app/android/kotlin/NoderaStorage.kt` | 213 | 0.0 | — | None (no clones). |
| `app/android/kotlin/ui/Model.kt` | 193 | 0.0 | — | Move JSON decoding behind a typed repository/ViewModel once instrumentation tests can target it. |
| `app/android/kotlin/NoderaWorker.kt` | 169 | 0.0 | — | None (no clones). |
| `scripts/android-e2e.sh` | 150 | — | `android-apk.sh` (`say`/`die` helpers e2e:53,56 vs apk:64-65; `NODERA_ROOT` e2e:26; `ANDROID_HOME` e2e:27; `PATH` platform-tools e2e:28; `adb install -r` e2e:81 vs apk:357) | Consume the same `android-common.sh` (logging + preamble + `adb install` helper). |
| `scripts/android-toolchain.sh` | 147 | — | `android-apk.sh` (pins `NDK_VERSION`/`BUILD_TOOLS` tc:34-35 vs apk:34-35; logging `log`/`warn`/`die` tc:48-50 vs apk:64-65 / e2e:53,56; `NODERA_ROOT` tc:28; `ANDROID_HOME` tc:37) | Be the source of truth for the version pins that the generated Gradle project must also agree with; consume `android-common.sh`. |
| `app/android/kotlin/ui/Onboarding.kt` | 131 | 0.0 | — | No split needed. |
| `app/android/kotlin/ui/Components.kt` | 98 | 0.0 | — | Shared native status and section primitives; keep small. |
| `app/android/kotlin/ui/Theme.kt` | 63 | 0.0 | — | No split needed. |
| `app/android/kotlin/NoderaBridge.kt` | 24 | 0.0 | — | None (no clones). |
| `app/src/android/{battery,network}.rs` | — | the one clone | each other, 11 lines | The single machine-detected clone anywhere near this category: the JNI `getSystemService(name)` lookup at `battery.rs:183-193` and `network.rs:196-206`, where only the service name string and the error label differ. Extract a shared helper. |

**The one machine-detected clone is now in scope.** The former mobile register listed the
`getSystemService` duplication as *adjacent*: the files live under `app/src/android/` and belonged to
the app category, while mobile [Task 15](Task.15.md) owned `network.rs`. After the 2026-08-05 merge
there is no boundary left to coordinate across, so it is a row rather than a footnote.

## Sequencing

Refactors ordered by payoff × readiness. The two registers were sequenced independently; this is one
order across both, and the reasoning that produced each item is preserved.

1. **Centralise the Android version pins** (`NDK_VERSION`, `BUILD_TOOLS`, `DEX_BUILD_TOOLS`,
   `COMPILE_SDK`) into one sourced file consumed by both `android-apk.sh` and
   `android-toolchain.sh`. The two scripts hand-copy these values and have drifted before — that
   drift was limitation M-9. Lowest-risk, highest-value.
2. **Extract Tauri command modules** while preserving setup order and thin delegates.
3. **Split the native Settings by destination plus a typed repository.** The 1,077-line composable
   file is higher-payoff than the remaining shell cleanup; it must keep preview identity and exact
   numeric round trips.
4. **Split the launch coordinator/types/lease** only after physical launcher acceptance freezes the
   states.
5. **Extract the desktop Settings sections** around one shared document owner.
6. **Split the UI primitives** into controls, data and overlays without changing tokens. The
   `TrackerStores.tsx` button family is the same item seen from the other end: the screen should
   eventually consume `components.tsx`'s `Button`, and the blocker is the style test rather than the
   code.
7. **Extract the ANSI logging helpers** (`say`/`die`/`log`/`warn`/`ok`/`bad`) into
   `scripts/lib/android-common.sh`. The largest repeated block (3 functions × 3 scripts, and the same
   pattern is in `scripts/check-android-bytecode.sh` and `scripts/e2e-android-mesh.sh`). Purely
   mechanical, zero behaviour change.
8. **Source the shared preamble** (`NODERA_ROOT`, `ANDROID_HOME` default, `PATH` platform-tools) from
   the same common library. Repeated verbatim in all three scripts; centralising removes a class of
   drift and lets the pin file from (1) ride the same `source`.
9. **Factor the `adb install -r` reinstall** (with its "keeps the device's peer identity" rationale)
   into a helper in the common library, shared by `android-apk.sh --install` and `android-e2e.sh`.
   Same intent, same comment, duplicated — a single helper documents the `-r` reason once.
10. **Extract the JNI `getSystemService(name)` lookup** in `src/android/{battery,network}.rs` into a
    shared helper.
11. **Re-run jscpd and the line counts** after those moves, and extend the filter to `web/` once
    [task 19](Task.19.md) has decided what shares a tree with what. The current zero-clone result
    predates all of it.

## Completed

| Refactor | Evidence | Completed |
|---|---|---|
| Collapse the `ENFORCEMENT` table in `settings.rs` | `const fn live/local/never` constructors used by 23 rows; the table is 74 lines shorter and every row's intent is now on one line beside its key. The four `Enforcement` variants and `resolve(...)` are unchanged, so `setting_status` reports exactly what it did | 2026-08-05 |
| Collapse the `TrackerStores.tsx` button family | One `StoreButton` with a `variant`; the four former components are its four class strings. Every `--tracker-store-*` declaration `app/ui/tests/tracker-stores-style.test.mjs` asserts on is preserved verbatim | 2026-08-05 |

> Debt remains shape, not copied blocks. This register deliberately does not recommend recombining
> native Compose and desktop React components: shared behaviour belongs in `nodera-core`, not in a
> cross-platform imitation layer. [Task 19](Task.19.md) is bound by the same rule — it unifies the
> React trees, not the Android one.
