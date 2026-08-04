# App — Category Charter

<!-- AI-AGENT-INSTRUCTION: The app is a SUPERVISOR + TRAY + RENDERER. It is not a peer: it holds no
     signing keys and serves nothing to the network. If you find yourself adding peer logic, network
     protocol, or consensus code here, stop — it belongs in the worker or the Rust services. Keep
     control.rs a strict mirror of the worker's ControlProtocol. Keep the task index in agreement with
     ../ROADMAP.md §2. -->

**Category:** `app` · **Status:** 🚧 IN PROGRESS (6 of 11 tasks completed) ·
**Last audit:** 2026-08-01

---

## 1. What this category is

The **Nodera launcher**: a Tauri desktop shell with a React front end and a shared Rust core. It
launches Minecraft into a selected world, starts at login, sits in the tray, and supervises the
bundled headless worker. Android consumes the same core through two JNI calls behind a native Compose
Material You companion.

Because the mod refuses to start without the worker, the app is **the thing every player actually
runs**. It must therefore be boring, small, and reliable.

## 2. What it is not

It is **not a peer**. All network behaviour lives in the worker (Java) and the Rust service binaries.
The app holds no signing keys, participates in no committee, and serves nothing to the network. It
supervises a process, coordinates launch/tunnel lifetime, and renders worker state.

This boundary is what keeps the app cheap to change: a UI mistake cannot corrupt a world, and the
dashboard's data path is already testable without the app at all, because the worker's `STATE` verb is
asserted headlessly on the Java gate.

## 3. Architecture

```
desktop React launcher ─┐
                       ├─ nodera-core: worker link · settings · stores · launch coordinator
Android Compose + JNI ──┘                         │
                                                 │ 127.0.0.1:25610
Tauri shell: window · tray · autostart · supervisor ── Java worker
```

**Attach mode matters.** The supervisor can either spawn the bundled worker or **attach** to one that
was started externally — which is how the development script runs it. Quitting the app must leave an
externally started worker untouched: the app must never kill a process it did not spawn.

## 4. Dependencies

**Depends on:** [`worker/Task.1.md`](../peer/Task.1.md) (the process it supervises),
[`worker/Task.2.md`](../peer/Task.2.md) (the control endpoint it reads).

**Consumed by:** players (it is the install target) and
[`minecraft/Task.7.md`](../minecraft/Task.7.md), whose gate assumes the app keeps the worker alive.

## 5. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Tauri scaffold + worker supervisor | ✅ COMPLETED |
| [2](Task.2.md) | Live metrics dashboard | ✅ COMPLETED |
| [3](Task.3.md) | Per-OS packaging + CI | 🚧 IN PROGRESS |
| [4](Task.4.md) | End-to-end acceptance + cross-machine continuity | ⏳ BLOCKED |
| [5](Task.5.md) | Telemetry consent: first-run modal + Privacy screen | 🚧 IN PROGRESS |
| [6](Task.6.md) | The dashboard API and the live link | ✅ COMPLETED |
| [7](Task.7.md) | The client becomes the way in | ✅ COMPLETED |
| [8](Task.8.md) | One subject per screen | ✅ COMPLETED |
| [9](Task.9.md) | Tracker stores | ✅ DONE |
| [10](Task.10.md) | Practical screens, honest numbers | 🚧 IN PROGRESS |
| [11](Task.11.md) | A launcher, not a dashboard | 🚧 IN PROGRESS |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

## 6. Files

| Path | Contents |
|---|---|
| `app/Cargo.toml` | Tauri, autostart plugin, tokio, serde — **workspace-excluded** |
| `app/tauri.conf.json` | Tray, autostart, single-instance, window and bundle config |
| `app/src/` | Thin Tauri command, tray, window and platform shell |
| `library/rust/nodera-core/` | Shared worker link, settings, stores, telemetry and launch coordinator |
| `app/ui/` | React + Vite desktop launcher |
| `app/android/kotlin/` | Native Compose Material 3 Android shell |

Package architecture: [`app/README.md`](../../app/README.md).

## 7. Conventions specific to this category

- **The crate is workspace-excluded.** Tauri's native webkit dependencies would break the headless
  `cargo test` gate, so it builds separately (`cd app && cargo test`). **Do not read the
  green workspace Rust gate as covering this crate** — that is exactly what task 3's CI job fixes.
- **`control.rs` is a strict mirror** of the worker's `ControlProtocol`: verbs, version, framing.
  Change all three mirrors in one commit and let the mod's gate classify skew.
- **The worker must work without the app** (development runs it bare), and **the app must degrade
  gracefully when the worker is down**: the tray shows offline, the dashboard shows the daemon as
  down, and there are no crash loops.
- **No logic in the UI worth testing beyond parsing.** If a panel needs a rule, the rule belongs in the
  worker where it can be asserted headlessly.
- **A setting the node cannot honour is badged, not faked.** The UI distinguishes "not enforced yet"
  from "not supported", using the worker's own reason.
