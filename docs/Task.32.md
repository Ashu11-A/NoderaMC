# Task 32 — The Nodera Companion App: A Required Headless Peer (Tauri: Rust + React), Mod-side Presence Gate, Always-On Node (Phase 6, new `rust/nodera-app` + `neoforge-mod` gate)

**Phase:** 6 (network infrastructure — the always-on peer) · **Depends on:** Tasks 27 (monorepo +
`nodera-codec`), 28 (`nodera-tracker`), 29 (`nodera-rendezvous` + `transport-rendezvous`),
30 (player-hosted model), 10/19–25 (the peer-runtime behaviours the daemon must run) · **Modules:**
new `rust/nodera-app` (Tauri desktop app + headless peer daemon), new `rust/nodera-peer` (the peer
node the daemon runs — see the Rust-vs-JVM decision), `neoforge-mod` (a startup **presence gate** +
a thin control client), `protocol`/`fixtures` (a small local control family), `scripts/`, `docs/`.

> **This decouples "being a Nodera node" from "running Minecraft."** Today the peer lives inside the
> NeoForge JVM and dies when the game closes (verified: `NoderaPeerService.stopHosting()` runs on
> world close — "host peer shutting down"). Task 32 moves the peer into a **separate, always-on
> companion process** that starts with the OS, sits in the system tray, and stays connected to the
> tracker + rendezvous network whether or not Minecraft is running. The mod becomes a **thin client
> of the local daemon** and **refuses to start** if the daemon is absent. This is what makes
> player-hosted worlds survive the host closing their game (request #3's "host doesn't need to stay
> connected") and turns every installer into a persistent network node.

## Goal

1. **A required companion app.** Players install the Nodera companion (from
   `https://github.com/Ashu11-A/NoderaMC`). It is a Tauri (Rust backend + React frontend) desktop app
   that (a) runs a **headless peer daemon** — a full `PeerRuntime`-equivalent node — and (b) shows a
   windowed dashboard + a system-tray presence.
2. **Mod presence gate.** When NeoForge loads the Nodera mod, the mod probes for the local daemon's
   control endpoint. If it is **not running / not installed**, the mod **aborts NeoForge startup**
   with a clear, actionable error ("The Nodera companion app is not running. Install/start it from
   https://github.com/Ashu11-A/NoderaMC"), rather than silently degrading.
3. **Always-on node.** The daemon auto-launches at login, stays connected to the network in the
   background (tray icon, minimizable to tray), and continues seeding / validating the worlds the
   user hosts or has cached **even with Minecraft closed** — so hosts no longer need to keep the game
   open for others to join (fixes the request-#3 limitation and L-40's live half).
4. **Dashboard telemetry.** The window shows: **chunks/data this node is helping maintain** on the
   network; **GB sent / received**; the **peers** currently exchanging data with this node; and the
   **world this node is currently connected to / hosting.**

## Context — where the code stands today (audited 2026-07-20)

- **The peer runs inside the JVM and dies with the game.** `NoderaPeerService` (`neoforge-mod`)
  constructs `PeerRuntime`, `SocketPeerTransport`/`RendezvousPeerTransport`, the `TrackerClient`, and
  the announce scheduler in-process; `stopHosting()` / `stopClient()` tear them down on world close /
  disconnect (log: `Nodera tracker announce STOPPED … host peer shutting down`). **There is no
  persistence of the node beyond the Minecraft process.**
- **The peer runtime is Java.** `PeerRuntime`, gateway election, `TickSync`, membership, the
  `distribution` data plane, `storage-*`, `committee`, `coordinator` are all Java modules under
  `java/`. The Rust side (`rust/`) currently has only the **infrastructure** crates the peers *use* —
  `nodera-codec` (byte-exact canonical encoding + Ed25519 verify + framing), `nodera-tracker`,
  `nodera-rendezvous` — not a peer *node*.
- **The wire is already cross-language.** `nodera-codec` proves the canonical encoding is byte-exact
  between Java and Rust against `fixtures/wire/`. A Rust peer can therefore speak the same protocol as
  the Java peers; a Rust↔Java control channel can reuse the same codec discipline.
- **Rendezvous + tracker are standalone binaries (Tasks 28/29).** The daemon consumes them exactly as
  `NoderaPeerService` does today: register a signed record with `nodera-rendezvous`, announce/query
  worlds with `nodera-tracker`, dial peers over `transport-rendezvous`/socket. Nothing about their
  wire changes — Task 32 adds a *new persistent client* of them, not a new service.
- **No autostart / tray / IPC exists.** Greenfield.

## The core architectural decision — where does the headless peer's logic live?

The daemon must run the full peer behaviour (membership, gateway migration, distribution/seeding,
committee re-execution, storage). That logic exists **in Java today**. Two options:

- **Option A — Rust-native peer (`rust/nodera-peer`).** Port the peer node to Rust, reusing
  `nodera-codec`. *Pros:* one language for all always-on infra; small memory footprint for a
  background daemon; no bundled JVM. *Cons:* large re-implementation of `peer-runtime` +
  `distribution` + `committee` + `storage` + determinism guarantees; risks a second, divergent engine
  (the determinism bet forbids two disagreeing engines — the region *engine* must stay the single
  Java `simulation`, so a Rust peer must **not** re-execute regions; it can only seed/relay/route and
  defer validation to Java committee peers). This constrains Option A to a **seeder/relay/router +
  tracker/rendezvous client** node, not a validator — acceptable for "help maintain chunks/data" but
  it cannot cast committee votes.
- **Option B — Tauri supervises a bundled headless JVM.** The Tauri app ships (or locates) a Java 21
  runtime and runs the existing Java peer as a headless process (`PeerRuntime` with no Minecraft), and
  the React UI talks to it. *Pros:* reuses **all** existing, tested Java peer logic incl. validation;
  no engine duplication. *Cons:* bundled-JVM size; two runtimes in one app.

**DECISION (2026-07-20): Option B is locked.** The daemon supervises a bundled headless Java peer,
reusing all tested Java peer/validation logic; the Rust seeder (Option A) is a later lightweight-only
mode, not the initial build. The rest of this spec assumes Option B.

**Recommendation:** **Option B for validation-capable nodes** (reuse the Java peer, headless), with
**Option A's Rust seeder** as a later lightweight-only mode. Rationale: the determinism invariant
(single Java engine) makes a full Rust validator a liability, and Tasks 19–25 already run headlessly
in Java (`SessionContinuityIT`, `CrashRecoveryIT`) — a headless Java peer is a small wrapper over
proven code, not new engine work. The Tauri app is the supervisor + UI + tray + autostart; the peer
node is the bundled headless Java process it manages. *(This decision is a §Open-Questions gate — it
changes the whole crate layout, so confirm before building.)*

## Folder structure (additions)

```
rust/
└── nodera-app/                     # NEW Tauri app (Rust backend + React frontend)
    ├── Cargo.toml                  # tauri, tauri-plugin-autostart, tokio; depends on nodera-codec
    ├── tauri.conf.json             # system tray, autostart, single-instance, window config
    ├── src/                        # Rust backend
    │   ├── main.rs                 # tray + window + single-instance + autostart registration
    │   ├── daemon.rs               # supervises the headless peer node (Option B: spawn JVM;
    │   │                           #   Option A: run rust/nodera-peer in-proc)
    │   ├── control.rs              # the local control endpoint the mod probes/queries (see 32b)
    │   ├── metrics.rs              # chunks maintained, GB in/out, peers, current world → to the UI
    │   └── tray.rs                 # status icon, quick actions (open window, pause seeding, quit)
    └── ui/                         # React frontend (Vite)
        ├── src/App.tsx             # dashboard: maintained-chunks, GB↑/↓, peer list, current world
        ├── src/panels/{Chunks,Bandwidth,Peers,World}.tsx
        └── src/ipc.ts              # tauri invoke/event bridge to the Rust backend

rust/nodera-peer/                   # NEW (Option A only) — Rust seeder/relay/router node (no engine)

java/neoforge-mod/src/main/java/dev/nodera/mod/
├── common/
│   ├── CompanionGate.java          # NEW: probe the local daemon at mod init; abort NeoForge if absent
│   └── CompanionClient.java        # NEW: thin control client (query state, request host/join via daemon)
└── NoderaMod.java / ServerBootstrap.java   # CHANGE: run CompanionGate before wiring the peer;
                                    #   route host/join through the daemon instead of an in-JVM PeerRuntime

protocol/ (+ fixtures/)            # NEW small local-control family (loopback only, versioned)
scripts/dev.sh                     # CHANGE: build/run the companion app for local dev
docs/                              # this file; README/AGENTS reframed: "install the companion app"
```

## Implementation details

### 32a — The companion app (Tauri: Rust + React)

1. **Scaffold `rust/nodera-app`** in the existing cargo workspace (Task 27). Tauri 2.x. React + Vite
   frontend under `ui/`. Depends on `nodera-codec` for wire types.
2. **System tray + window.** Tray icon (Windows "background apps" area / macOS menu bar / Linux tray)
   with status (connected / degraded / offline), a menu (Open dashboard, Pause seeding, Quit), and a
   normal window that minimizes to tray instead of quitting (`tauri.conf.json` +
   `WindowEvent::CloseRequested` → hide).
3. **Autostart.** `tauri-plugin-autostart` registers the app to launch at login on all three OSes;
   a settings toggle. First-run prompt to enable.
4. **Single instance.** `tauri-plugin-single-instance` — one daemon per machine; a second launch
   focuses the window.
5. **Dashboard panels** (React, fed by Rust `emit` events on a cadence):
   - **Chunks/data maintained:** count of pieces + bytes this node currently seeds/holds for the
     network (from the daemon's `ArchiveInventory` / content store).
   - **Bandwidth:** GB sent / received, live rate (from the daemon's `TrafficMeter` equivalent —
     `MeteredPeerTransport` already meters this on the Java side; expose it over control).
   - **Peers:** the peers currently exchanging data with this node (id, route, ↑/↓ rate, relay vs
     direct — from `TransportSelector` path reports, Task 29).
   - **Current world:** the world(s) this node is hosting / connected to (name, players, health,
     held-%).

### 32b — The headless peer daemon + local control endpoint

1. **Run the peer node** (Option B recommended): the Tauri backend supervises a bundled headless Java
   peer — a new tiny `main` in `peer-runtime` (or a `nodera-headless` module) that boots a
   `PeerRuntime` with capabilities from config, registers with rendezvous, announces cached/hosted
   worlds to the tracker, seeds/serves the `distribution` data plane, and runs committee validation
   for regions it is assigned — all **without Minecraft**. This is the `SessionContinuityIT` /
   `ArchiveManager` code paths run as a long-lived process.
2. **Local control endpoint** — a loopback-only listener (e.g. `127.0.0.1:<fixed/known port>` or an
   OS-native named pipe / unix socket) that:
   - answers a **presence probe** (mod → "are you there?" → daemon identity + version + protocol
     version) for the gate (32c);
   - accepts **host/join requests** from the mod (share this world / join world X) and returns
     routes/status;
   - streams **state** (the dashboard metrics) so the mod's in-game HUD and the companion window read
     the same source of truth.
3. **Control protocol** is a small, **versioned, loopback-only** message family, framed with the same
   `nodera-codec` length-prefix discipline (or a simple JSON-over-HTTP localhost API if that is
   friendlier to the React side — decide with the Option A/B gate). It is **not** consensus state and
   carries **no** secret material beyond the local trust boundary; passwords for hosting are entered
   in the companion, not sent from the mod, unless the local channel is authenticated with a
   per-install token written to a file both processes read.
4. **Persistence:** the daemon owns the peer identity (`PersistentIdentityStore`, L-28) and the
   client content store (`storage-client`, Task 22) so identity + cache survive Minecraft restarts —
   the node's continuity is the daemon's job now, not the game's.

### 32c — The mod presence gate (abort NeoForge if the daemon is absent)

1. At mod construction (`NoderaMod` / a `FMLCommonSetupEvent`), `CompanionGate.requireRunning()`
   probes the local control endpoint with a short timeout.
2. **If present:** verify protocol-version compatibility, store a `CompanionClient` handle, and wire
   the mod to route all peer operations through the daemon (host/join/query) instead of constructing
   an in-JVM `PeerRuntime`. `NoderaPeerService` becomes a thin façade over `CompanionClient`.
3. **If absent / incompatible:** abort startup loudly. On the client, throw so NeoForge shows the mod
   as errored and/or display a blocking error screen; the message names the cause and the install URL
   (`https://github.com/Ashu11-A/NoderaMC`). Prefer a NeoForge-native "mod loading error" so the game
   does not hard-crash to desktop with a stack trace but shows an actionable dialog. On a dedicated
   server, log-and-exit with the same message.
4. **Version skew:** if the daemon is older/newer than the mod expects, the gate says exactly that
   ("update the companion app" / "update the mod"), not a generic failure.
5. **Do not double-run the peer.** With the daemon present, the mod must **not** also start its own
   `PeerRuntime` — the in-JVM peer path (`NoderaPeerService.startHost`/`onServerSessionInfo`) is
   replaced by delegation to the daemon. This is the substantive behavioural change of Task 32.

### 32d — Deep integration with tracker + rendezvous (analysis of Tasks 28/29 coupling)

The daemon is the new **primary** client of both Rust services; audit the integration points:

- **Tracker (Task 28, `nodera-tracker`):** the announce loop that today lives in
  `NoderaPeerService.startAnnouncing()` (a `nodera-tracker-announce` daemon thread inside the JVM,
  stopping on world close) **moves into the companion daemon**, which keeps announcing hosted/cached
  worlds continuously. This is the mechanism by which a host's world stays listed after they close
  Minecraft. The Java `TrackerClient` (announce/query) is reused by the headless peer (Option B) or
  reimplemented over `nodera-codec` (Option A). Consequence for Task 31's multiplayer screen: the
  "own worlds" list and player counts come from the **daemon's** persistent tracker view, not a
  per-session query.
- **Rendezvous (Task 29, `nodera-rendezvous` + `transport-rendezvous`):** the signed-record
  registration + relay reservations + hole-punch that `NoderaPeerService.composeHostTransport()` does
  per-share **move into the daemon** and persist across game sessions, so the node stays reachable
  (and relay-reachable) while Minecraft is closed. `TransportSelector` (direct > punched > relayed)
  runs in the daemon; its path reports feed the dashboard's peer panel.
- **Continuity (Task 10):** gateway migration / session continuity now spans the daemon's lifetime,
  not the game's — a host closing Minecraft is no longer a peer-leave for the host's *node* (the
  daemon stays), only for the host's *player* session. This materially changes the continuity model:
  update `Task.10.md` and L-30/L-40 framing (the always-on node is the intended L-41 "sidecar"
  answer — see Limitations).
- **Security boundary (Task 0 rule 7):** the daemon is still an **untrusted-by-peers** node —
  everything it serves is hash/signature-verified by counterparties. The companion being *required*
  does not make it authoritative; it is a persistence + reachability convenience, not a trust anchor.

## Testing strategy

### Headless / unit (the gate)

1. **`CompanionGateTest` (Java):** probe → present ⇒ proceeds; absent ⇒ throws the actionable error
   with the install URL; version-skew ⇒ the specific message. Uses a fake local endpoint.
2. **Control-protocol conformance:** the local control family round-trips (Java `CompanionClient` ↔ a
   test daemon), byte-exact against `fixtures/` if it rides `nodera-codec` (else a JSON schema test).
3. **`nodera-app` Rust tests:** daemon lifecycle (start/stop/supervise), metrics aggregation
   (chunks/bytes/peers), tracker-announce persistence across a simulated game-close, autostart
   registration is idempotent, single-instance guard.
4. **Headless peer IT:** the daemon keeps a world announced + seeded after the "game" (a driving test
   client) disconnects — the request-#3 property, proven without Minecraft (extends
   `SessionContinuityIT` / `ArchiveManager`).

### Real acceptance (GUI env, deferred with L-45)

- Install the companion, launch NeoForge → mod starts (gate passes), tray shows connected.
- Quit the companion, launch NeoForge → the actionable error appears (gate fails closed).
- Host a world, close Minecraft, from a second machine/instance see the world still listed and
  joinable (the daemon kept it alive) — the headline Task 32 outcome.

## Limitations (`LIMITATIONS.md` §B)

- **Answers L-41** (the "separate-OS-sidecar process" open item): the companion daemon **is** the
  always-on out-of-game process. L-41 moves OPEN → RETIRING, owned by T32; RETIRED when the daemon's
  continuous flush/seed is proven to survive a Minecraft `kill -9` (the daemon is a different
  process, so it does).
- **Advances L-40** (active-player stream / continuous seeding live half): the daemon runs it
  out-of-game.
- **Reframes L-30/A-3** continuity: the node's liveness is decoupled from the game's; update
  `Task.10.md`.
- **New L-47 (proposed):** *the companion is required but there is no automated end-to-end installer +
  gate + cross-machine continuity test (needs two hosts / a CI matrix + GUI env).* Owner: T32. Exit:
  a CI job installs the app, runs the gate both ways, and proves cross-session world availability.
- **New L-48 (proposed):** *Option-A Rust seeder cannot validate (single-engine determinism rule);
  until a validation-capable node ships, headless nodes are seeder/relay/router only unless running
  the Option-B Java peer.* Owner: T32/T16.
- **A-7 / platform:** autostart, tray, and single-instance are per-OS; each needs its own acceptance.

## Acceptance criteria

1. **Companion app builds + runs** (`rust/nodera-app`): Tauri window + system-tray presence +
   autostart registration + single-instance, on at least the primary dev OS; React dashboard renders
   the four required panels (maintained chunks/data, GB ↑/↓, peers, current world) from live daemon
   metrics.
2. **Headless peer daemon** runs a `PeerRuntime`-equivalent node without Minecraft, registers with
   rendezvous, announces to the tracker, and **keeps a hosted world available after the game closes**
   (headless IT green).
3. **Mod presence gate.** With the daemon running, the mod starts and routes peer ops through it (no
   in-JVM `PeerRuntime`). With the daemon absent/incompatible, NeoForge startup aborts with the
   actionable error naming `https://github.com/Ashu11-A/NoderaMC` (`CompanionGateTest` green).
4. **Tracker/rendezvous integration moved.** The announce loop + rendezvous registration live in the
   daemon and persist across game sessions; `Task.10.md`/L-40/L-41 updated accordingly.
5. `./gradlew check` **and** `cd rust && cargo test` green (incl. new `nodera-app` tests); README +
   `Tested.md` + `LIMITATIONS.md` updated in the same commit; GitHub issue `Task 32 — <title>`
   opened/closed per `.github/ISSUE_SYSTEM.md`.
6. **Real installer + cross-machine continuity acceptance** deferred with L-45/L-47 (GUI + multi-host
   env), documented.

## Notes for the implementing model

- **The Option A/B decision gates the whole task** — resolve it first (recommended B: Tauri supervises
  a bundled headless Java peer, reusing all tested Java peer logic; Rust seeder as a later mode). Do
  not build two divergent region engines — the determinism bet forbids it (only Java `simulation`
  re-executes regions).
- The control channel is **loopback-only, versioned, non-authoritative**; peers still verify
  everything the daemon serves (Task 0 rule 7). Requiring the app is a persistence/reachability
  convenience, never a new trust anchor.
- The gate must **fail closed, clearly** — a missing daemon is a first-class, explained error with an
  install link, not a stack trace or a silent no-network degrade.
- One task = one branch = one PR; land the Tauri shell + daemon supervisor, then the gate + control
  client, then the tracker/rendezvous move, as increments behind the single Task 32 issue.
