# App Task 1 — Tauri Scaffold + Worker Supervisor

<!-- AI-AGENT-INSTRUCTION: ATTACH SEMANTICS ARE LOAD-BEARING: the app must never kill a worker it did
     not spawn. Quitting in attach mode leaves the external worker running. Do not "simplify" the
     supervisor into an unconditional kill-on-exit. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (scaffold; CI build → [task 3](Task.3.md))
**Category:** app · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [worker 1](../worker/Task.1.md)
**Consumed by:** [app 2](Task.2.md), [minecraft 7](../minecraft/Task.7.md)

---

## Goal

A desktop shell that launches at login, stays out of the way in the system tray, and keeps the peer
worker running — with a supervisor that is safe in both of its modes.

## Status detail

Complete as a scaffold. `main.rs` provides the tray, window, single-instance guard, and autostart
registration; `daemon.rs` supervises the worker and is **attach-aware**; `tray.rs` carries the status
icon and quick actions; the React shell renders the dashboard panels.

The crate is **workspace-excluded** from the headless `cargo test` gate because Tauri's native webkit
dependencies would break it. It builds separately, and `scripts/dev.sh --with-app` launches it in
attach mode.

## Dependencies

- [worker 1](../worker/Task.1.md) — the process being supervised and probed.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Tauri 2.x window + application shell | ✅ |
| 2 | System tray with status and a menu (open dashboard, pause seeding, quit) | ✅ |
| 3 | Window minimises to tray instead of quitting | ✅ |
| 4 | Autostart registration with a settings toggle and a first-run prompt | ✅ |
| 5 | Single-instance guard (a second launch focuses the window) | ✅ |
| 6 | `daemon.rs` — spawn the bundled worker **or** attach to an external one | ✅ |
| 7 | React + Vite dashboard shell | ✅ |
| 8 | CI build so the exclusion stops meaning "never compiled in CI" | → [task 3](Task.3.md) |

## Design

**Attach mode is not a developer convenience — it is a correctness rule.** Two supervisors both
believing they own one worker would fight over its lifecycle. The app therefore distinguishes a worker
it spawned from one it merely found, and quitting only stops the former. A blanket kill-on-exit would
terminate a worker started by a script, a service manager, or another instance.

**Minimise to tray, do not quit.** The whole product promise is that the node stays online. An
application whose close button ends the node would silently break that promise the first time someone
tidied their taskbar.

**Single instance.** A second launch focuses the existing window rather than starting a second
supervisor — which would immediately violate the ownership rule above.

**Autostart is opt-in with a prompt, not silent.** Registering for login start without asking is the
behaviour users distrust in background software, and this app already asks for a lot of trust by being
mandatory.

**Workspace exclusion is deliberate and temporary.** Tauri's native dependencies are heavyweight and
platform-specific; including the crate in the headless workspace gate would make every unrelated Rust
change depend on a webkit toolchain. The cost is that CI does not compile the app at all, which is why
a dedicated build job is a named deliverable rather than an afterthought.

## Files

- `rust/nodera-app/src/{main,daemon,tray}.rs`, `rust/nodera-app/tauri.conf.json`
- `rust/nodera-app/ui/`
- `scripts/dev.sh` (`--with-app`)

## Testing

- Daemon lifecycle: start, stop, supervise, **attach** — including that quitting in attach mode leaves
  the external worker running.
- Autostart idempotency; the single-instance guard.
- Manual smoke per increment: `scripts/dev.sh --with-app` shows live data; quitting the app leaves the
  externally started worker untouched.

## Acceptance criteria

1. ✅ The app builds and runs on the primary development OS.
2. ✅ Tray, autostart, and single-instance behave correctly.
3. ✅ The supervisor spawns a bundled worker or attaches to an external one.
4. ✅ Quitting never kills a worker the app did not spawn.
5. ⏳ CI compiles the app ([task 3](Task.3.md)).

## Limitations

None owned. The workspace-exclusion consequence is recorded in [`LIMITATIONS.md`](LIMITATIONS.md) §C —
it is a known trade-off with a named owner, not a hidden gap.
