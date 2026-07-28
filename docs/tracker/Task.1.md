# Tracker Task 1 — The `nodera-tracker` Service Binary

<!-- AI-AGENT-INSTRUCTION: This binary verifies signatures; it never creates them, and it holds no
     keys. It must remain safe to run on a public host: quotas, size caps, bounded counts, no
     full-scrape endpoint, and a UDP path bounded against reflection amplification. Any feature that
     makes a tracker answer load-bearing for correctness is out of scope by definition. Keep this
     header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** tracker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 1](../network/Task.1.md)
**Consumed by:** [tracker 2](Task.2.md), [worker 3](../worker/Task.3.md), [minecraft 4](../minecraft/Task.4.md)

---

## Goal

A standalone Rust service that keeps a world listed for as long as anyone cares about it — including
when every seeder is offline — while being safe to expose on the public internet.

## Status detail

Complete. The crate now carries **109 `#[test]`s** (see [`TESTING.md`](TESTING.md)); this task's
mechanisms — announce lifecycle, TTL sweep, sampling, quotas, health, UDP — are the foundation.
`TrackerServiceIT` spawns the **real release binary** and drives it from
Java peers: two peers announce two worlds with per-world isolation; a JDK-signed announce is verified
inside the service by `ed25519-dalek`; a tampered record is refused with `bad-signature` and never
reaches the registry; a `STOPPED` announce removes a peer immediately; and — the decisive scenario —
a world whose every Java seeder has gone silent past the TTL is **still listed by name, with its
countdown and a DEAD verdict**.

The service answers the frozen discovery family plus the appended announce family over
u32-length-framed **TCP and UDP**. The UDP path is one datagram per request over the shared registry,
capped against reflection amplification, and silently drops undecodable datagrams.

## Dependencies

- [network 1](../network/Task.1.md) — canonical encoding and framing via `nodera-codec`.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `main.rs` + `config.rs` — CLI, bind address, TTLs, limits, logging | ✅ |
| 2 | `registry.rs` — swarms keyed by genesis hash; peer records with routes, roles, manifests, reliability, last-seen | ✅ |
| 3 | `announce.rs` — started / heartbeat / stopped with Ed25519 verification | ✅ |
| 4 | `query.rs` — reservoir-sampled responses with a seeder floor | ✅ |
| 5 | `health.rs` — world health derivation + retention-countdown surface | ✅ |
| 6 | Last-seen sweep (TTL ≈ 2× announce interval) — `registry.rs::Registry::sweep` | ✅ |
| 7 | `limits.rs` — per-IP and per-identity quotas, record-size caps, bounded counts | ✅ |
| 8 | `wire.rs` — u32 framing with a 16 MiB cap; TCP and UDP surfaces | ✅ |
| 9 | Graceful SIGTERM drain | ✅ |

## Design

**A separate binary because the property is "outlives every player".** An embedded tracker inside a
peer can only answer while that peer runs. Making the tracker its own always-on process is what lets a
world still be *findable* — with an honest DEAD verdict — after everyone who held it went offline.
That was the failing scenario the embedded implementation could not satisfy, and it is why the
embedded serving path was deleted rather than kept as a fallback.

**Verify-only, no keys.** The service checks signatures on announces and stores what it verified. It
cannot impersonate a peer because it holds nothing to sign with, and self-registration means it never
speaks on anyone's behalf.

**Identity keys a record; the observed address is a hint.** If the source IP keyed the record, anyone
able to reach the tracker could relocate a peer. Routes come from the signed record, and the observed
source is appended at low priority — useful for reachability, never authoritative.

**Sampling with a seeder floor.** A large swarm cannot be returned whole, and a random sample might
contain no seeders — leaving a joiner with peers but no data. `FULL_ARCHIVE` and `WORLD_SEEDER` roles
are therefore always included up to the floor before the sample fills the rest.

**Zero persistence by default.** State that rebuilds itself in one announce interval does not need a
database, and a stateless service is one less thing an operator can corrupt or leak. Optional
persistence covers only display-name metadata.

**The ack paces the client.** `nextAnnounceAfterSeconds` lets the service shed load by asking peers to
slow down, rather than depending on every client's good behaviour.

**No full-scrape endpoint.** Enumerating every world and peer is exactly the primitive an abuser
wants; the omission is deliberate.

## Files

- `rust/nodera-tracker/src/{main,config,registry,announce,query,health,deletion,limits,wire,services,service,telemetry}.rs`

## Testing

- Announce lifecycle including re-announce replacement; TTL expiry; stopped-removes; per-world
  isolation.
- Sampling bounds and the seeder floor; quota rejection; invalid-signature rejection.
- Health and countdown transitions.
- UDP: one datagram per request, the amplification cap, silent drop of undecodable datagrams.
- `TrackerServiceIT` — the real binary driven from Java peers.

## Acceptance criteria

1. ✅ A world stays listed with its countdown and a DEAD verdict when every seeder is offline.
2. ✅ A tampered announce is refused and never reaches the registry.
3. ✅ Per-world isolation holds; a `STOPPED` announce removes immediately.
4. ✅ Quotas, size caps, and bounded counts hold under flood.
5. ✅ The UDP surface cannot be used for reflection amplification.
6. ✅ `cargo test` green (60) and cross-language conformance green.

## Limitations

None open. **L-44** (tracker embedded in a Java peer) is RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
