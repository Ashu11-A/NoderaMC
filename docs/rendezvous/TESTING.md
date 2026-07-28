# Rendezvous — Testing

<!-- AI-AGENT-INSTRUCTION: Counts come from `cargo test -p nodera-rendezvous` and the Java gate, never
     from memory. The decisive test drives the REAL BINARY with RELAY-ONLY peers — a test that allows
     a direct path does not prove relay correctness and must not be recorded as doing so. Keep counts
     and Last run current. -->

**Category:** rendezvous · **Last run:** 2026-07-28 · **71 Rust tests in `nodera-rendezvous` · 0
failing** (62 `#[test]` + 9 `#[tokio::test]`, the latter the real-socket suite in `wire.rs`) ·
**28 Java `@Test`** in `dev.nodera.transport.rendezvous` (including `RendezvousRelayIT`).

```bash
cd rust && cargo test -p nodera-rendezvous          # the service (71)
cd rust && cargo test -p nodera-service              # identity, announce, drain, update (38)
./gradlew :transport:test --tests '*endezvous*'     # the transport + the real-binary IT (28)
```

Counts above were verified 2026-07-28 by grep against the source (`rg '#\[test\]|#\[tokio::test\]'` and
`rg '@Test'`), not from memory.

---

## 1. The testing strategy

| Level | What it proves |
|---|---|
| Rust unit tests | Each mechanism in isolation: registration, discovery, reservations, circuits, punching, limits |
| Cross-language conformance | The full rendezvous family round-trips byte-exactly against Java-emitted fixtures; the tag mirror fails CI if one side appends alone |
| Java transport tests | Path policy, the cipher, dialling, endpoint parsing, and punch coordination, headlessly |
| **Real-binary integration** | `RendezvousRelayIT` bridges **relay-only** peers through the actual binary — the only level that proves a pure-relay path works |
| Real-socket drain suite | The five `wire.rs` `#[tokio::test]`s that hold the migration lane: notice-on-own-channel, in-flight guard, readable refusal, discovery-survives-drain, no-new-circuit-in-grace |
| Live cross-internet | Real NAT, measured path mix — [`Task.3.md`](Task.3.md), pending |

## 2. Rust unit coverage

- Register, refresh, and expiry; namespace isolation and paging with a cursor.
- Quota, oversize, and bad-signature rejection.
- Reservation issue, **stateless** HMAC validation, and expiry.
- Circuit enforcement: per-direction byte ceiling, duration, idle timeout — each with the correct
  teardown reason code.
- Punch go-signal ordering and the NAT-pair outcome inference (punch-then-relay = failure).

## 3. Java transport coverage

- `TransportSelector` — direct over punched over relayed; demotion on path loss; bulk stream chunks
  strongly avoid relayed paths.
- `EndToEndCipher` — loopback round-trip, identity binding, tamper rejection.
- `CandidateDialer` — candidate ordering; reachable and unreachable dials.
- `HolePunchCoordinator` — go-signal wait and candidate selection.
- `RendezvousEndpoint` — `tcp://host:port` scheme stripping, IPv6 literals, malformed refusal.
- `BootstrapAddressHasNoNodeIdTest` / `CallerRouteIsDirectTest` — the two structural dispatch fixes
  (NPE out of the heartbeat scheduler; caller route treated as direct).

## 4. `RendezvousRelayIT` — the decisive scenario

Against the **real binary**, with both peers configured **relay-only**:

1. Both register signed records and discover each other.
2. A `PeerJoin` and a `SessionKeepAlive` cross the end-to-end-encrypted circuit **byte-exact** — so
   the relay carried them without being able to read or alter them.
3. Exhausting the reservation's byte ceiling tears the circuit down with the correct reason code.
4. With a direct path allowed, the selector reports direct — the punch-upgrade policy.

Relay-only is the point. A run that happens to find a direct path proves nothing about the fallback
this category exists to provide.

## 5. Conventions

- **Never assert the ordering without asserting the option exists.** The one structural defect this
  category has had was a correct preference policy with nothing to prefer: only a RELAY candidate was
  ever advertised. A test that a direct candidate exists when a direct listener exists is worth more
  than one that checks the ordering.
- **Every new message** lands with a Java golden fixture and the Rust mirror in the same commit.
- **Teardown reasons are asserted, not just teardowns.** A silent disconnection is
  undiagnosable in production.

## 6. Live evidence

Live rendezvous behaviour is exercised by the mod's scripted suites (see
[`../minecraft/TESTING.md`](../minecraft/TESTING.md)); `rendezvous.log` in a suite run shows
signed-record registrations. The measured direct/punched/relayed **mix** is [`Task.3.md`](Task.3.md)
and is not yet recorded.

## Draining, over real sockets (Task 5)

The five tests that hold the migration lane in place, all in `wire.rs`:

- `a_drain_notice_arrives_on_a_reserved_peers_own_control_channel` — the notice is delivered on the
  socket the reserver is already holding, and **verified** with the service's own key. A forged notice is
  an eviction primitive, so the signature check is the test's point as much as the delivery is.
- `a_live_circuit_registers_as_in_flight_work` — the counter a drain waits on, and its release when the
  bridge ends. This is the test that would have caught the drain that was only a log line.
- `a_draining_relay_refuses_a_reservation_with_a_readable_reason` — a reason, not a closed socket.
- `a_draining_relay_still_answers_discovery` — the peers who must move can still look.
- `a_draining_relay_refuses_a_new_circuit` — nothing is opened that would break inside the grace period.

The ordering itself is asserted in the shared crate:
`lifecycle::tests::a_drain_refuses_work_before_it_tells_anybody` and
`in_flight_work_delays_the_drain_and_the_grace_period_bounds_it`.
