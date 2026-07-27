# Rendezvous Task 5 — Discoverable, drainable, self-updating

<!-- AI-AGENT-INSTRUCTION: The load-bearing claim of this task is that a rendezvous restart is a
     MIGRATION, not an outage. The order inside a drain is the design: refuse new work, tell the peers
     already committed, tell the trackers, wait for in-flight circuits, only then stop. Do not reorder
     it, and do not reintroduce a "drain" that logs the word and drops the runtime — that is exactly
     the bug this task fixes. A draining relay MUST keep answering discovery. Keep this header's
     status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** rendezvous · **Owns:** L-83 · **Last audit:** 2026-07-27
**Depends on:** [rendezvous 1](Task.1.md), [rendezvous 2](Task.2.md), [tracker 5](Task.5.md)
**Consumed by:** [network 13](../network/Task.13.md), [worker 3](../worker/Task.3.md),
[minecraft 5](../minecraft/Task.5.md)

---

## Goal

An operator can add a rendezvous to the network and have every peer find it; can restart one for an
update without cutting a single circuit or stranding a single peer; and can let it update itself. A
rendezvous announces itself to trackers, refuses new work the moment it decides to stop, tells the peers
holding reservations **on their own sockets** where to go instead, lets in-flight circuits finish, and
only then exits.

## Status detail

Landed and green (`cargo test -p nodera-rendezvous`, **67 tests**, up from 62; the five new ones are
socket-level).

- `src/service.rs` — shares a `DrainState` with the lifecycle task; a draining relay refuses
  `RELAY_RESERVE` with the readable reason `draining` and refuses `RELAY_CONNECT`, while continuing to
  answer `REGISTER` and `DISCOVER`.
- `src/wire.rs` — the control-channel map became a **std** mutex so the drain broadcast can run from a
  synchronous trait method; `broadcast_frame` pushes a signed notice to every reserved peer; a bridged
  circuit now holds an in-flight guard, which is what a drain waits on.
- `src/registry.rs` — `record_count`, the number the service publishes as its own load.
- `src/config.rs` — `tracker_endpoints`, `advertised_routes`, `identity_file`,
  `drain_grace_seconds`, `max_concurrent_circuits`, `update_*`.
- `src/main.rs` — the shared `nodera-service` lifecycle; the listener closes *after* the drain, not
  during it.

Remaining: the pure-relay continuity run belongs to [Task 3](Task.3.md), which is still blocked on two
genuinely separate networks.

## Dependencies

- [rendezvous 1](Task.1.md) — the reservation and circuit machinery a drain has to wind down.
- [tracker 5](Task.5.md) — the directory this service announces into, and the ack that hands it its own
  replacement list.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Self-announce to configured trackers on the refresh cadence | ✅ |
| 2 | A real drain: refuse → notify peers → notify trackers → await circuits → stop | ✅ |
| 3 | In-flight circuit accounting, released on every exit path including a panic | ✅ |
| 4 | `SERVICE_DRAIN_NOTICE` pushed down every reserved peer's control channel | ✅ |
| 5 | A draining relay keeps answering registration and discovery | ✅ |
| 6 | Reservations refused with a readable reason, not a closed socket | ✅ |
| 7 | Self-update from a GitHub release, verified, drain-gated, off by default | ✅ |
| 8 | A stable service identity across restarts | ✅ |
| 9 | Live cross-internet numbers | ⏳ [Task 3](Task.3.md) |

## Design

**The drain order is the design.** Refuse first: a reservation granted after the decision to stop is a
promise the service already knows it will break. Then tell the peers who are already committed — they
are, by construction, the ones whose inbound path is about to vanish. Then tell the trackers, so a peer
asking *after* this moment is not sent here. Then wait for in-flight work, bounded by
`drain_grace_seconds`. Only then say `Stopped`, so the record does not vanish from discovery while
circuits are still using it.

**What "draining" used to mean here.** The service printed `draining on shutdown signal`, broke its
accept loop, and returned from `main` — dropping the tokio runtime and killing every bridged circuit
mid-frame, because the circuits were detached `tokio::spawn` tasks nobody awaited. The word was in the
log line and nowhere in the behaviour. The in-flight guard in `run_reserved` is the fix, and
`a_live_circuit_registers_as_in_flight_work` is what holds it in place.

**The notice goes down the peer's own socket, and the tracker path is the backup.** A peer holding a
reservation has an open control channel to this process. That is the fastest and most certain way to
reach exactly the peers who will be hurt, and the ones for whom rediscovery is slowest. Publishing
`Draining` to trackers covers everybody else. Belt and braces, in that order.

**The replacements travel in the notice.** The announce ack carries the announcer's siblings, so a
draining relay already holds the list it needs. The alternative — announce, then query, then drain — adds
a round trip at the one moment the service is least able to make one.

**A refusal must be readable.** A draining relay answers `RELAY_RESERVE` with
`accepted = false, reason = "draining"` rather than closing the socket. The peer side treats a reason it
can read as "re-reserve elsewhere now" and a silent close as "retry here" — the difference between a
migration and a stall.

**Discovery survives a drain.** Registration and discovery are cheap TTL'd metadata and they are how
peers find each other in order to move somewhere else. Refusing them during a drain would strand exactly
the peers the drain exists to protect.

**Circuits are what a drain waits for; registrations are not.** A registration is a lease that peers
re-establish within one refresh interval, so losing the registry is self-healing
([`REFERENCE.md`](REFERENCE.md) §9.3). A bridged circuit is somebody's world transfer.

**The service signs its own record.** See [tracker 5](Task.5.md) §Design for the reasoning and the
narrower invariant it preserves. A forged drain notice would be a way to herd a target's traffic onto an
attacker's relay, which is why the notice carries a signature the peer checks before acting on it.

**`max_concurrent_circuits` is advertised, not enforced here.** Enforcement is the reservation limits.
Publishing the ceiling lets a peer prefer a relay with headroom over a saturated one — the capacity term
of the score, weighted 20 of 100 so an operator can shed load without a service being able to flatter
itself into first place.

## Files

- `rust/nodera-rendezvous/src/service.rs` — drain-aware reserve/connect, `DRAINING_REASON`
- `rust/nodera-rendezvous/src/wire.rs` — control-channel map, `broadcast_frame`, the in-flight guard
- `rust/nodera-rendezvous/src/config.rs` — the new keys
- `rust/nodera-rendezvous/src/main.rs` — `RendezvousHost`, the lifecycle wiring, the re-exec
- `rust/nodera-service/src/{drain,lifecycle,directory,identity,update}.rs` — the shared plumbing
- `java/transport/.../rendezvous/RelayCircuitClient.java` — reads a notice on the control channel
- `java/transport/.../rendezvous/RendezvousPeerTransport.java` — multi-endpoint reserve + failover
- `.github/workflows/release-latest.yml` — publishes `SHA256SUMS`, the manifest the updater reads

## Testing

```bash
cd rust && cargo test -p nodera-rendezvous   # 67 tests, 5 of them socket-level drain tests
cd rust && cargo test -p nodera-service      # 38 tests, drain + lifecycle ordering
./gradlew :peer:test --tests '*RendezvousDirectoryTest*'
```

Decisive tests:

- `wire::tests::a_drain_notice_arrives_on_a_reserved_peers_own_control_channel` — over a real socket,
  verified with the service's own key. The migration lane's headline property.
- `wire::tests::a_live_circuit_registers_as_in_flight_work` — the counter a drain waits on, and its
  release when the bridge ends. This is the test that would have caught the old fake drain.
- `wire::tests::a_draining_relay_refuses_a_reservation_with_a_readable_reason` — reason, not silence.
- `wire::tests::a_draining_relay_still_answers_discovery` — the peers who need to move can still look.
- `wire::tests::a_draining_relay_refuses_a_new_circuit` — no circuit that would break inside the grace.
- `lifecycle::tests::a_drain_refuses_work_before_it_tells_anybody` — the ordering, asserted directly.
- `lifecycle::tests::in_flight_work_delays_the_drain_and_the_grace_period_bounds_it`.
- `RendezvousDirectoryTest.a_drain_notice_migrates_the_peer_to_the_replacement_it_names` — the peer half.

Degradation, as this category requires: a relay that dies without draining is still just a broken
circuit and a lapsed reservation, and the peer side now re-reserves elsewhere with backoff instead of
ending its accept loop. That last part was a real defect — see [network 13](../network/Task.13.md).

## Acceptance criteria

1. ✅ A rendezvous announced to a tracker is discovered by peers that were never configured with it.
2. ✅ A restart cuts no circuit that finishes inside the grace period.
3. ✅ Every peer holding a reservation is told, on its own socket, with somewhere to go.
4. ✅ A forged drain notice is ignored.
5. ✅ A draining relay still answers discovery.
6. ✅ Updating is inert unless configured, verified before install, and drain-gated.
7. ⏳ Measured on two genuinely separate networks ([Task 3](Task.3.md)).

## Limitations

Owns **L-83** (a drain's grace period can still expire with work in flight — bounded, reported, and by
design, but the truncation is real). Recorded in [`LIMITATIONS.md`](LIMITATIONS.md) with its exit test.
