# `rust/nodera-rendezvous`

<!-- AI-AGENT-INSTRUCTION: RESERVATIONS ARE THE ABUSE BOUNDARY — no reservation, no CONNECT. Never add
     a relay path that skips reservation validation, and never remove the per-direction byte,
     duration, and idle metering: an unmetered relay is an open relay. The service must never be able
     to read relayed traffic (peers run an end-to-end cipher). It holds no signing keys. Log counts,
     never payloads. Update this file when a module is added. -->

**NAT reach.** Peers register signed candidate records and discover each other, coordinate hole
punching, and — when no direct path exists — exchange end-to-end-encrypted frames through a metered
relay.

- **Depends on:** `nodera-codec`.
- **Also depends on:** `nodera-service` — identity, tracker self-announce, the drain sequence, and
  self-update. The drain order lives there on purpose: a second copy of it here is how the two services
  would end up meaning different things by "draining".
- **Depended on by:** the Java rendezvous transport, the worker, the mod's host lane.
- **Docs:** [`docs/rendezvous/`](../../docs/rendezvous/Task.0.md) · reference architecture:
  [`docs/rendezvous/REFERENCE.md`](../../docs/rendezvous/REFERENCE.md)

---

## Architecture

```
rust/nodera-rendezvous/src/
├── main.rs         CLI: --config nodera-rendezvous.toml
├── config.rs       bind, registration TTL, page limits, reservation limits, quotas,
│                   optional namespace allowlist
├── registry.rs     namespace → node id → signed record (candidates, capabilities, expiry)
├── register.rs     register / refresh, Ed25519 verification, TTL bookkeeping,
│                   trust-on-first-use identity binding
├── discover.rs     namespace query with cursor, limit, and candidate filtering
├── reservation.rs  reserve → reservation (relay route, expiry, limits, HMAC proof)
├── circuit.rs      connect → incoming → accept → bridged copy loops, with per-direction
│                   byte/duration/idle metering and teardown reason codes
├── punch.rs        observed addresses + a coordinated go-signal
├── limits.rs       per-identity registrations, per-IP quotas, record-size caps
└── service.rs      the tokio server
```

The namespace is `(networkId, genesisHash)`. The tracker answers *which worlds exist and who seeds
them*; this service answers *how do I reach peer X right now*. They are independent — a deployment may
run either or both.

## Why it is shaped this way

**Registration and relaying are different businesses in one binary.** Registration and discovery are
cheap TTL'd metadata; a relay circuit carries real bandwidth and real cost. Keeping them logically
separate means the cheap plane stays available when the expensive one is saturated, and their limits
are independently tunable.

**No reservation, no connect — validated statelessly.** The reservation carries an HMAC proof, so the
service does not have to remember every issued reservation to enforce it. This closes the open-relay
abuse hole: an unreserved connect is refused before any bridging begins.

**Meter per direction, tear down with a reason.** Byte ceiling, duration, and idle timeout are all
enforced, and the teardown reason is surfaced to the Java side. An unmetered relay is an open relay;
a silent teardown is an undiagnosable one.

**Trust on first use for identity binding.** A record binds a node id to a public key the first time
it is seen, so a later record claiming the same id under a different key is refused — cheap
impersonation becomes expensive without introducing a registry authority.

**The observed source is appended as a candidate, at low priority.** That is how a peer learns its own
reflexive address without letting an observer overwrite what the peer signed.

**Refresh at half the TTL.** A registration that expires silently makes a peer vanish from discovery
with nothing logging a failure.

**Log counts, never payloads.** The privacy floor is explicit: the service records how much it
carried, not what.

## Rules

- The service holds no signing keys and can neither read nor forge relayed traffic — peers run an
  end-to-end cipher over every relayed leg **before any application byte**.
- A relayed steady state is **legal**, not a failure: correctness must hold on a pure-relay path.
- Naming trap: the `Rendezvous*Policy` classes in the Java tree are rendezvous *hashing* and are
  unrelated to this service. Do not merge the concepts.

## Tests

55 unit tests plus the real-binary `RendezvousRelayIT`, which bridges two **relay-only** Java peers:
frames cross the encrypted circuit byte-exact, the byte ceiling tears it down with the right reason,
and the selector reports direct when one is allowed.

```bash
cd rust && cargo test -p nodera-rendezvous
```
