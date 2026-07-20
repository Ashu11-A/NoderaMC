# nodera-app — the Nodera companion app (Task 32)

A [Tauri](https://tauri.app) desktop app (Rust backend + React frontend) that runs the **always-on
Nodera peer** so a node stays on the network even with Minecraft closed — this is what lets a
player-hosted world survive the host closing their game, and turns every install into a persistent
network node.

**Architecture — Option B (locked):** this app *supervises the bundled headless Java Nodera peer
worker* (`nodera-headless`, reusing all of the tested Java peer/validation logic — the determinism
rule forbids a second region engine). The **worker owns the loopback control endpoint** that the
Minecraft mod probes; this app is the UI, system tray, autostart, the **worker supervisor**
(`daemon.rs`), and a **control-endpoint monitor** (`control.rs`) that probes the worker for liveness.

**Attach mode** (`NODERA_APP_ATTACH=1`): the app does not spawn its own worker — it attaches to one
already running (e.g. started by `scripts/dev.sh`). Used so dev runs don't fight over the control
port. In production the app supervises the worker itself.

## Why it is excluded from the `rust/` workspace

Tauri pulls per-OS native webview deps (webkit2gtk on Linux, WebView2 on Windows, WebKit on macOS)
that are not part of the headless CI toolchain, so `cargo test` / `cargo build --workspace` must not
try to compile it. It is listed under `exclude` in `rust/Cargo.toml` and built with its own toolchain.

## Prerequisites

- Rust (workspace `rust-toolchain.toml`) + the [Tauri v2 prerequisites](https://v2.tauri.app/start/prerequisites/)
  for your OS.
- Node.js 18+ (for the Vite/React frontend under `ui/`).
- `cargo install tauri-cli --version '^2'` (provides `cargo tauri`).

## Build / run

```bash
cd rust/nodera-app
npm --prefix ui install          # first time
cargo tauri dev                  # window + tray + control endpoint + daemon supervisor
cargo tauri build                # release installer (with autostart + tray)
```

The supervised worker launcher is resolved from `NODERA_WORKER_BIN`, else the bundled
`resources/nodera-headless/bin/nodera-headless` (the `:nodera-headless` `installDist` output, copied
in at bundle time). Build the worker with `./gradlew :nodera-headless:installDist` and copy
`java/nodera-headless/build/install/nodera-headless` to `resources/nodera-headless` before
`cargo tauri build`.

## Control endpoint

The **worker** (`nodera-headless`) owns `127.0.0.1:25610` and answers the presence probe
(`dev.nodera.peer.control.ControlProtocol`, mirrored by the mod's `CompanionProtocol`):

```
client → worker:  NODERA-PROBE <protocolVersion>
worker → client:  NODERA-OK <protocolVersion> <workerVersion>
```

This app *connects* to that endpoint (`control::monitor`) for liveness; it does not bind it. Keep
`PROTOCOL_VERSION` in `src/control.rs` in lockstep with the Java `ControlProtocol`. Loopback-only and
non-authoritative: peers still verify everything the node serves on the real network (Task 0 rule 7).

## Status

Scaffold: Rust backend (`main`/`control`/`daemon`/`metrics`) + React dashboard (the four required
panels: maintained chunks/data, GB ↑/↓, peers, current world) + tray + autostart + single-instance +
attach mode. The **worker itself is real and runnable** (`java/nodera-headless`, proven to boot +
answer the probe). Deferred (the live lane): the worker's telemetry pump feeding real dashboard
metrics, the host/join control verbs, per-OS installers, and app icons under `icons/`.
