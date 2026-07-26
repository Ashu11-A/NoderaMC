# Worker — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the worker category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old note. -->

**Category:** worker · **Last audit:** 2026-07-25 · Tasks completed: **4 / 5**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Boot + presence endpoint | ✅ COMPLETED | Verified live: boots, becomes gateway, answers the probe |
| [2](Task.2.md) | Control protocol v2 + telemetry | ✅ COMPLETED | Real bytes/peers/worlds; verbs grew additively to 10+ |
| [3](Task.3.md) | Host/join delegation + seeding | 🚧 IN PROGRESS | Archive seeding + grant gossip + the announce heartbeat's live holdings + validated-lane region-piece seeding (`NODERA-SEED-REGION`, `RegionSeedSpool`) landed; L-41 retired 2026-07-26. Deliverable 9 — rendezvous registration persisting across game sessions — has no evidence yet |
| [4](Task.4.md) | Out-of-game validation | ✅ COMPLETED (headless) | L-48 retired; live region feed rides the mod |
| [5](Task.5.md) | Telemetry emitter | ✅ COMPLETED | `TelemetryVerbIT` + the e2e outage lane; **L-77 RETIRED** |

---

## 2. Milestone notes (newest first)

### 2026-07-25 — The worker becomes the node's emitter, and the outage lane is green

`WorkerTelemetryService` + `NODERA-TELEMETRY GET|SET|EVENT` landed, with `TelemetryVerbIT` driving
the real control socket and `scripts/e2e-telemetry.sh` driving the real worker distribution against
the real collector binary.

The evidence that matters is **T6**, the outage lane. With the collector killed mid-run, the suite
compares the worker's whole `NODERA-STATE` answer field by field against the one taken before, and
requires everything except the telemetry block and the clock to be identical — `PROBE` and
`IDENTITY` keep answering, and the send failure surfaces in the telemetry block rather than being
swallowed. That is `Plan.6` D10 turned into a test instead of a promise, and it retires **L-77**.

One decision recorded because it will look conservative later: **absent consent is denied consent.**
A worker started by hand, by a container, or by `scripts/dev.sh` has been told nothing, so it sends
nothing — and T1 proves it by letting two collection windows pass and asserting the collector's
spool is still empty.

### 2026-07-25 — The worker becomes the node's only telemetry emitter

[Task 5](Task.5.md) is scoped, and the decision behind it is the one worth recording: **the consent
record lives with the worker, not with the app or the mod.**

The alternative — each surface emitting its own telemetry — fails three ways at once. Three consent
checks can disagree; the mod's and the app's lifetimes are both shorter than the node's, so a
session's closing events would be lost exactly when they are most interesting; and the process most
likely to be modified by third parties would be the one holding a network path to the telemetry
service. The worker outlives the game, already owns the loopback control endpoint, and is where every
measurement already lives.

Consequence, recorded so it is not rediscovered later: **absent consent is denied consent.** A worker
started by hand, by a container, or by `scripts/dev.sh` has been told nothing and therefore sends
nothing. Registered **L-77**.

### 2026-07-25 — Workers become first-class mesh members

The reported defect was that validators existed only while their player was connected — a region's
committee was whatever players happened to be standing in it, so a logout could collapse it to a
committee of one.

`ResidentQuorumIT` is the exit proof: with two standing workers plus player nodes, a player's logout
leaves the committee at quorum size and it **keeps committing**, with the certificate co-signed by a
peer that has no Minecraft process. The counterfactual — no residents ⇒ committee of one — is asserted
alongside, so the row cannot silently regress to the degraded shape it was reported in.

Grant gossip landed as a live worker consumer in the same period: a co-hosting peer's permission set
had been author-local, so an operator promotion or a ban simply did not exist for the rest of the
network. Every receiver re-verifies the grant against the world's author key — the transport carries
the decision without ever being trusted with it (`GrantGossipIT`, 6).

### 2026-07-23 — World continuity: the worker keeps the world alive

The acceptance was blunt: *the host disconnects — the world must not die with them.*

`WorldArchiveService` seeds the canonical save archive through `SEED`, rides its manifest holdings on
every tracker announce, answers manifest queries from other peers, fetches any world's archive from
the swarm through `ARCHIVE`, and reports real maintained pieces and bytes in `STATE`.

`WorldContinuityIT` proves the whole chain over the **real** tracker and rendezvous binaries, driving
the control verbs exactly as the mod drives them: share → listed → P2P fetch byte-exact → host game
closed → **host worker killed** → the second peer still reproduces the world.

`CompanionCrashSurvivalIT` proves the crash half with real OS processes: the actual worker
distribution runs as a separate daemon, a co-located stand-in game process is SIGKILLed so no shutdown
hook runs, and the daemon keeps answering control verbs with every seeded piece still maintained.

### 2026-07-21 — Out-of-game validation; L-48 RETIRED

Before this, the entire validation stack was runtime-unreferenced outside the mod and the
`simulationmsg` wire family had no live consumer — the code was tested and unused, which is a weaker
position than it looks.

`WorkerValidationService` gave it a host. `WorkerQuorumValidationIT` shows three companion-only
workers forming a committee over the transport, converging on the byte-identical reference-engine
root, persisting co-signed certificates, and failing over to epoch+1 after primary loss. The fallback
lane — also previously orphaned — executes unassigned-region actions through the server lane, and the
soak ratio rides the worker's telemetry.

### 2026-07-21 — Control protocol v2 and live data

The verb table went from a presence probe to a real control plane: `STATE`, `IDENTITY`,
`HOST`/`JOIN`/`STOP`, `PASSWORD`, `STATUS`, `WORLDID`. The dashboard and the multiplayer tabs stopped
showing placeholder zeros, and the "no trackers/rendezvous configured" bug — which was a missing feed,
not a missing configuration — was fixed.

The worker became the **world author**: it holds the signing key, mints signed world identities, and
enforces author-only re-key, which is what turns "only the creator can change the password" into a
cryptographic statement.

### 2026-07-20 — The worker boots

`HeadlessPeerMain` runs a full `PeerRuntime` with persistent identity and serves the loopback control
endpoint the mod's gate probes. Verified live: it boots, becomes gateway, and answers
`NODERA-PROBE 1` → `NODERA-OK`. The decision that shaped everything after it — **Option B**, reuse the
tested Java peer rather than writing a Rust node — was locked here, because the single-engine
determinism rule forbids a second implementation that could re-execute regions.
