# Task 7 — Tauri Companion App (module: `rust/nodera-app`)

**Module:** the desktop application (Tauri: Rust backend + React frontend) that supervises the
peer worker and gives players an always-on dashboard ·
**Depends on:** Task 6 (the worker it supervises + the control endpoint it reads), Task 2
(2a `nodera-codec` wire types where needed) ·
**Consumed by:** players (install target); Task 5's gate (5g) assumes the app keeps the worker
running.

## Implementation status

Audited **2026-07-21**. ✅ completed · 🚧 pending · ⏳ waiting.

| Phase | Deliverable | Status | Waiting on |
|---|---|---|---|
| 7a | Tauri scaffold: window + system tray + autostart + single-instance + worker supervisor (`daemon.rs`, attach-aware) + React dashboard shell | ✅ scaffold (workspace-EXCLUDED from the `cargo test` gate — Tauri native deps; built separately / `scripts/dev.sh --with-app`) | — |
| 7b | Live metrics: `control.rs fetch_state` polls `NODERA-STATE` each second and pumps the real snapshot (bytes/peers/worlds/world) to the dashboard | ✅ (landed with legacy Task 33) | — |
| 7c | Per-OS packaging: installers, app icons, autostart acceptance on Windows/macOS/Linux, workspace/CI integration | 🚧 | — |
| 7d | Automated end-to-end acceptance: installer + gate both ways + cross-machine continuity (host closes Minecraft, world stays joinable) — the L-47 exit | ⏳ | 6c (worker seeding/announce), 5a (client harness for the joining side) |

## Goal

Make the always-on node a product: players install the Nodera companion from
`https://github.com/Ashu11-A/NoderaMC`; it launches at login, sits in the system tray, runs and
supervises the bundled headless Java peer (Task 6 — Option B locked), and shows a live
dashboard — **chunks/data this node maintains for the network, GB sent/received, the peers
currently exchanging data, and the world(s) this node hosts or is connected to**. The mod
refuses to start without the worker (5g), so the app is the thing every player actually runs;
it must be boring, small, and reliable.

## Context (last audit: 2026-07-21)

- Landed (legacy Tasks 32/33): the `rust/nodera-app` scaffold — `main.rs` (tray + window +
  single-instance + autostart registration), `daemon.rs` (supervises the worker; **attach
  mode** reuses an externally-started worker, which is how `scripts/dev.sh --with-app` runs
  it), `control.rs` (probes the control endpoint; `fetch_state` parses the `STATE` JSON via
  `serde_json`), `metrics.rs` (the `Metrics` struct the React `App.tsx` renders), `tray.rs`;
  React/Vite UI under `ui/` with the four dashboard panels. The metrics pump emits
  `nodera://metrics` each second with real worker data plus `daemon_up` from the probe.
- The crate is **workspace-excluded** (Tauri's native webkit deps would break the headless
  `cargo test` gate); it builds separately. This exclusion is deliberate and stays until 7c
  wires a CI job that can build it.
- The control protocol mirror lives in `control.rs` and must stay in lockstep with
  `ControlProtocol` (Task 6) and the mod's `CompanionProtocol` — version 2 today; the 5g gate
  classifies skew.
- The app is a supervisor + UI, **not** a peer: all network behaviour lives in the worker
  (Java) and the Rust service binaries (Tasks 3/4). The app holds no signing keys and serves
  nothing to the network.

## Folder structure (monorepo default)

```
rust/nodera-app/
├── Cargo.toml            tauri, tauri-plugin-autostart, tokio, serde_json; workspace-excluded
├── tauri.conf.json       tray, autostart, single-instance, window config
├── src/
│   ├── main.rs           tray + window + single-instance + autostart + metrics pump
│   ├── daemon.rs         worker supervisor (spawn bundled JVM | attach to external worker)
│   ├── control.rs        loopback control client: probe + fetch_state (NODERA-STATE)
│   ├── metrics.rs        Metrics struct → UI (chunks, GB ↑/↓, peers, current world)
│   └── tray.rs           status icon, quick actions (open window, pause seeding, quit)
└── ui/                   React + Vite
    ├── src/App.tsx       dashboard: maintained-chunks, GB↑/↓, peer list, current world
    ├── src/panels/{Chunks,Bandwidth,Peers,World}.tsx
    └── src/ipc.ts        tauri invoke/event bridge
```

## Related files

- `rust/nodera-app/**` (the module), `scripts/dev.sh` (`--with-app` build+launch in attach
  mode)
- Worker side it reads: `java/nodera-headless/**`, `java/peer-runtime/.../control/ControlProtocol.java` (Task 6)
- Gate that depends on the app keeping the worker alive: `java/neoforge-mod/.../common/CompanionGate.java` (5g)
- Legacy specs: [`old/Task.32.md`](old/Task.32.md) (32a app, 32d integration analysis),
  [`old/Task.33.md`](old/Task.33.md) (live metrics pump)

## Implementation details (phases)

- **7a — Scaffold.** ✅ Full spec: [`old/Task.32.md`](old/Task.32.md) §32a. Tauri 2.x; tray
  with status + menu (Open dashboard / Pause seeding / Quit); window minimizes to tray instead
  of quitting; `tauri-plugin-autostart` with a settings toggle + first-run prompt;
  `tauri-plugin-single-instance` (second launch focuses the window); `daemon.rs` supervises
  the worker — spawn-bundled or attach-external. Deps: 6a.
- **7b — Live metrics.** ✅ Full spec: [`old/Task.33.md`](old/Task.33.md) Phase B. One-second
  pump: probe → `daemon_up`; `fetch_state` → `Metrics` → `emit("nodera://metrics")`; React
  renders real chunks/GB/peers/world. Deps: 6b. Related: `control.rs`, `main.rs`,
  `metrics.rs`, `ui/src/App.tsx`.
- **7c — Packaging + CI.** 🚧 Per-OS installers (`tauri build` bundles; the bundled-or-located
  Java 21 runtime decision for the worker ships here), app icons, autostart acceptance on each
  OS (A-7-style per-platform care), and a CI job that builds the app so the workspace
  exclusion stops meaning "never compiled in CI". Deps: 6a. Related: `tauri.conf.json`,
  `.github/workflows/`.
- **7d — End-to-end acceptance (L-47 exit).** ⏳ A CI/tooling job that: installs the app,
  verifies the 5g gate both ways (worker present ⇒ Minecraft starts; absent ⇒ actionable
  abort), and proves **cross-machine continuity** — host a world, close Minecraft, and from a
  second machine/instance the world is still listed and joinable because the worker kept it
  alive. Deps: **6c** (worker announce/seeding), **5a** (the joining client harness).

## Testing strategy

- Rust unit tests (once 7c brings the crate into CI): daemon lifecycle (start/stop/supervise/
  attach), metrics parsing (`fetch_state` against golden `STATE` JSON), autostart idempotency,
  single-instance guard.
- The dashboard's data path is already testable end-to-end without the app: the worker's
  `STATE` verb is asserted headlessly in Task 6 — the app only renders it. Keep it that way:
  no logic in the UI worth testing beyond parsing.
- Manual smoke per increment: `scripts/dev.sh --with-app` (attach mode) shows live data;
  quitting the app leaves the externally-started worker untouched (attach semantics).
- 7d is the shared cross-machine continuity proof with Task 6 — one CI job, two owners.

## Limitations

- **L-47** OPEN ([`LIMITATIONS.md`](LIMITATIONS.md)): no automated installer + gate +
  cross-machine continuity test — the 7d exit.
- **L-48** (Task 6): the app supervises a validation-capable **Java** worker; a Rust-native
  lightweight seeder mode stays a possible later addition, never a validator (single-engine
  rule).
- Workspace exclusion: `cargo test` at `rust/` does not cover this crate until 7c; do not
  read the green Rust gate as covering the app.
- Per-OS surface (tray/autostart/installers) needs per-platform acceptance — no shortcut.

## Acceptance criteria

1. 7a/7b: the app builds + runs on the primary dev OS; tray + autostart + single-instance
   work; the dashboard renders the four panels from live worker metrics (not zeros); attach
   mode leaves external workers untouched.
2. 7c: installers produced for the target OSes with icons; CI builds the app; autostart
   verified per OS.
3. 7d: the CI job proves install → gate-both-ways → host → close Minecraft → world still
   joinable from a second instance (L-47 RETIRED).
4. README/Tested + this status table updated; issues per `.github/ISSUE_SYSTEM.md`.

## Notes for the implementing model

- The app is deliberately thin: supervisor + tray + renderer. Any behaviour that touches the
  network belongs in the worker (Task 6) or the services (Tasks 3/4) — if you find yourself
  adding peer logic here, stop.
- Keep `control.rs` a strict mirror of `ControlProtocol` — verbs, version, framing — and bump
  all three sides in one commit.
- The worker must keep working without the app (dev mode: `scripts/dev.sh` runs it bare);
  the app must degrade gracefully when the worker is down (tray shows offline, dashboard
  shows `daemon_up = false`, no crash loops).
- Bundling a JVM is a 7c decision with size consequences — document the choice (bundle vs
  locate) and keep it reversible.
