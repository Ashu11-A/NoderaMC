# Rendezvous — Category Charter

<!-- AI-AGENT-INSTRUCTION: The relayed path is a FIRST-CLASS FALLBACK, not an apology: correctness
     must hold on a pure-relay path, and tests must exercise it. The service introduces and forwards;
     it is never trusted. Naming trap: `RendezvousPlacementPolicy` and `RendezvousArchivePolicy`
     elsewhere in the project are rendezvous *hashing* and have nothing to do with this service — do
     not "unify" them. Keep the task index in agreement with ../ROADMAP.md §2. -->

**Category:** `rendezvous` · **Status:** 🚧 IN PROGRESS (2 of 3 tasks completed) ·
**Last audit:** 2026-07-25

---

## 1. What this category is

**NAT reach.** A direct socket transport needs a directly reachable listener — LAN, port forwarding,
or a VPN — which most players do not have. This category adds a standalone Rust rendezvous and relay
service plus the Java transport that consumes it: peers **register** signed candidate records and
**discover** each other, **coordinate hole punching**, and, when no direct path exists, exchange
**end-to-end-encrypted** frames through a relay.

The Java side is the third `PeerTransport`: direct-first, punch-upgrade, relay-fallback, behind the
same seam — call sites cannot tell which path carried a message.

## 2. Architecture

```
   peer A ──register(signed)──►┌──────────────────────────────┐◄──register── peer B
   peer A ──discover(ns)──────►│  nodera-rendezvous (Rust)    │
                               │  registry · observed addr    │
   peer A ──reserve──────────► │  reservations (HMAC proof)   │
   peer A ──connect(B)───────► │  circuit bridge (metered)    │
   peer A ◄─punch_sync────────►│  punch coordination          │◄─punch_sync─► peer B
                               └──────────────────────────────┘
        relayed legs carry X25519+AES-GCM frames the relay cannot read or forge
```

- **Namespace = (networkId, genesisHash).** The tracker answers *which worlds exist and who seeds
  them*; the rendezvous answers *how do I reach peer X right now*. Independent services; a deployment
  may run either or both.
- **Rendezvous and relay stay logically separate even in one binary.** Registration and discovery are
  cheap TTL'd metadata; relay circuits carry real bandwidth and get hard limits. **No reservation, no
  connect.**
- **End-to-end encryption before any application byte** on relayed legs: X25519 ECDH (ephemeral,
  Ed25519-identity-signed) plus AES-GCM. One `NodeIdentity` end to end; the address is never proof.

## 3. Dependencies

**Depends on:** [`network/Task.1.md`](../network/Task.1.md) — the canonical encoding, framing, and the
`PeerTransport` seam this composes behind.

**Consumed by:** [`network/Task.2.md`](../network/Task.2.md) (cross-NAT migration),
[`minecraft/Task.5.md`](../minecraft/Task.5.md) (the live host lane),
[`worker/Task.3.md`](../worker/Task.3.md) (registration that persists across game sessions).

## 4. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | The `nodera-rendezvous` service binary | ✅ COMPLETED |
| [2](Task.2.md) | The Java rendezvous transport | ✅ COMPLETED |
| [3](Task.3.md) | Live cross-internet proof | ⏳ BLOCKED |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

**Reference architecture:** [`REFERENCE.md`](REFERENCE.md) — the discovery, connectivity-control, and
data planes; connection lifecycle; relay reservations; hole punching; security model. The task files
bind that reference to Nodera.

## 5. Files

| Path | Contents |
|---|---|
| `rust/nodera-rendezvous/src/` | `main`, `config`, `registry`, `register`, `discover`, `observed`, `reservation`, `circuit`, `punch`, `limits` |
| `java/transport/.../transport/rendezvous/` | `RendezvousPeerTransport`, `CandidateDialer`, `RelayCircuitClient`, `HolePunchCoordinator`, `TransportSelector`, `EndToEndCipher` |
| `java/transport/.../protocol/rendezvous/` | The rendezvous/relay message family |

Package architecture: [`rust/nodera-rendezvous/README.md`](../../rust/nodera-rendezvous/README.md),
[`java/transport/README.md`](../../java/transport/README.md).

## 6. Conventions specific to this category

- **Never special-case the transport at a call site.** Everything behind `PeerTransport`. The
  in-game NeoForge relay is a *different* relay (name collision only) and remains the permanent
  in-game fallback lane.
- **Relayed legs are end-to-end encrypted before any application byte.** Direct legs may skip the
  cipher (messages are signed anyway) but reuse the same session-establishment path.
- **Reservations are the abuse boundary.** No reservation ⇒ no connect. Limits are enforced
  per-direction and circuits are torn down with a reason code the Java side surfaces.
- **The service is untrusted by construction:** it can refuse introductions, but it can never
  impersonate (signed records) or read traffic (the E2E cipher).
- **A relayed steady state is legal.** Hole punching is best-effort; RELAYED is not a failure.
