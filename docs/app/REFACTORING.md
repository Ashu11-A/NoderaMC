# App — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: Source is jscpd (`build/jscpd/jscpd-report.json`, filtered to
     rust/nodera-app/) plus manual review of every .rs file in the crate. jscpd flags ZERO duplicated
     blocks here, so every row below is a manual candidate and carries "—" under % duplicated. Re-run
     the jscpd filter before adding rows; promote a row to a numbered task only with an owning
     limitation row. Scope: rust/nodera-app/ excluding target/ and the android/ Kotlin/manifest
     assets (the .rs under src/android/ IS in scope). Audited 2026-07-28. -->

Source: `build/jscpd/jscpd-report.json` (filtered to `rust/nodera-app/`) + manual review of all 30
`.rs` files. **jscpd flags zero duplicated blocks in this crate**, so the table is manual candidates
only. Line counts from `build/loccount.txt`. Audited 2026-07-28.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---|---|---|
| `rust/nodera-app/src/lib.rs` | 1135 | — | — | The `run()` function (≈360 lines, lines 773–1089) is the crate's god-function: plugin registration, state construction, the 50-entry `invoke_handler!`, the `setup` closure that spawns ~8 background tasks, and `build_tray`. Extract one `bootstrap` module: `register_plugins`, `build_state`, `register_commands`, and a `spawn_workers` set, each naming one task the setup closure currently inlines. Makes the task graph readable and the lifecycle unit-testable without a window. |
| `rust/nodera-app/src/settings.rs` | 1337 | — | — | Two unrelated responsibilities in one file: the settings **document** (`Settings`, validation, defaults) and the **enforcement resolver** (`ENFORCEMENT` table, `setting_status`, `resolve`, `WorkerReport`). Move the enforcement half into `enforcement.rs`; move `SettingsHandle`'s damaged-file / Android-race persistence into `state.rs`. The 24 tests split along the same seam. Shrinks the largest file and isolates the invariant the badges depend on. |
| `rust/nodera-app/src/api/model.rs` | 829 | — | — | `World::of`, `Peer::of`, `Endpoint::of` are the same shape — lift each wire row field-for-field into a view struct, mapping the handful of "unknown" sentinels. jscpd does not flag them because the field names differ, but the repetition is real. A single `From<&WireRow> for View` per type (or one small macro) removes it without hiding the sentinel rules the tests pin. |
| `rust/nodera-app/src/api/link.rs` | 648 | — | — | `stream_once` (≈80 lines, nested `match`) and `poll_until_gone` share the accept→publish→edge shape. Extract a `sample_and_publish(metrics, transport, first, on_reconnect)` helper so the two paths differ only in how they obtain a sample, not in how they treat one. |
| `rust/nodera-app/src/android/battery.rs` | 465 | — | — | Mixes three concerns: JNI platform glue (`platform` mod), the JNI bridge entry point (`Java_dev_nodera_app_NoderaBridge_initialise`), and the help-URL/policy logic the UI reads. Move the non-JNI half (`BatteryPolicy`, `help_url`, the desktop stubs) into `android/battery_policy.rs` so the readable, fully-testable-on-desktop part is not buried under `extern "system"` and `with_context`. |

## Sequencing

1. **`lib.rs::run` extraction** — the god-function. Highest payoff: every later change to the task
   graph currently edits a 360-line closure, and the setup order is load-bearing (the deep-link
   handler must register before the window; the sync-file writer before the pusher; the link before
   the sampler). Named functions make that order a comment you can read.
2. **`settings.rs` split** — the largest file and the one with the clearest internal seam. The
   enforcement resolver is pure and table-driven; pulling it out lets the badge logic be reasoned
   about (and reviewed) without the file-persistence context.
3. **`api::model` wire→view conversions** — small, mechanical, low-risk, and it removes the one
   place a new wire field can silently fail to surface on screen (forget the mapping and the field is
   dropped). Do it after the model is otherwise stable (Task 10's content pass).
4. **`android/battery` split** — improves testability of the non-JNI half on desktop, where the JNI
   half cannot run at all. Independent of the first three.
5. **`api::link` helper** — moderate payoff, lowest risk; do last, once the link's behaviour is not
   actively changing.

> jscpd's zero is a real result, not a misconfiguration: the crate's modules are structurally
> distinct (each owns one verb or one data path), and the near-duplicate `::of` conversions in
> `api::model` diverge enough per-field that the token matcher does not cluster them. The debt here
> is shape (god-function, dual-responsibility files), not copied code.
