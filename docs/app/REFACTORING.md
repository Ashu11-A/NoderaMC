# App — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: Source is jscpd (`build/jscpd/jscpd-report.json`, filtered to
     app/) plus manual review of every .rs file in the crate. jscpd flags ZERO duplicated
     blocks here, so every row below is a manual candidate and carries "—" under % duplicated. Re-run
     the jscpd filter before adding rows; promote a row to a numbered task only with an owning
     limitation row. Scope: app/ excluding target/ and the android/ Kotlin/manifest
     assets (the .rs under src/android/ IS in scope). Audited 2026-07-28. -->

Superseding audit **2026-08-01** after the shared-core and launcher split. The prior jscpd result
remains zero duplicated blocks; rows below track shape debt created or exposed by the new boundaries.
Line counts are direct `wc -l` results.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---|---|---|
| `app/src/lib.rs` | 1121 | — | — | Shell still combines command delegates, plugin registration, tray, setup and generated handler list. Extract command modules without moving shared behavior back out of `nodera-core`. |
| `library/rust/nodera-core/src/core.rs` | 1245 | — | — | `NoderaCore` is the shared façade but now also contains full launch orchestration. Extract launch execution into `launch/coordinator.rs` while preserving one public handle. |
| `library/rust/nodera-core/src/launch/mod.rs` | 856 | — | — | Types, coordinator, cancellation-safe tunnel lease and tests share one file. Split types/coordinator/lease after launch ABI settles; do not split the state transitions across owners. |
| `app/ui/src/components.tsx` | 842 | — | — | Controls, data displays, pagination, dialogs and world art are one primitive catalogue. Split by controls/data/overlays without duplicating token strings. |
| `app/ui/src/Settings.tsx` | 793 | — | — | Settings orchestration and all basic configuration sections remain coupled. Extract one component per section while retaining a single settings document owner. |

## Sequencing

1. **Extract Tauri command modules** while preserving setup order and thin delegates.
2. **Split launch coordinator/types/lease** only after physical launcher acceptance freezes states.
3. **Extract desktop Settings sections** around one shared document owner.
4. **Split UI primitives** into controls, data and overlays without changing tokens.
5. **Re-run jscpd and line counts** after those moves; current zero-clone result predates them.

> Debt remains shape, not copied blocks. This register deliberately does not recommend recombining
> native Compose and desktop React components: shared behavior belongs in `nodera-core`, not in a
> cross-platform imitation layer.
