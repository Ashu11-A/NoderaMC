# `rust/nodera-app`

<!-- AI-AGENT-INSTRUCTION: This crate is WORKSPACE-EXCLUDED — `cd rust && cargo test` does NOT cover
     it, so never present the green workspace gate as covering the app; run `cd rust/nodera-app &&
     cargo test`. The app is a SUPERVISOR + TRAY + RENDERER, not a peer: it holds no signing keys and
     serves nothing to the network. ATTACH SEMANTICS ARE LOAD-BEARING — never kill a worker the app
     did not spawn. Keep control.rs a strict mirror of the worker's ControlProtocol, and change all
     three mirrors in one commit. Update this file when a module or a build step changes. -->

**The Nodera companion.** A [Tauri](https://tauri.app) desktop application (Rust backend, React
frontend) that players install: it launches at login, sits in the system tray, supervises the
always-on peer worker, and shows a live dashboard.

Because the mod refuses to start without the worker, this is **the thing every player actually runs**.
It must be boring, small, and reliable.

- **Depends on:** the worker's loopback control endpoint (`java/peer`, `dev.nodera.headless`).
- **Depended on by:** players; the mod's presence gate assumes it keeps the worker alive.
- **Docs:** [`docs/app/`](../../docs/app/Task.0.md)

---

## Architecture

```
rust/nodera-app/
├── Cargo.toml        tauri, autostart plugin, tokio, serde — WORKSPACE-EXCLUDED
├── tauri.conf.json   tray, autostart, single instance, window and bundle configuration
├── src/
│   ├── main.rs       tray + window + single-instance + autostart + the 1 Hz metrics pump
│   ├── daemon.rs     the supervisor: spawn the bundled worker OR attach to an external one
│   │                 plus Android's allowlisted P2P-property handoff from persisted settings
│   ├── control.rs    loopback control client — a strict mirror of ControlProtocol
│   ├── metrics.rs    the Metrics struct the UI renders
│   ├── settings.rs   the settings surface backed by the worker's CONFIG verb
│   ├── config.rs     app configuration and the worker's spawn environment
│   ├── power.rs      power-state policy (whether to transfer)
│   ├── logs.rs       a bounded log ring
│   ├── system.rs     host sampling
│   └── android/
│       └── worker.rs one-shot Android context + setup startup gate
└── ui/src/           React + Vite: App, Settings, World, components, theme, ipc
                      — Info / State / Peers / Trackers / Pieces, in VPN-client chrome
```

**Option B is locked.** The app supervises the bundled **headless Java peer worker**, reusing all of
the tested Java peer and validation logic. The determinism rule forbids a second region engine, so a
Rust-native peer could only ever seed, relay, and route — never validate.

## Why it is shaped this way

**It is not a peer, and that boundary is what keeps it cheap.** All network behaviour lives in the
worker and the Rust service binaries. A UI mistake here cannot corrupt a world, and the dashboard's
data path is already asserted **on the Java gate** through the worker's `STATE` verb — so the app's
own test surface is parsing, not logic.

**Attach mode is a correctness rule, not a convenience.** Two supervisors both believing they own one
worker would fight over its lifecycle. The app distinguishes a worker it spawned from one it merely
found, and quitting stops only the former. A blanket kill-on-exit would terminate a worker started by
a script or a service manager.

**Minimise to tray, do not quit.** The product promise is that the node stays online; a close button
that ends the node would break it the first time someone tidied their taskbar.

**Parse tolerantly, by contract.** Worker `STATE` fields are additive, so unknown fields use serde
defaults: a worker newer than its dashboard shows fewer panels, never an error.

**Byte order is a real bug class.** The piece bitmap is produced by Java's `BitSet` and consumed here;
a mis-decoded bitmap renders a *plausible but wrong* picture, which is worse than rendering nothing.
The decoder is tested against that exact contract.

**Badges must not lie.** A setting the node cannot honour gets a distinct muted "not supported" badge
carrying the worker's own reason — deliberately not the amber "not enforced yet", which would imply
the feature is coming. Two connection settings are permanently in that state and are kept in the UI on
purpose: deleting them would silently discard values users already saved and would hide a known
limitation.

**The workspace exclusion is deliberate and owned.** Tauri pulls per-OS native webview dependencies
(webkit2gtk, WebView2, WebKit) that are not part of the headless CI toolchain, so the workspace gate
must not try to compile it. The cost — CI not compiling the app — is a tracked deliverable rather than
an unnoticed gap.

## Control endpoint

The **worker** owns `127.0.0.1:25610` and answers the presence probe; this app *connects* to it and
never binds it.

On Android, the mobile Network screen persists random/fixed P2P settings and Rust writes
`nodera-worker.properties`. A one-shot gate waits for both `MainActivity`'s bound context and Tauri
setup's successful property handoff before calling Kotlin. Kotlin allowlists only `NODERA_P2P_PORT`
and `NODERA_P2P_PORT_RANGE`; control stays on the shared default because setting only Java's side
would disconnect this Rust client. Desktop keeps its existing environment handoff, generated from
the same P2P settings encoder.

```
client → worker:  NODERA-PROBE <protocolVersion>
worker → client:  NODERA-OK <protocolVersion> <workerVersion>
```

Keep `PROTOCOL_VERSION` in `src/control.rs` in lockstep with the Java `ControlProtocol` and the mod's
`CompanionProtocol` — three mirrors, one commit. The channel is loopback-only and
**non-authoritative**: peers still verify everything the node serves on the real network.

## Build and run

Prerequisites: the pinned Rust toolchain plus the Tauri v2 prerequisites for your OS, Node.js 18+ for
the frontend, and `cargo install tauri-cli --version '^2'`.

```bash
cd rust/nodera-app
npm --prefix ui install       # first time
npm --prefix ui test          # type-check + bundle + emitted-CSS contracts
cargo tauri dev               # window + tray + supervisor + control monitor
cargo tauri build             # release bundle (autostart + tray)
cargo test                    # REQUIRED — the workspace gate does not cover this crate

scripts/dev.sh --with-app     # manual smoke, in attach mode
```

The supervised worker launcher resolves from `NODERA_WORKER_BIN`, otherwise from the bundled worker
distribution copied in at bundle time (build it with the worker's `installDist` task first).
`NODERA_APP_ATTACH=1` selects attach mode, so development runs do not fight over the control port.

## Rules

- The worker must work without the app; the app must degrade gracefully when the worker is down —
  tray offline, daemon shown as down, no crash loops.
- No logic in the UI worth testing beyond parsing. If a panel needs a rule, put the rule in the worker
  where it is asserted headlessly.
- Never kill a worker the app did not spawn.

## Tests

188 tests: 187 Rust tests covering bitmap decoding and its edge cases, additive-field tolerance,
control-socket error surfacing and timeouts, the settings golden JSON (the cross-language key
contract), worker-environment spawn pairs, Android-property parity/control isolation/replacement,
both Android startup orders, a power-state truth table, the log ring, system sampling, and
badge-enforcement invariants; plus one post-build frontend test pinning emitted tracker-store
desktop roles, dynamic Material 3 mobile roles, and padding ownership. The frontend test runs after
every production UI build, so a utility Tailwind silently omitted cannot satisfy it.
