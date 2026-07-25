# Rendezvous Task 1 — The `nodera-rendezvous` Service Binary

<!-- AI-AGENT-INSTRUCTION: Reservations are the abuse boundary of this service: no reservation, no
     CONNECT. Never add a relay path that skips reservation validation, and never remove the
     per-direction byte, duration, and idle metering — an unmetered relay is an open relay. The
     service must never be able to read relayed traffic. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** rendezvous · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [network 1](../network/Task.1.md)
**Consumed by:** [rendezvous 2](Task.2.md), [worker 3](../worker/Task.3.md), [minecraft 5](../minecraft/Task.5.md)

---

## Goal

A standalone service that lets two peers behind NAT find and reach each other: signed registration and
paged discovery, reflexive-address observation, HMAC-proof relay reservations, metered circuit
bridging, and coordinated hole punching — all while being unable to read or forge what it carries.

## Status detail

Complete. 55 Rust tests. `RendezvousRelayIT` spawns the **real binary** and drives two relay-only Java
peers through it: they register, discover each other, and a `PeerJoin` plus a keep-alive cross the
end-to-end-encrypted circuit **byte-exact**; exhausting the reservation's byte ceiling tears the
circuit down with the right reason code; and the selector reports the direct path when one is allowed.

## Dependencies

- [network 1](../network/Task.1.md) — canonical encoding and framing via `nodera-codec`.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `main.rs` + `config.rs` — bind, TTLs, page limits, reservation limits, quotas, optional namespace allowlist | ✅ |
| 2 | `registry.rs` — namespace → node id → signed record with candidates, capabilities, expiry | ✅ |
| 3 | `register.rs` — register/refresh, Ed25519 verification, TTL bookkeeping | ✅ |
| 4 | `discover.rs` — namespace query with cursor, limit, and candidate filtering | ✅ |
| 5 | `observed.rs` — the caller's reflexive address | ✅ |
| 6 | `reservation.rs` — reserve → reservation with relay route, expiry, limits, HMAC proof | ✅ |
| 7 | `circuit.rs` — connect → incoming → accept → bridged copy loops with metering and teardown reasons | ✅ |
| 8 | `punch.rs` — observed addresses plus a coordinated go-signal | ✅ |
| 9 | `limits.rs` — per-identity registrations, per-IP quotas, record-size caps | ✅ |

## Design

**Registration and relaying are different businesses in one binary.** Registration and discovery are
cheap, TTL'd metadata; a relay circuit carries real bandwidth and real cost. Keeping them logically
separate means the cheap plane stays available even when the expensive one is saturated, and it makes
the limits on each independently tunable.

**No reservation, no connect.** The reservation is validated **statelessly** from its HMAC proof, so
the service does not have to remember every issued reservation to enforce it. This is what closes the
open-relay abuse hole: an unreserved connect is refused before any bridging begins.

**Meter per direction, tear down with a reason.** Byte ceiling, duration, and idle timeout are all
enforced, and teardown carries a reason code the Java side surfaces. An unmetered relay is an open
relay; a silent teardown is an undiagnosable one.

**Trust on first use for identity binding.** A record binds a node id to a public key the first time
it is seen, so a later record claiming the same id under a different key is refused. Combined with a
per-IP quota, this makes cheap impersonation attempts expensive without introducing a registry
authority.

**The service appends the observed source as a candidate — at low priority.** That is how a peer
learns its own reflexive address (the STUN-shaped part of the design) without letting an observer
overwrite what the peer signed.

**Refresh at half the TTL.** A registration that expires silently makes a peer vanish from discovery
without anything logging a failure; refreshing at half-life keeps the record alive across ordinary
jitter.

**Log counts, never payloads.** The privacy floor is explicit: the service records how much it
carried, not what.

## Files

- `rust/nodera-rendezvous/src/{main,config,registry,register,discover,observed,reservation,circuit,punch,limits}.rs`

## Testing

- Register, refresh, expiry; namespace isolation and paging.
- Quota, oversize, and bad-signature rejection.
- Reservation issue, stateless validation, and expiry.
- Circuit byte, duration, and idle enforcement with correct teardown reasons.
- Punch go-signal ordering.
- Cross-language conformance: the full rendezvous family round-trips byte-exactly against
  Java-emitted fixtures; the tag mirror is green.
- `RendezvousRelayIT` — the real binary bridging relay-only peers.

## Acceptance criteria

1. ✅ Only signed records register; a forged or oversized record is refused.
2. ✅ Discovery is namespaced, paged, and rate-limited.
3. ✅ A connect without a valid reservation is refused.
4. ✅ Circuits are metered per direction and torn down with a reason code.
5. ✅ The service can neither read nor forge relayed traffic.
6. ✅ `cargo test` green (55) and conformance green.

## Limitations

None open. **L-23** and **L-27** are RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
