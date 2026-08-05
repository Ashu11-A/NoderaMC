# NoderaMC Roadmap

<!-- AI-AGENT-INSTRUCTION: This is the SINGLE CENTRAL ROADMAP. It aggregates every category's task
     status into one ordering/priority/difficulty analysis. It is DERIVED, never authoritative:
     each row's status must equal the status header of the task file it links to. On every
     outcome-changing commit — update the owning task file first, then this file's §2 row, re-check
     the §3 wave it sits in, and re-derive the completion figure in §1. If this file and a task file
     disagree, the task file wins and this file is the bug. Never renumber a category's tasks to
     make this table tidier; task numbers are referenced by GitHub issues and commit messages. -->

**Snapshot: 2026-08-01** · Overall system completion **90.4%** (not recomputed) · 2,285 Java tests ·
690 Rust workspace tests · 2 Tauri-shell tests (one browser-opening case ignored) · 31 frontend tests
· 0 failing. `./gradlew check`, both Rust workspaces, frontend production build and signed debug APK
and a physical Android 15 run were green on this date.

The figure moved 93.1 → 85.7 → 91.3 → **90.4 %**, and every move was real. The **telemetry
programme** ([`plans/Plan.6.md`](plans/Plan.6.md)) first added ten tasks across seven categories, then
delivered seven of them. The drop to 90.4 % is the **service-directory lane** adding three tasks
([tracker 5](tracker/Task.5.md), [rendezvous 5](rendezvous/Task.5.md),
[network 13](network/Task.13.md)) of which none is finished yet, against a body of work that is
otherwise green. A percentage that only ever rises is a percentage measured against a scope that
quietly moves.

The **wire programme** ([`plans/Plan.7.md`](plans/Plan.7.md)) opened and landed on 2026-07-28:
[network 14](network/Task.14.md), the cross-version protocol refactor. All eight phases are done and
the whole gate is green — the frame is now `NDR2` with canonical TLV infrastructure bodies, in both
languages. **L-86 and L-89 retired 2026-07-28** (issue #97): their stated exit tests are entirely
headless and all green, so they moved on the retire-on-green-exit-test rule. The remaining three
root-cause rows L-87, L-88, L-90 are RETIRING — they are not RETIRED, because the negotiation/
admission behaviour they cover has not yet run as two real processes from different builds on one
network.

**Correction 2026-07-29.** Two of those three rows were RETIRING over code nothing called.
`:peer:structureReport` listed `dev.nodera.protocol.session.Negotiation` among "classes only tests
and benchmarks reference", and that was accurate — the phase-4 handshake had replaced tags 1–4 with
`Hello`/`HelloAck` and left the *same* defect in place, a message family living in the codec and its
tests with no runtime handler. It is now wired into `PeerRuntime` (every announce sends `Hello`, every
`Hello` is answered against the carrier-authenticated identity, the negotiated profile governs both
committee seats and emitted bytes), so L-87 and L-88 are RETIRING on the live mixed-release run they
always named rather than on green tests over unreachable code. Nothing about their status changed;
what changed is that the status is now honest.

The completion figure above is **not** recomputed for it — the weighting behind that number is not
derivable from this table, and inventing one would be worse than a stale figure that says so.

**Correction 2026-07-30, and this one moves a row the wrong way.** The 2026-07-29 audit produced an
inventory of implemented-but-unreached capabilities ([`network/REFACTORING.md`](network/REFACTORING.md)
§ Unwired capabilities). Working through it closed four lanes and settled the rest on evidence — but it
also found the defect shape inside a **retired** register, which is the one place nobody re-checks.
Engine **L-20** ("genesis is a single-signer trust root") was retired on `GenesisRecertification` +
`GenesisApprovalFlow`, whose only construction site is its own IT, while `CertifiedWorldGenesis` — the
record production actually mints and persists — carries one author key, one signature, and no founding
set. Every world on the network is founded on exactly the trust root that row called a limitation. It
is reopened as engine **L-92** (OPEN, exit test demands a production call path), L-20's evidence cell
left untouched as history with an audit note above it. Engine open/retiring rows go 6 → 7.

What closed in the same pass, each verified failing without its fix: the live-join password gate stops
being an unlimited online guessing oracle (`JoinAttemptThrottle` had no caller, and the gate's
challenge is single-use per *connection*, so reconnecting bought unlimited guesses); the NDR2
authorisation table is enforced at dispatch (`MessageType.permits` had one non-test caller, itself
unwired, so a forged `PeerGoodbye` could evict any member); Task 9's forward event-sync gained the
caller network L-30 was citing as evidence; an unsupported wire kind is answered with a `Nack` instead
of dropped in silence; and the worker's replication sweep gives its byte budget back, so a node that
filled up no longer refuses every world the placement policy expects of it for the rest of its life.
The completion figure is again left as it stands, for the same reason.

**Reorganisation 2026-08-05, and it moves the denominator.** The `app` and `mobile` categories are
one category, [`frontend`](frontend/Task.0.md), covering every user-facing surface: the desktop
launcher, the Android companion, and the public website. Mobile's five tasks are renumbered +11
(mobile 1…5 → frontend 12…16); the app's eleven kept their numbers. **No status moved in that
commit** — not a task, not a limitation, not a test count — because a merge that also retires rows
makes it impossible to tell which change the evidence belonged to.

Four tasks open with it: [frontend 17](frontend/Task.17.md) (the launcher redesigned, with a Theme
screen), [18](frontend/Task.18.md) (the website), [19](frontend/Task.19.md) (one tree building the
desktop app, the site and — through the Rust core and the worker rather than through React — the
Android app), and [20](frontend/Task.20.md) (the site published as a container image the VPS pulls,
replacing an SSH push). The task total goes 76 → 80 and none of the four is finished, so the
completion figure above is again **not** recomputed rather than being quietly improved.

Documentation format, conventions, and the maintenance discipline: [`README.md`](README.md).

---

## 1. Where the build stands

The engine, the network stack, and both Rust services are **proven Minecraft-free**. What remains is
concentrated in two places:

1. **The live lane** ([`minecraft/Task.2.md`](minecraft/Task.2.md)) — wiring the proven headless
   stacks to a real `ServerLevel`. This is the largest single remaining deliverable and it gates the
   live acceptance of almost every other category.
2. **The parity program** ([`engine/Task.8.md`](engine/Task.8.md) …
   [`engine/Task.12.md`](engine/Task.12.md)) — entities, redstone, environment, mobs, and the player
   lane joining the validated lane, which burns the limitation registers down to empty.

Already proven live, on real clients: world sharing through the tracker and rendezvous; a second
client joining; a world surviving its host being killed and being re-hosted by the joiner from the
network in ~3 s; the entity lane active on 12–13 regions across member nodes; per-player FOV region
ownership with forwarded actions and quorum commits; validated pickup delivered exactly once.

| Category | Tasks | Done | In progress | Blocked | Charter |
|---|---:|---:|---:|---:|---|
| [Engine](engine/Task.0.md) | 12 | 7 | 5 | 0 | Deterministic simulation + committee validation |
| [Network](network/Task.0.md) | 15 | 12 | 3 | 0 | Wire, transports, runtime, storage, torrent plane, telemetry, measurement |
| [Tracker](tracker/Task.0.md) | 6 | 5 | 1 | 0 | Always-on discovery service |
| [Rendezvous](rendezvous/Task.0.md) | 6 | 4 | 1 | 1 | NAT reach: punching + relay fallback |
| [Minecraft](minecraft/Task.0.md) | 11 | 5 | 5 | 0 | The NeoForge mod — the playable product |
| [Worker](peer/Task.0.md) | 8 | 6 | 2 | 0 | The always-on headless peer |
| [Frontend](frontend/Task.0.md) | 20 | 10 | 9 | 1 | Every user-facing surface: desktop launcher, Android companion, website |
| [Testing](testing/Task.0.md) | 1 | 1 | 0 | 0 | The test tooling: harness, scenarios, benchmarks, structural report |
| [Telemetry](telemetry/Task.0.md) | 3 | 1 | 1 | 0 | Consented measurement: ingest + Big Data plane |
| **Total** | **80** | **49** | **27** | **2** | |

Note (2026-07-28 doc sweep): the task totals now reflect every `Task.<n>.md` file on disk (the previous
table undercounted tracker/rendezvous/minecraft/worker/app/mobile — the last two are now one
`frontend` category). The completion figure in §1's
header is **not** recomputed here — its weighting is not derivable from this table, and a raw
done/total (49/75) would mix not-started tasks into the denominator in a way the historical figure
never did.

The `server` category ([`server/Task.0.md`](server/Task.0.md), 10 tasks, 0 done) is scoped in
[`plans/Plan.5.md`](plans/Plan.5.md) and is **excluded from this table and from the completion
figure** until its first task starts — counting an unstarted programme would move the denominator
without moving the work.

---

## 2. Task index (all categories)

Status values are exactly those in each task file's header: ✅ COMPLETED · 🚧 IN PROGRESS ·
⏳ BLOCKED · ⬜ NOT STARTED.

### Engine — [`docs/engine/`](engine/Task.0.md)

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](engine/Task.1.md) | Domain types, crypto, canonical encoding | ✅ | — |
| [2](engine/Task.2.md) | Deterministic region engine | ✅ | engine 1 |
| [3](engine/Task.3.md) | Shadow validation | ✅ headless | engine 2, network 1 |
| [4](engine/Task.4.md) | Coordinator: leases, epochs, propose→verify→commit | ✅ headless | engine 3 |
| [5](engine/Task.5.md) | Committee validation — the MVP gate | ✅ headless | engine 4 |
| [6](engine/Task.6.md) | Server-fallback lane + cross-region router | ✅ headless | engine 5, engine 7 |
| [7](engine/Task.7.md) | Interference guard, chunk lifecycle, delegability, mod compatibility | ✅ headless | engine 4 |
| [8](engine/Task.8.md) | Entity & mob lane | 🚧 | engine 7, network 3, minecraft 2 |
| [9](engine/Task.9.md) | Validated redstone + contraption migration | 🚧 | engine 6, engine 7 |
| [10](engine/Task.10.md) | Environment lane: random ticks, fluids, fire, gravity, lighting | 🚧 | engine 8, engine 9 |
| [11](engine/Task.11.md) | Deterministic entity simulation: mob AI, spawning, projectiles | 🚧 | engine 8, engine 10 |
| [12](engine/Task.12.md) | Player lane & trustless closure | 🚧 | engine 11, network 2, minecraft 2 |

### Network — [`docs/network/`](network/Task.0.md)

Programme plan (task 14): [`plans/Plan.7.md`](plans/Plan.7.md).

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](network/Task.1.md) | Wire protocol, transport seam, chunked streams, handshake | ✅ | engine 1 |
| [2](network/Task.2.md) | Peer runtime: membership, gateway election, migration | 🚧 | network 1, engine 5, rendezvous 3 |
| [3](network/Task.3.md) | Event-sourced + durable storage | ✅ | network 1 |
| [4](network/Task.4.md) | Torrent distribution data plane | ✅ | network 3, engine 2 |
| [5](network/Task.5.md) | Discovery, multi-bootstrap, persistent identity | ✅ | network 2 |
| [6](network/Task.6.md) | Archive placement, replication, repair | ✅ | network 4, network 5 |
| [7](network/Task.7.md) | Reliability, storage quotas, 24 h retention | ✅ | network 6 |
| [8](network/Task.8.md) | Per-world content encryption | ✅ | network 4 |
| [9](network/Task.9.md) | Crash safety + active-player stream | ✅ | network 4, network 6, network 7 |
| [10](network/Task.10.md) | Tick-lag / TPS metric + low-TPS region handoff | ✅ | engine 5, network 7 |
| [11](network/Task.11.md) | Telemetry core | ✅ | network 1 |
| [12](network/Task.12.md) | Telemetry emitter core | ✅ | network 11, telemetry 1 |
| [13](network/Task.13.md) | Measured service selection on the peer | 🚧 | tracker 5, rendezvous 5, network 2 |
| [14](network/Task.14.md) | Cross-version wire protocol ([`plans/Plan.7.md`](plans/Plan.7.md)) | 🚧 | network 1, network 2, tracker 5, rendezvous 5 |
| [15](network/Task.15.md) | Structural benchmarking + structural code report | ✅ | network 2, network 4, network 5, worker 1 |

### Tracker — [`docs/tracker/`](tracker/Task.0.md)

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](tracker/Task.1.md) | The `nodera-tracker` service binary | ✅ | network 1 |
| [2](tracker/Task.2.md) | The Java client: announce family + `TrackerClient` | ✅ | tracker 1, network 5 |
| [3](tracker/Task.3.md) | Operations hardening | 🚧 | tracker 1 |
| [4](tracker/Task.4.md) | Service telemetry | ✅ | tracker 1, telemetry 1 |
| [5](tracker/Task.5.md) | Service directory + scoring plane | ✅ | tracker 1, tracker 2, network 1 |
| [6](tracker/Task.6.md) | A published image, and a tracker anyone can run | ✅ | tracker 1, tracker 5 |

### Rendezvous — [`docs/rendezvous/`](rendezvous/Task.0.md)

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](rendezvous/Task.1.md) | The `nodera-rendezvous` service binary | ✅ | network 1 |
| [2](rendezvous/Task.2.md) | The Java rendezvous transport | ✅ | rendezvous 1, network 2 |
| [3](rendezvous/Task.3.md) | Live cross-internet proof | ⏳ | network 2, minecraft 1 |
| [4](rendezvous/Task.4.md) | Service telemetry + NAT-pair statistics | ✅ | rendezvous 1, telemetry 1 |
| [5](rendezvous/Task.5.md) | Discoverable, drainable, self-updating | 🚧 | rendezvous 1, rendezvous 2, tracker 5 |
| [6](rendezvous/Task.6.md) | A published image, and a relay anyone can run | ✅ | rendezvous 1, rendezvous 5 |

### Minecraft — [`docs/minecraft/`](minecraft/Task.0.md)

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](minecraft/Task.1.md) | Mod skeleton, build conventions, run harness | ✅ | — |
| [2](minecraft/Task.2.md) | The live validation lane | 🚧 | minecraft 1; engine 3–7, network 1–10 |
| [3](minecraft/Task.3.md) | Diagnostics HUD + command tree | ✅ | network 11, minecraft 1 |
| [4](minecraft/Task.4.md) | Multiplayer + share GUI | 🚧 | minecraft 1, tracker 2, rendezvous 2, worker 2 |
| [5](minecraft/Task.5.md) | Decentralized host lane | 🚧 | network 3, network 8, rendezvous 2, worker 3 |
| [6](minecraft/Task.6.md) | World identity + permissions (mod half) | 🚧 | worker 2, worker 3, minecraft 1 |
| [7](minecraft/Task.7.md) | Companion presence gate | ✅ | worker 1 |
| [8](minecraft/Task.8.md) | In-game telemetry + consent mirror | ✅ headless | worker 5, network 12, minecraft 7 |
| [9](minecraft/Task.9.md) | Profiling lane — the spark profiler | ✅ | minecraft 1 |
| [10](minecraft/Task.10.md) | A world is shown only when it can be played | 🚧 | minecraft 6, minecraft 7, worker 8 |
| [11](minecraft/Task.11.md) | The mod's GUI, rebuilt on the vanilla layout API | ⬜ | minecraft 10 |

### Peer — [`docs/peer/`](peer/Task.0.md)

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](peer/Task.1.md) | Boot + presence endpoint | ✅ | network 2, network 5 |
| [2](peer/Task.2.md) | Control protocol v2 + live telemetry | ✅ | worker 1, network 11, network 3 |
| [3](peer/Task.3.md) | Host/join delegation + world seeding | 🚧 | minecraft 5, network 4, tracker 2, rendezvous 2 |
| [4](peer/Task.4.md) | Out-of-game committee validation | ✅ headless | engine 5, network 2 |
| [5](peer/Task.5.md) | Telemetry emitter + consent record | ✅ | network 12, worker 2, telemetry 1 |
| [6](peer/Task.6.md) | World ownership + durable world registry | ✅ | worker 2, worker 3, network 3 |
| [7](peer/Task.7.md) | The LAN lane — playing without a mod | ✅ | worker 2, worker 6, tracker 2 |
| [8](peer/Task.8.md) | One world, one identity | 🚧 all 11 deliverables closed; open on live rows | worker 3, minecraft 6 |

### Frontend — [`docs/frontend/`](frontend/Task.0.md)

The `app` and `mobile` categories merged into this one on 2026-08-05. The app tasks kept their
numbers; the mobile tasks shifted by eleven — **mobile 1…5 are frontend 12…16**. A "mobile 3" in an
older issue or commit message is [frontend 14](frontend/Task.14.md).

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](frontend/Task.1.md) | Tauri scaffold + worker supervisor | ✅ | worker 1 |
| [2](frontend/Task.2.md) | Live metrics dashboard | ✅ | worker 2 |
| [3](frontend/Task.3.md) | Per-OS packaging + CI | 🚧 | frontend 1 |
| [4](frontend/Task.4.md) | End-to-end acceptance + cross-machine continuity | ⏳ | worker 3, minecraft 1 |
| [5](frontend/Task.5.md) | Telemetry consent: first-run modal + Privacy screen | 🚧 | worker 5, frontend 2 |
| [6](frontend/Task.6.md) | Dashboard API + live worker link | ✅ | frontend 2, worker 2, worker 6 |
| [7](frontend/Task.7.md) | The client becomes the way in | ✅ | frontend 6, worker 7 |
| [8](frontend/Task.8.md) | Screen redesign: one subject per screen | ✅ | frontend 6, frontend 7 |
| [9](frontend/Task.9.md) | Tracker stores | ✅ | frontend 8, tracker 6, network 13 |
| [10](frontend/Task.10.md) | Practical screens, honest numbers | 🚧 | frontend 8, frontend 9, worker 2 |
| [11](frontend/Task.11.md) | A launcher, not a dashboard | 🚧 | frontend 9, frontend 10 |
| [12](frontend/Task.12.md) | The Android build: toolchain, APK, dex floor | ✅ | frontend 1, worker 1 |
| [13](frontend/Task.13.md) | The interface: Material You, and what a phone may be asked | ✅ | frontend 12, frontend 6 |
| [14](frontend/Task.14.md) | The phone in the mesh, and how it is proven | ✅ | frontend 12, worker 3, tracker 2 |
| [15](frontend/Task.15.md) | Settings the app can keep; worker verbs it never asks for | ✅ | frontend 13, frontend 14, frontend 7 |
| [16](frontend/Task.16.md) | The phone reaches the network it was told to | 🚧 | frontend 14, frontend 9, worker 2 |
| [17](frontend/Task.17.md) | A launcher, redesigned | 🚧 | frontend 11 |
| [18](frontend/Task.18.md) | The website | 🚧 | frontend 9 |
| [19](frontend/Task.19.md) | One codebase, three exports | 🚧 | frontend 17, frontend 18 |
| [20](frontend/Task.20.md) | Shipping it | 🚧 | frontend 18, frontend 19 |

### Testing — [`docs/testing/`](testing/Task.0.md)

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](testing/Task.1.md) | One tool: shell suites become Java scenarios | ✅ | worker 1, minecraft 5, server 1, network 15 |

### Telemetry — [`docs/telemetry/`](telemetry/Task.0.md)

Programme plan: [`plans/Plan.6.md`](plans/Plan.6.md).

| Task | Title | Status | Depends on |
|---|---|---|---|
| [1](telemetry/Task.1.md) | The `nodera-telemetry` ingest service | ✅ | network 1 |
| [2](telemetry/Task.2.md) | The Big Data plane: bus, warehouse, retention | 🚧 | telemetry 1 |
| [3](telemetry/Task.3.md) | Analysis, dashboards, alerting, transparency report | ⬜ | telemetry 2, worker 5 |

---

## 3. Delivery order (dependency-driven waves)

Waves are sequential; items inside a wave are parallelizable.

```
engine ──► network ──►┬─► tracker ──┐
                      ├─► rendezvous┼─► minecraft ◄── worker ◄── frontend
                      └─────────────┘
        (minecraft delivers the LIVE halves of engine and network;
         worker runs network's peer runtime out-of-game; frontend supervises worker)
```

| Wave | Work | Why here |
|---|---|---|
| **0 — now, parallel** | [minecraft 1](minecraft/Task.1.md) Xvfb `runClient` harness in CI · [tracker 3](tracker/Task.3.md) ops hardening · [frontend 3](frontend/Task.3.md) packaging + CI | Each is small, independent, and unblocks a batch of acceptance evidence downstream |
| **1 — the live gate** | [minecraft 2](minecraft/Task.2.md) live validation lane | The single biggest remaining lane; capture mixins, `ServerLevel` applier, chunk tickets, live shadow → coordinator → committee → fallback wiring. Almost every ⏳ elsewhere waits on it |
| **2 — GUI-deferred pool** | [minecraft 3](minecraft/Task.3.md) surface pass · [minecraft 4](minecraft/Task.4.md) live feeds + join flow · [minecraft 5](minecraft/Task.5.md) re-manifest + live encryption · [minecraft 6](minecraft/Task.6.md) world-list mixin + grant enforcement | One GUI environment unlocks all four at once — do them as one batch, not four visits |
| **3 — network completion** | [network 2](network/Task.2.md) gateway migration end-to-end · [rendezvous 3](rendezvous/Task.3.md) cross-internet soak · [worker 3](peer/Task.3.md) seeding/announce delegation · [frontend 4](frontend/Task.4.md) cross-machine continuity | All four need a live/NAT environment; they share the same harness wave 1 produces |
| **3.5 — telemetry emitters** ✅ | [network 12](network/Task.12.md) · [worker 5](peer/Task.5.md) · [minecraft 8](minecraft/Task.8.md) · [tracker 4](tracker/Task.4.md) · [rendezvous 4](rendezvous/Task.4.md) landed; [frontend 5](frontend/Task.5.md) all but a component test | Done in one pass, proven end to end by the `telemetry` scenario (`scripts/nodera-test.sh run telemetry`). What remains is not code: [telemetry 3](telemetry/Task.3.md) needs a **population** that has opted in before a dashboard can answer anything |
| **4 — parity program** | [engine 8](engine/Task.8.md) → [engine 9](engine/Task.9.md) → [engine 10](engine/Task.10.md) → [engine 11](engine/Task.11.md) → [engine 12](engine/Task.12.md) | Burns every category's `LIMITATIONS.md` to empty; engine 12 closes the ledger |

**Biggest schedule lever:** wave 1. Nine tasks across five categories carry a "live evidence
pending" clause that the same harness closes.

---

## 4. Priority (most important first)

Importance = how much it unblocks × how directly it proves the central bet × player-visible value.

| Rank | Task | Why |
|---|---|---|
| 1 | [minecraft 1](minecraft/Task.1.md) — CI run harness | Hours of work; converts every "proven live once by hand" claim into a repeatable gate |
| 2 | [minecraft 2](minecraft/Task.2.md) — live validation lane | The determinism bet on real servers; the MVP gate's live half; unblocks waves 2–3 |
| 3 | [engine 8](engine/Task.8.md) — entity lane completion | Headless and durable exits are green; live pickup/mob/pearl evidence makes the lane real |
| 4 | [worker 3](peer/Task.3.md) — delegation + seeding | Makes "the world outlives its host" a property of the node, not of a lucky timing window |
| 5 | [network 2](network/Task.2.md) — gateway migration | Session continuity under churn on direct, punched, and relayed paths |
| 6 | [minecraft 4](minecraft/Task.4.md) — multiplayer GUI live feeds | The feature players actually see: worlds, health, piece map, join |
| 7 | [engine 9](engine/Task.9.md) — validated redstone | High player value; palette v4 landed, contraption migration remains |
| 8 | [frontend 3](frontend/Task.3.md)/[frontend 4](frontend/Task.4.md) — packaging + continuity CI | The app is what every player installs; unpackaged is unshipped |
| 9 | [engine 10](engine/Task.10.md) — environment lane | Living worlds in delegated regions (grass, fluids, fire, light) |
| 10 | [engine 11](engine/Task.11.md) — deterministic mobs | Retires the ghost lane species by species |
| 11 | [rendezvous 3](rendezvous/Task.3.md) — cross-internet numbers | Confirms NAT reach at real-world scale |
| 12 | [engine 12](engine/Task.12.md) — trustless closure | Endgame: prediction/rollback, BFT membership, empties the ledger |
| 13 | [tracker 3](tracker/Task.3.md) — ops hardening | Needed by community operators, not by correctness |

---

## 5. Difficulty (hardest first, remaining scope only)

Difficulty = technical risk × breadth × novelty.

| Rank | Task | What makes it hard |
|---|---|---|
| 1 | [engine 12](engine/Task.12.md) | Several research-grade problems at once: BFT open membership, validated movement with prediction + rollback, deterministic worldgen, zero-reconnect local-replica view |
| 2 | [engine 11](engine/Task.11.md) | Deterministic mob AI and spawning inside vanilla's rate envelope; fixed-point pathfinding; per-species retirement without a visible behaviour cliff |
| 3 | [engine 10](engine/Task.10.md) | Deterministic lighting is notoriously hard; fluid/fire/random-tick parity envelopes against vanilla |
| 4 | [minecraft 2](minecraft/Task.2.md) | Real-world divergence hunting; every foreign write source must reach one mixin choke point, cheaply |
| 5 | [engine 9](engine/Task.9.md) | Redstone semantic fidelity plus contraption ownership migration across region borders |
| 6 | [engine 8](engine/Task.8.md) | Entity state in roots, ghost lanes, cross-region transfer with no dupes and no loss |
| 7 | [network 2](network/Task.2.md) | Migration UX under failure: freeze, reconnect, exactly-once resubmit across three transport paths |
| 8 | [worker 3](peer/Task.3.md) | Splitting "player session" from "node session" without a window where a world is announced but unserved |
| 9 | [frontend 4](frontend/Task.4.md) | Cross-machine acceptance in CI: two machines, an installer, a gate, and a timing-sensitive continuity assertion |
| 10 | [minecraft 1](minecraft/Task.1.md) | Headless-display Minecraft in CI is fiddly, not deep |

---

## 6. Limitation registers

Every category owns its limitations. A task is only done when its register rows move.

| Category | Register | Open/retiring rows |
|---|---|---|
| Engine | [`engine/LIMITATIONS.md`](engine/LIMITATIONS.md) | L-1, L-2, L-7, L-12, L-16, L-17, L-50 (L-51 and L-52 both retired 2026-07-28) |
| Network | [`network/LIMITATIONS.md`](network/LIMITATIONS.md) | OPEN: L-30, L-85 · RETIRING: L-33, L-76, L-84, L-87, L-88, L-90, L-91 (L-86 + L-89 retired 2026-07-28, issue #97) |
| Tracker | [`tracker/LIMITATIONS.md`](tracker/LIMITATIONS.md) | L-81 (RETIRING — release signing key outstanding, a credential step not code) |
| Rendezvous | [`rendezvous/LIMITATIONS.md`](rendezvous/LIMITATIONS.md) | L-83 (OPEN — drain-resume proof missing; mechanism believed complete) |
| Minecraft | [`minecraft/LIMITATIONS.md`](minecraft/LIMITATIONS.md) | L-43, L-46, L-49, L-50, L-80 · MC-JOIN-1…6 · MC-GUI-1…5 |
| Worker | [`peer/LIMITATIONS.md`](peer/LIMITATIONS.md) | RETIRING: W-FETCH-1, W-REPL-1, W-DUP-3 · OPEN: W-DUP-1, W-DUP-2, W-DUP-4 |
| Frontend | [`frontend/LIMITATIONS.md`](frontend/LIMITATIONS.md) | OPEN: L-47, L-56, L-91, L-92, L-93, M-1, M-3, M-4 · RETIRING: L-94, M-NET-2 · envelope: A-9 |
| Telemetry | [`telemetry/LIMITATIONS.md`](telemetry/LIMITATIONS.md) | L-73, L-74, L-75 (L-72 retired → [`LIMITATIONS.fixed.md`](telemetry/LIMITATIONS.fixed.md)) |

**Envelope constraints** (immovable facts of physics/platform, engineered around until players
cannot observe them) are listed once, in each owning category's register §A. They never burn down;
they are satisfied when their hiding mechanism ships.

---

## 7. Cross-cutting notes

- **Standing harness.** The Nodera debugger (headless integration scenarios over
  `LoopbackTransport` and real TCP) is not a task; every category adds scenarios to it. Its scope
  lives in its GitHub issue and in [`../AGENTS.md`](../AGENTS.md).
- **Three-lane staffing.** Lane A (Minecraft-facing): minecraft 1 → 2 → 4/5/6. Lane B (pure Java):
  engine 8 → 9 → 10 → 11 → 12. Lane C (Rust services + worker + app): tracker 3, rendezvous 3,
  worker 3, frontend 3/4. Lanes A and C join at wave 3; lane B joins when the live lane can host it.
- **Issue lookup.** Category task numbers are **not** GitHub issue numbers, and never were. Find an
  issue by title. Each task file names the issue it belongs to where one exists.
- **Prior art.** The engine's regionised-ticking and ownership-takeover designs draw on Folia and
  MultiPaper; the studies live in [`minecraft/folia/`](minecraft/folia/) and
  [`minecraft/MultiPaper/`](minecraft/MultiPaper/), with the upstream sources under
  [`minecraft/upstream/`](minecraft/upstream/). Neither project validates anything — committee
  re-execution plus quorum certificates is Nodera's novel layer.
- **Programme plans.** Multi-task programmes (the architecture plan, the API unification, the
  limitation burn-down, the operator-permission programme, the Paper/Folia endpoint, the telemetry
  programme) live in [`plans/`](plans/) and are
  referenced from the task files they produced.
