# Rendezvous — Testing

<!-- AI-AGENT-INSTRUCTION: Counts come from `cargo test -p nodera-rendezvous` and the Java gate, never
     from memory. The decisive test drives the REAL BINARY with RELAY-ONLY peers — a test that allows
     a direct path does not prove relay correctness and must not be recorded as doing so. Keep counts
     and Last run current. -->

**Category:** rendezvous · **Last run:** 2026-07-25 · **55 Rust tests · 0 failing** (plus the Java
transport tests, including `RendezvousRelayIT`, on the Java gate)

```bash
cd rust && cargo test -p nodera-rendezvous          # the service
./gradlew :transport:test --tests '*endezvous*'     # the transport + the real-binary IT
```

---

## 1. The testing strategy

| Level | What it proves |
|---|---|
| Rust unit tests | Each mechanism in isolation: registration, discovery, reservations, circuits, punching, limits |
| Cross-language conformance | The full rendezvous family round-trips byte-exactly against Java-emitted fixtures; the tag mirror fails CI if one side appends alone |
| Java transport tests | Path policy, the cipher, dialling, and punch coordination, headlessly |
| **Real-binary integration** | `RendezvousRelayIT` bridges **relay-only** peers through the actual binary — the only level that proves a pure-relay path works |
| Live cross-internet | Real NAT, measured path mix — [`Task.3.md`](Task.3.md), pending |

## 2. Rust unit coverage

- Register, refresh, and expiry; namespace isolation and paging with a cursor.
- Quota, oversize, and bad-signature rejection.
- Reservation issue, **stateless** HMAC validation, and expiry.
- Circuit enforcement: per-direction byte ceiling, duration, idle timeout — each with the correct
  teardown reason code.
- Punch go-signal ordering.

## 3. Java transport coverage

- `TransportSelector` — direct over punched over relayed; demotion on path loss; bulk stream chunks
  strongly avoid relayed paths.
- `EndToEndCipher` — loopback round-trip, identity binding, tamper rejection.
- `CandidateDialer` — candidate ordering; reachable and unreachable dials.
- `HolePunchCoordinator` — go-signal wait and candidate selection.

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
