# Network Task 13 — Measured service selection on the peer

<!-- AI-AGENT-INSTRUCTION: This task is the PEER half of the service-directory lane. Two rules are
     load-bearing. (1) A peer verifies every directory row and recomputes every composite itself — a
     tracker's answer is a hint, and code that trusts `compositePermille` is a bug. (2) The reconnect
     paths must NEVER give up: the defect this task fixes is an accept loop that returned after one
     failed reservation, silently removing a peer's inbound relay path until the process restarted.
     Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** network · **Owns:** L-91 · **Last audit:** 2026-07-28
**Depends on:** [tracker 5](../tracker/Task.5.md), [rendezvous 5](../rendezvous/Task.5.md),
[network 1](Task.1.md), [network 2](Task.2.md)
**Consumed by:** [worker 3](../worker/Task.3.md), [minecraft 5](../minecraft/Task.5.md),
[app 2](../app/Task.2.md)

---

## Goal

A peer stops reading its rendezvous list out of a configuration string. It asks its trackers, verifies
every answer, measures the candidates itself, uses **several** of the best, reports what it measured, and
migrates when one says it is leaving — without a restart and without a gap.

## Status detail

Landed and green (`./gradlew :core:test :transport:test :peer:test`, **579 peer-module tests**;
30 new across three suites).

- `java/peer/.../discovery/ServiceScoreBoard.java` — the peer's own measurement window and the
  selection. 20 tests.
- `java/peer/.../discovery/RendezvousDirectory.java` — the sweep: query, verify, probe, select, report,
  and the drain-notice migration. 11 tests against a real tracker socket.
- `java/peer/.../discovery/TrackerClient.java` — `serviceDirectory` (verifying, merging) and
  `reportServiceScores` (signed).
- `java/transport/.../rendezvous/RendezvousPeerTransport.java` — a live endpoint list, reserve/connect
  failover across every endpoint, unbounded retry with capped backoff, drain-notice listeners.
- `java/transport/.../rendezvous/RelayCircuitClient.java` — reads and **verifies** a drain notice on the
  reservation's own control channel, then keeps waiting for a circuit.
- `java/worker/.../HeadlessPeerMain.java` + `WorldHostingService.java` — the directory on a 60-second
  sweep, and a live rendezvous endpoint list that re-registers every hosted world when it changes.
- `rust/nodera-app/src/daemon.rs` — passes `NODERA_RENDEZVOUS_ENDPOINTS`, which it never did.

The mod half landed on 2026-07-27: `WorkerStateParser.rendezvousRoutes` reads the worker's live
selection out of the `NODERA-STATE` reply the mod already fetches, both transport-composition paths
seed from it, and the announce cadence pushes later changes through `setEndpoints`. No new control
verb and no wire change were needed — the `rendezvous` array was already in the STATE contract, and
reading a field that exists is free where adding one is not.

Remaining: the exit test. It is a **live** suite — two relays, one drained mid-session, the in-game
transport asserted to re-register — and it does not exist yet, so L-91 is RETIRING rather than
RETIRED.

> Register note (2026-07-28): this task's limitation was renumbered from a duplicate **L-84** to
> **L-91**. The other L-84 (task 2, "a joining peer could not send to its own bootstrap") keeps the
> id. See [`LIMITATIONS.md`](LIMITATIONS.md) for the hygiene note.

## Dependencies

- [tracker 5](../tracker/Task.5.md) — the directory to query and the aggregate to read.
- [rendezvous 5](../rendezvous/Task.5.md) — the drain notice this reacts to.
- [network 2](Task.2.md) — the `PeerTransport` seam none of this is allowed to leak through.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `serviceDirectory` — query every tracker, verify every row, merge without arbitrating | ✅ |
| 2 | `ServiceScoreBoard` — bounded probe windows, percentiles, the shared composite | ✅ |
| 3 | Local measurements override the aggregate; the aggregate breaks ties | ✅ |
| 4 | Select several rendezvous, not one | ✅ |
| 5 | Signed score reports back to the trackers | ✅ |
| 6 | Reserve and connect fail over across every endpoint | ✅ |
| 7 | The accept loop retries forever with capped backoff instead of returning | ✅ |
| 8 | A verified drain notice migrates the peer, using the replacement it names | ✅ |
| 9 | Configured endpoints survive as seeds | ✅ |
| 10 | The worker drives the sweep and re-registers on change | ✅ |
| 11 | The mod's transport reads the worker's selection | ✅ code; ⬜ live exit test (L-91) |

## Design

**Everything a tracker says is a hint, and the code says so.** `serviceDirectory` verifies each row's
Ed25519 signature over the service's own canonical bytes and drops what fails, then orders by a
composite it **recomputes** from the components. The transmitted `compositePermille` is never read for a
decision. A tracker keeps the powers it always had — hide a service, list an unreachable one — and gains
none.

**A peer scores what it measured, because that is the question the aggregate cannot answer.** A relay
with excellent global availability that *this* peer cannot reach is useless to this peer. So where the
peer has probed a service, its own availability and p95 replace the aggregate's; where it has not — the
position every joining peer is in — the aggregate is all the evidence there is, which is why the
tracker computes one at all. Capacity and freshness always come from the record and the tracker: a peer
cannot observe either.

**Selection is three tie-breaks deep, and each one earns its place.** This peer's score; then the
tracker's recomputed aggregate, because a handful of probes at similar RTT genuinely cannot separate two
relays and the network's broader evidence beats an arbitrary constant; then the service id, so two peers
with identical evidence make the *same* choice instead of scattering a swarm across relays that never
share a path.

**A failed probe is not a fast probe.** `Reachability` reports `-1` for unreachable and a `ServiceScore`
with no reporters carries `rttP95 == 0`. Both had to become explicit sentinels: without them, an
unreachable or unmeasured relay would score as the fastest thing in the directory. This was caught by a
test, not by review.

**Several endpoints, all of them used.** `selected()` returns a list. Registering with several
rendezvous and querying only the first converts redundancy into a silent single point of failure — the
fallback endpoints hold records nobody reads ([`../rendezvous/REFERENCE.md`](../rendezvous/REFERENCE.md)
§9.1). `reserveAnywhere` and `openConnectAnywhere` try all of them.

**The accept loop must never give up — a real defect, not a hardening.** It previously slept 500 ms once
after a failed re-reservation and then *returned*, ending the transport's inbound relay path until the
process restarted. A rendezvous restart was therefore a permanent, invisible outage for every peer that
had reserved there. It now retries across every endpoint with backoff from 500 ms to 30 s, forever,
which costs one sleeping thread and restores the path the moment a relay comes back.

**A refusal a peer can read is worth more than a closed socket.** A draining relay answers with
`reason = "draining"`; the loop moves to the next endpoint immediately rather than retrying the host that
just said no.

**A drain notice is verified before it is acted on.** It arrives on the reservation's control channel and
carries the service's signature. Acting on an unverified one would let anyone on the path evict a peer
from a working relay and herd its traffic somewhere chosen for it.

**Configured endpoints are seeds, not overrides.** An operator who pinned a relay meant it, and a
LAN-only deployment has no tracker to ask. Seeds come first; discovered endpoints follow in score order.

**The mod asks the worker rather than growing its own directory.** The companion is already
running, already discovering relays from trackers, already probing and scoring them, and already
re-picking when one drains. Duplicating that inside the mod would mean two directories that
disagree — and the mod is the one with less information, since it starts and stops with the world.
So the mod reads the answer and keeps configuration as the fallback: a player with no companion
linked, an older worker whose STATE predates the field, or a LAN-only deployment with no tracker to
ask must all still be able to share a world. A relay the worker marks unreachable is skipped, because
composing a transport from a relay the worker just failed to reach is worse than using the
configured list.

**The refresh is guarded separately from the announce it rides with.** A task that throws out of
`scheduleWithFixedDelay` is silently never run again. Sharing the announce's cadence is right —
the worker's selection changes at most once a minute — but sharing its failure would stop the
announce too, with nothing anywhere saying so.

**Probing is an injectable seam.** `RendezvousDirectory.Prober` defaults to `Reachability::measure`. The
discovery, verification, selection and migration logic is then testable without standing up relays, and
the reachability half is tested directly on the scoreboard.

## Files

- `java/peer/src/main/java/dev/nodera/peer/discovery/ServiceScoreBoard.java`
- `java/peer/src/main/java/dev/nodera/peer/discovery/RendezvousDirectory.java`
- `java/peer/src/main/java/dev/nodera/peer/discovery/TrackerClient.java`
- `java/transport/src/main/java/dev/nodera/transport/rendezvous/RendezvousPeerTransport.java`
- `java/transport/src/main/java/dev/nodera/transport/rendezvous/RelayCircuitClient.java`
- `java/transport/src/main/java/dev/nodera/protocol/service/` — the message family
- `java/worker/src/main/java/dev/nodera/headless/{HeadlessPeerMain,WorldHostingService}.java`
- `rust/nodera-app/src/daemon.rs`

## Testing

```bash
./gradlew :peer:test --tests '*ServiceScoreBoardTest*' --tests '*RendezvousDirectoryTest*'
./gradlew :transport:test --tests '*ServiceMessageCodecTest*'
./gradlew check                       # the whole Java gate
cd rust/nodera-app && cargo test --lib daemon
```

Decisive tests:

- `RendezvousDirectoryTest.a_peer_with_no_configured_rendezvous_learns_one_from_a_tracker` — the
  requirement, over a real socket.
- `RendezvousDirectoryTest.a_forged_row_is_refused_and_never_dialled` — the trust boundary.
- `RendezvousDirectoryTest.a_drain_notice_migrates_the_peer_to_the_replacement_it_names` — seamless
  handover, with no tracker round trip.
- `RendezvousDirectoryTest.a_sweep_reports_what_it_measured_back_to_the_tracker` — closes the scoring
  loop; without it the aggregate is only the services' self-praise.
- `RendezvousDirectoryTest.an_unreachable_tracker_leaves_the_previous_selection_in_place` — the
  degradation path this category requires.
- `ServiceScoreBoardTest.a_peers_own_measurements_override_the_trackers_aggregate`.
- `ServiceScoreBoardTest.a_failed_probe_is_not_a_fast_probe` and
  `the_trackers_aggregate_breaks_a_tie_between_relays_this_peer_cannot_separate`.
- `ServiceScoreBoardTest.a_recent_recovery_pulls_the_score_back_up` — an outage ages out of the ring
  rather than condemning a relay forever.
- `daemon::tests::the_configured_rendezvous_endpoints_reach_the_worker` — the app-side bug, pinned.

## Acceptance criteria

1. ✅ A peer with only a tracker configured selects and uses a rendezvous it was never told about.
2. ✅ A forged or tampered directory row is never dialled.
3. ✅ A peer's own measurements decide, and an inflated aggregate does not.
4. ✅ More than one rendezvous is in use at a time, and all of them are read.
5. ✅ A rendezvous restart does not end a peer's inbound relay path.
6. ✅ A verified drain notice migrates the peer before the old relay stops.
7. ✅ Configured endpoints keep working, with or without a tracker.
8. 🚧 The Minecraft mod's transport reads the worker's selection — code landed and unit-tested; the live drain suite that is this row's exit test is still to be written (L-91).

## Limitations

Owns **L-91** (renumbered 2026-07-28 from a duplicate L-84), now RETIRING. The mod reads the
worker's live selection and pushes changes into the transport without a world reopen; what is not yet
proved is the loop closing against a relay that actually drains, which is a live suite this branch has
not written. Recorded in [`LIMITATIONS.md`](LIMITATIONS.md) with its exit test.
