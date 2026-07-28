# Network — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the network category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test or IT name), then reconcile ../ROADMAP.md §2 and the root README bar. Never
     rewrite an old note — append a new one. -->

**Category:** network · **Last audit:** 2026-07-27 · Tasks completed: **11 / 13**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Wire protocol, transports, handshake | ✅ COMPLETED | Tags through 60; authenticated accept handshake (L-53 retired) |
| [2](Task.2.md) | Peer runtime, membership, gateway | 🚧 IN PROGRESS | Election + continuity + certified committee changes ✅; migration end-to-end remains |
| [3](Task.3.md) | Event-sourced + durable storage | ✅ COMPLETED | RocksDB tier, forced-kill recovery, certified forward sync |
| [4](Task.4.md) | Torrent data plane | ✅ COMPLETED | Production consumer live (world-continuity lane); L-33 render half remains |
| [5](Task.5.md) | Discovery + multi-bootstrap + identity | ✅ COMPLETED | Serving role moved to the tracker service |
| [6](Task.6.md) | Placement, replication, repair | ✅ COMPLETED | `WorldReplicationService` closed the "announced but never replicated" gap |
| [7](Task.7.md) | Reliability, quotas, retention | ✅ COMPLETED | Countdown network-visible on every announce |
| [8](Task.8.md) | Per-world content encryption | ✅ COMPLETED | Re-key now actually revokes (L-55) |
| [9](Task.9.md) | Crash safety + active-player stream | ✅ COMPLETED | Continuous streaming + bounded final flush + freshness guard |
| [10](Task.10.md) | Tick-lag + low-TPS handoff | ✅ COMPLETED | Gained a live call site 2026-07-25 |
| [11](Task.11.md) | Telemetry core | ✅ COMPLETED | Honest CERTIFIED/PENDING/SOLO region status |
| [12](Task.12.md) | Telemetry emitter core | ✅ COMPLETED | 21 tests + the cross-language registry mirror; L-76 RETIRING |
| [13](Task.13.md) | Measured service selection | 🚧 IN PROGRESS | 30 new Java tests; the mod's own transport still reads a static list (L-84) |

---

## 2. Milestone notes (newest first)

### 2026-07-27 — The route the tracker had already sent

The instrumentation added in the previous pass paid for itself on the first run. The recovery
screen stopped saying "Migrating world…" for two minutes and said, in two seconds:

```
Recovery failed: no routable seeder for world d454b2264b84
```

That is a routing failure, not a throughput one — which settles a question two earlier passes had
guessed at and got wrong. `ArchiveFetchThroughputTest` had already shown the lane moves 18 MB in
1.9 s in-process; the pieces were never slow, they were never requested.

`resolveSeeders` asks the tracker twice: a seeder query, then a routes query. The seeder query's
answer carries `PeerEntry`, and a `PeerEntry` **has a `route`** — which this code dropped on the
floor, keeping only the node id, and then depended on the second query to supply the very thing it
had just discarded. Any tracker that does not answer the routes query — an older build, a dropped
datagram — leaves every seeder known and unreachable. The host had announced to that same tracker
three seconds earlier.

Fixed: the route on the entry is kept, both sources go through one `rememberRoute` that rejects
`mc/` game-endpoint claims, and the lookup logs what it found
(`N seeder(s), M routable, over K tracker(s)`) so the next failure of this shape names itself.

Also in this pass, from the same screenshot — both defects mine, introduced two passes earlier:

- the failure text asserted "this world is password protected" for a world shared with
  `encryption=false`. `CompanionClient.fetchArchive` now returns the worker's own reason instead of
  discarding it, and continuity prints that verbatim. A cause the client cannot observe is a cause
  it must not name — this was the second wrong guess printed in that exact spot;
- the message was drawn with `drawCenteredString`, which does not wrap, so it ran off both edges
  with its first and last words cut off. It now wraps to `width - 80`. This is MC-GUI-2, written
  into new code after being catalogued.

And the fetch deadline is now a **stall** deadline rather than a wall clock. A flat budget asks
"has this taken too long?", which is wrong in both directions: it kills a large world that is
transferring perfectly well, and it lets a download that is receiving nothing look busy for two
minutes. Progress is the question; a fetch that keeps moving keeps going.

### 2026-07-27 — The joiner that could not speak to the peer it was joining

Third live reproduction of a stuck "Migrating world…", and the first one whose cause is in the
transport rather than the archive lane. The host quit to the title screen — the ordinary continuity
case — and the other player's client sat on the recovery screen while both workers were online and
the seeder held every piece (208 pieces / 51.4 MiB on the dashboard).

The joiner's log carried this sixteen times:

```
Exception in thread "nodera-peer-state-4cf0c965" java.lang.NullPointerException:
    rendezvous transport requires a nodeId
  at RendezvousPeerTransport.dispatch(RendezvousPeerTransport.java:255)
  at PeerRuntime.sendTo(PeerRuntime.java:792)
  at PeerRuntime.onHeartbeatTick(PeerRuntime.java:610)
```

The address is the **bootstrap**: a route with nobody's name on it, because a joiner dials an
address before it can know who is listening — learning that is what the `PeerJoin` it is trying to
send is for. `dispatch` rejected it at line one, above the code that has honoured a caller-supplied
route since L-30. So the joiner could never announce itself, its membership stayed `{self}`, and
the download had no mesh behind it.

Three fixes, at three layers, because one alone would have left the shape intact:

- an unnamed address with a dialable route goes over the direct transport, and one without a route
  fails as a `TransportException` — the type every caller is written to tolerate, never an unchecked
  throw that unwinds a scheduler thread;
- `PeerRuntime.sendTo` absorbs unchecked failures as well as checked ones, honouring the contract it
  already documented;
- `onHeartbeatTick` isolates the bootstrap re-announce, so the `SessionKeepAlive` broadcast at the
  bottom cannot be skipped by anything above it.

Evidence: `BootstrapAddressHasNoNodeIdTest` (3) and `HeartbeatSurvivesABadBootstrapTest`, both
verified failing with the fixes disabled.

Measured in passing, because the same run looked like a throughput problem and was not:
`ArchiveFetchThroughputTest` moves an 18 MB archive between two services in **1.9 s (~9.8 MB/s)**.
The lane's own pacing is not slow; what was slow was a joiner with no mesh.

### 2026-07-27 — A peer stops being told where its relays are

The rendezvous list was a configuration string, and three things followed from that: adding a rendezvous
to the network reached nobody, losing one was an outage for everyone configured to use it, and no peer
could prefer the relay that actually worked for it. `RendezvousDirectory` replaces it — ask the trackers,
verify every row, probe the candidates, keep the best few, report what was measured.

Two properties are worth naming. **A peer scores what it measured**, because a relay with excellent global
availability that *this* peer cannot reach is useless to this peer; the tracker's aggregate is the
evidence a joining peer has and the tie-break when a peer's own probes cannot separate two relays. And
**several endpoints are selected, all of them used**: registering with several and querying only the first
converts redundancy into a silent single point of failure.

Two sentinels had to be made explicit, and a test rather than review caught the second: `Reachability`
reports `-1` for unreachable, and a score with no reporters carries `rttP95 == 0`. Read naively, both make
an unusable relay the fastest-scoring thing in the directory.

**A real defect fixed on the way through.** `RendezvousPeerTransport`'s accept loop slept 500 ms after a
failed re-reservation and then *returned*, ending the peer's inbound relay path until the process
restarted — so a rendezvous restart was a permanent, invisible outage for anybody who had reserved there.
It now retries across every endpoint with capped backoff, forever. A second one on the app side:
`worker_env` never passed `NODERA_RENDEZVOUS_ENDPOINTS`, so a user's configured relay was silently
ignored; `the_configured_rendezvous_endpoints_reach_the_worker` pins it.

### 2026-07-26 — The node remembers who behaved

`PersistedCoordinatorState` has existed since Task 6 with nowhere to live. The consequence was
quiet enough to survive a year of audits: every restart reset the epoch counter that defends against
stale proposals, and every restart made the node reputation-blind, so a peer that had spent an
evening disagreeing with committed roots came back with a spotless score.

`DurableCoordinatorState` gives it a file beside the action, credit and vote journals. The live
session attaches it on open, checkpoints every 30 s — a crash is the case durability exists for, and
a crash never reaches `close()` — and flushes on close.

Two decisions worth keeping: the file is **local, never consensus** (a node's opinion of its peers
is a view, and two honest nodes may hold different ones — which is why it sits beside the journals
and not in the certified chain), and **damage is recovered from rather than thrown**. Losing this
state costs the node its memory of who behaved, which observing the network rebuilds; refusing to
start would cost it the world. `DurableCoordinatorStateTest` (6) covers both, including the heal:
after a corrupt file is read, the next flush replaces it with a readable one.

### 2026-07-26 — The "known flake" was a missing latch release

`SocketPeerTransportAuthTest` had a documented workaround — run the transport suites with
`--no-parallel --max-workers=2` before assuming a regression — and a CI failure on a telemetry
branch made it worth looking at rather than re-running.

The exception was `auth handshake timed out`, thrown from `SocketPeerTransport.writeFrame`. A sender
in authenticated mode waits for its own read loop to see the remote's challenge and write the signed
hello; the read loop's `finally` closed the socket but **never released that latch**. So a
connection that died mid-handshake left every sender blocked for the full 10 s and then reported a
*timeout* — which sends whoever reads the log looking for a slow peer when the peer is gone. CPU
starvation was not the cause; it was the trigger.

Three changes, and the middle one is the fix:

- teardown releases `authHelloWritten`, so a dead peer costs a sender **4 ms** instead of 10 s;
- a sender distinguishes "connection closed during the auth handshake" from "timed out", because
  the two send a reader to different places;
- the timeout became a named constant at 30 s, which now only has to cover a starved scheduler.

`aPeerThatDiesMidHandshakeFailsTheSenderImmediately` asserts on the clock as well as the exception —
a fail-fast that takes 30 s is the bug wearing a passing test's clothes. The full gate at maximum
parallelism, which is what used to flake, is green.

### 2026-07-25 — The emitter core lands, and consent gates collection

`dev.nodera.telemetry` is in (`TelemetryEmitterTest`, 21). The decision worth recording is the one
the acceptance criterion is written around: **consent gates collection, not transmission.**

`consentDeniedProducesNoEventsAtAll` asserts that a denied node builds no event object at all —
not that it builds them and drops them. The difference is not cosmetic. A design that collects into
a buffer and discards it later is one flag away from shipping data nobody agreed to, and it spends
the CPU cost of measurement on the people who declined.

Two supporting decisions came out of writing it:

- **Bucketing lives in one class.** If each call site coarsened its own values they would drift, and
  two events would disagree about what "large" means. `Buckets` is the only place a measurement
  becomes a statistic, and its boundaries are what the receiver's declared ranges are written against.
- **The install id is regenerated on every grant.** Turning telemetry off and on again produces a
  *new* installation as far as the pipeline is concerned, so a revocation cannot be undone by
  re-granting — nothing the client sends links the two eras.

`TelemetryRegistryMirrorTest` runs `nodera-telemetry --print-schema` and compares it with the Java
registry, the same mechanism the wire-tag mirror uses. It skips when the binary is absent, so the
pure-Java gate stays honest, and `scripts/e2e-telemetry.sh` + the `telemetry` CI job build it first.

### 2026-07-25 — A twelfth task: the meters get a second consumer

[Task 11](Task.11.md) built one snapshot per tick for *this node's own screens*. [Task 12](Task.12.md)
adds the second consumer — a consented, bucketed projection of the same measurements, shipped to the
telemetry plane ([`../plans/Plan.6.md`](../plans/Plan.6.md)).

Worth recording as a boundary rather than a feature: the two consumers are deliberately not the same
code path. The HUD reads exact numbers about the machine it runs on, because that is the machine's
own business; telemetry reads **buckets**, behind a consent gate, because a byte-exact world size is
a fingerprint and a bucket index is a statistic. Sharing the projection between them would have made
the privacy properties depend on which caller happened to be asking.

Registered **L-76**: nothing measures NoderaMC in the wild, so every claim about behaviour on real
machines is currently the authors' own machines plus CI.

### 2026-07-25 — 



### 2026-07-25 — Settings that lied stop lying: L-57 and L-58 RETIRED

Both rows described settings the app exposed but the node did not honour as documented.

**L-57** asked for the download cap's overshoot bound to be *measured*, and measuring it found the
documented claim was false: the gate admitted a request whenever any credit remained and then charged
the whole request, so a multi-piece batch overshot by several pieces while the docs promised one.
`admitRequestBytes` now discounts the request's largest piece and requires the rest to fit — bounding
the overshoot at exactly one piece, while still letting a budget smaller than a single piece make
progress instead of deadlocking the download.

**L-58** was reported as `restart_required`, and that framing hid the real defect: the blobs a node
holds *are* its seeding obligations, so re-pointing the archive directory without moving them would
leave a world listed on the tracker while every piece request missed — worse than refusing the change.
`FsContentStore.relocateTo` moves the content first and re-points the store only after; content
addressing makes it safe. The p2p bind port stays restart-required — an open listener is a different
problem from a movable directory.

### 2026-07-25 — Permission and archive hygiene: L-54 and L-55 RETIRED

Both were "noted" follow-ups that had never been built, and both were quietly security-relevant.

**L-54 — grants now exist on the mesh, not just on the author's disk.** A co-hosting peer's
permission set stayed author-local, so an operator promotion or a ban simply did not exist for the
rest of the network. `WorldGrantGossipService` relays every grant over `WorldGrantGossip` (tag 60).
The transport carries the decision without ever being trusted with it: the grant is opaque bytes and
every receiver re-verifies both the signature and the granter's authority against the world's author
key. `GrantGossipIT` (6) covers the attacks too.

**L-55 — a password re-key now actually revokes the old password.** A re-key appended a new encrypted
manifest and left the previous one seeded, and the superseded blob is still decryptable with the OLD
password — so rotating it revoked nothing. `supersedeOlderVersions` drops every older version from
the manifest table, from holdings, and from the content store itself.

### 2026-07-25 — Mesh population and boundary independence

Three live-play defects shared one root: *a region's committee was whatever players happened to be
standing in it, and nothing on the live lane ever noticed when its primary stopped keeping up.*

1. **Workers hold committee seats through a player's disconnect.** `ResidentQuorumIT` runs the
   scenario — two standing workers plus player nodes — and shows the committee holding at quorum size
   across a logout, still committing, with the certificate co-signed by a peer that has no Minecraft
   process. The counterfactual (no residents ⇒ committee of one) is asserted alongside.
2. **A laggy player no longer wedges its regions for everyone.** The handoff lane existed and was
   tested but had **no live call site**. `forwardLagTickBps` measures the age of the oldest forwarded
   action the primary has not answered — literally what the player at the boundary is waiting on
   (`LiveLagHandoffIT`, 4).
3. **Region status stops reading as a permanent "unsigned".** CERTIFIED / PENDING / **SOLO**: a
   committee of one is a population shortfall that no amount of waiting fixes, and the panel now says
   so (`RegionCertificationTest`, 8).

Two latent faults surfaced en route and were fixed: a `CommitAnnounce` for a **revoked** replica threw
an illegal pipeline transition out of the peer state thread (killing it), and a newer-epoch
`RegionAssigned` re-activated a **live** replica from the genesis snapshot, rewinding it to v0.

### 2026-07-24 — Discovery and telemetry audit remediation

An end-to-end audit of the tracker → rendezvous → worker → mod → companion chain found four
capabilities that were *built and tested but never connected*, each of which made the system quietly
weaker than its own docs claimed.

1. **Direct-first was structurally unreachable.** `RendezvousPeerTransport` advertised only a RELAY
   candidate, so no discovered peer ever had a direct candidate and every byte crossed the relay — the
   exact inversion the reference warns about. It now publishes a HOST candidate from the direct
   transport's listen route and renews at half the TTL instead of silently vanishing from discovery.
2. **Trackers were never a peer-discovery plane.** Session membership came exclusively from one
   bootstrap route plus its gossip. `PeerDiscoveryService` sweeps every tracker and rendezvous and
   introduces this node to each routable peer — merged, never arbitrated.
3. **A shared world's *bytes* were not shared.** Announcing published where a world was; nothing
   replicated it. `WorldReplicationService` runs the placement policy over the tracker directory and
   adopts the worlds this node is placed for.
4. **The piece map had no source.** A `NODERA-PIECES` verb, a parser, and a feed replaced an
   always-empty grid.

Alongside: scheme-aware tracker endpoints with a real UDP surface (bounded against reflection
amplification, silent rather than truncating when an answer would exceed the bound, with a TCP
fallback), and per-peer traffic totals replacing hardcoded zeros.

### 2026-07-23 — Rust cluster complete; world-continuity lane

The standalone tracker and rendezvous services landed, retiring the embedded Java tracker and the
never-built libp2p transport plan. In the same period the world-continuity lane made "the host
disconnects — the world must not die with them" hold end to end: a save is packed into the canonical
`WorldArchive`, filed under the piece plane, seeded to the always-on worker, served to the swarm, and
fetched back by any peer's worker. `WorldContinuityIT` proves the chain over the real service
binaries, including full host-worker death.
