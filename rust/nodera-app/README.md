# nodera-app — the Nodera companion app (Task 32)

A [Tauri](https://tauri.app) desktop app (Rust backend + React frontend) that runs the **always-on
Nodera peer** so a node stays on the network even with Minecraft closed — this is what lets a
player-hosted world survive the host closing their game, and turns every install into a persistent
network node.

**Architecture — Option B (locked):** this app *supervises a bundled headless Java Nodera peer*
(reusing all of the tested Java peer/validation logic — the determinism rule forbids a second region
engine). The Rust side is the UI, system tray, autostart, the loopback **control endpoint** the mod
probes, the **daemon supervisor**, and metrics aggregation.

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

The supervised headless peer jar is resolved from `NODERA_HEADLESS_JAR` (dev) or the bundled
`resources/nodera-headless.jar` (release); the Java runtime from `NODERA_JAVA` or `PATH`. Producing
that headless jar (a Minecraft-free `main` over `peer-runtime`) is the Java-side half of Task 32.

## Control endpoint

Binds `127.0.0.1:25610` and answers the mod's presence probe
(`dev.nodera.mod.common.CompanionProtocol`):

```
mod → daemon:  NODERA-PROBE <protocolVersion>
daemon → mod:  NODERA-OK <protocolVersion> <daemonVersion>
```

Keep `PROTOCOL_VERSION` in `src/control.rs` in lockstep with the Java constant. The endpoint is
loopback-only and non-authoritative: peers still verify everything the node serves on the real
network (Task 0 rule 7).

## Status

Scaffold: Rust backend (`main`/`control`/`daemon`/`metrics`) + React dashboard (the four required
panels: maintained chunks/data, GB ↑/↓, peers, current world) + tray + autostart + single-instance.
Deferred (the live lane): the headless-peer jar + its telemetry pump feeding real metrics, the
host/join control verbs, per-OS installers, and app icons under `icons/`.
