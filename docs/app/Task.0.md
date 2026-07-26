# App — Category Charter

<!-- AI-AGENT-INSTRUCTION: The app is a SUPERVISOR + TRAY + RENDERER. It is not a peer: it holds no
     signing keys and serves nothing to the network. If you find yourself adding peer logic, network
     protocol, or consensus code here, stop — it belongs in the worker or the Rust services. Keep
     control.rs a strict mirror of the worker's ControlProtocol. Keep the task index in agreement with
     ../ROADMAP.md §2. -->

**Category:** `app` · **Status:** 🚧 IN PROGRESS (2 of 5 tasks completed) ·
**Last audit:** 2026-07-25

---

## 1. What this category is

The **Nodera companion**: a small Tauri desktop application (Rust backend, React frontend) that
players install. It launches at login, sits in the system tray, runs and supervises the bundled
headless peer worker, and shows a live dashboard — the chunks and data this node maintains for the
network, bytes sent and received, the peers currently exchanging data, and the worlds this node hosts
or is connected to.

Because the mod refuses to start without the worker, the app is **the thing every player actually
runs**. It must therefore be boring, small, and reliable.

## 2. What it is not

It is **not a peer**. All network behaviour lives in the worker (Java) and the Rust service binaries.
The app holds no signing keys, participates in no committee, and serves nothing to the network. It
supervises a process and renders a JSON snapshot.

This boundary is what keeps the app cheap to change: a UI mistake cannot corrupt a world, and the
dashboard's data path is already testable without the app at all, because the worker's `STATE` verb is
asserted headlessly on the Java gate.

## 3. Architecture

```
  ┌───────────────────────── Tauri app ─────────────────────────┐
  │ main.rs   tray · window · single-instance · autostart       │
  │ daemon.rs supervisor: spawn bundled JVM  |  ATTACH external │
  │ control.rs loopback client: probe + fetch_state (mirror of  │
  │            the worker's ControlProtocol)                    │
  │ metrics.rs Metrics struct ──emit("nodera://metrics") 1 Hz──►│
  │ ui/        React + Vite: Info · State · Peers · Trackers ·  │
  │            Pieces, inside VPN-client connection chrome      │
  └──────────────────────────────┬──────────────────────────────┘
                                 │ 127.0.0.1:25610
                          worker (Java, always-on)
```

**Attach mode matters.** The supervisor can either spawn the bundled worker or **attach** to one that
was started externally — which is how the development script runs it. Quitting the app must leave an
externally started worker untouched: the app must never kill a process it did not spawn.

## 4. Dependencies

**Depends on:** [`worker/Task.1.md`](../worker/Task.1.md) (the process it supervises),
[`worker/Task.2.md`](../worker/Task.2.md) (the control endpoint it reads).

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

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

## 6. Files

| Path | Contents |
|---|---|
| `rust/nodera-app/Cargo.toml` | Tauri, autostart plugin, tokio, serde — **workspace-excluded** |
| `rust/nodera-app/tauri.conf.json` | Tray, autostart, single-instance, window and bundle config |
| `rust/nodera-app/src/main.rs` | Tray, window, single-instance, autostart, the metrics pump |
| `rust/nodera-app/src/daemon.rs` | Worker supervisor: spawn bundled JVM or attach to an external worker |
| `rust/nodera-app/src/control.rs` | Loopback control client — a strict mirror of `ControlProtocol` |
| `rust/nodera-app/src/metrics.rs` | The `Metrics` struct the UI renders |
| `rust/nodera-app/ui/` | React + Vite dashboard |

Package architecture: [`rust/nodera-app/README.md`](../../rust/nodera-app/README.md).

## 7. Conventions specific to this category

- **The crate is workspace-excluded.** Tauri's native webkit dependencies would break the headless
  `cargo test` gate, so it builds separately (`cd rust/nodera-app && cargo test`). **Do not read the
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
