# `rust/nodera-tracker`

<!-- AI-AGENT-INSTRUCTION: This service is DISCOVERY INFRASTRUCTURE, NEVER AUTHORITY. No endpoint here
     may become load-bearing for correctness — peers verify everything by hash and signature. The
     service holds no signing keys over anything but its OWN ADDRESS RECORD (see nodera-service), and
     self-registration is the only way in for peers and services alike. It runs on
     the public internet: quotas, size caps, bounded counts, no full-scrape endpoint, and a UDP path
     bounded against reflection amplification are structural, not optional. Update this file when a
     module is added. -->

**Always-on world and peer discovery that does not die with any player.** An operator runs this on a
public host; peers announce signed records and query per-world swarms.

- **Depends on:** `nodera-codec` (the peers' own canonical encoding — there is no second protocol),
  `nodera-service` (identity, self-announce, drain, self-update — shared with the rendezvous so the two
  cannot drift into different definitions of "draining").
- **Depended on by:** the Java `TrackerClient`, the mod's multiplayer list, the worker's announce loop,
  and — new with the service directory — every peer that needs to find a rendezvous point.
- **Docs:** [`docs/tracker/`](../../docs/tracker/Task.0.md) · reference architecture:
  [`docs/tracker/REFERENCE.md`](../../docs/tracker/REFERENCE.md)

---

## Architecture

```
rust/nodera-tracker/src/
├── main.rs       CLI: --config nodera-tracker.toml
├── config.rs     bind address, announce interval, TTLs, bounds, quotas, sample size
├── registry.rs   swarms keyed by genesis hash → peer records
│                 (routes, roles, held manifests, reliability, last seen)
├── announce.rs   started / heartbeat / stopped, with Ed25519 verification
├── query.rs      reservoir-sampled responses with a seeder floor
├── health.rs     world-health derivation + the retention countdown surface
├── limits.rs     per-IP and per-identity quotas, record-size caps, bounded counts
├── wire.rs       u32-length framing (16 MiB cap) over TCP, plus the UDP datagram surface
└── service.rs    the tokio server: one task per connection, graceful SIGTERM drain
```

## Why it is shaped this way

**A separate binary, because the property is "outlives every player".** An embedded tracker inside a
peer can only answer while that peer runs. Making it its own always-on process is what lets a world
stay **findable — with an honest DEAD verdict — after everyone who held it went offline**. That
scenario is the reason this service exists, and it is why the embedded Java implementation was
deleted rather than kept as a fallback.

**The swarm identifier is the world's genesis hash** — the content-addressed analogue of a torrent
`info_hash`. Display names are directory metadata keyed by that hash; the genesis manifest stays
name-free and frozen.

**Identity keys a record; the observed address is a hint.** If the source IP keyed the record, anyone
able to reach the service could relocate a peer. Routes come from the signed record, with the observed
source appended at low priority.

**Verify-only, no keys.** The service checks signatures and stores what it verified. It cannot
impersonate a peer because it holds nothing to sign with.

**Sampling with a seeder floor.** A large swarm cannot be returned whole, and a random sample might
contain no seeders — leaving a joiner with peers but no data. Full-archive and seeder roles are
included up to the floor before the sample fills the rest.

**Zero persistence by default.** State that rebuilds itself within one announce interval does not need
a database, and a stateless service is one less thing to corrupt or leak.

**The ack paces the client.** Telling peers when to announce next lets the service shed load rather
than depending on every client's good behaviour.

**No full-scrape endpoint.** Enumerating every world and peer is exactly the primitive an abuser
wants; the omission is deliberate.

**The UDP surface is bounded and silent.** One datagram per request over the shared registry, capped
against reflection amplification, and **silent rather than truncating** when an answer would exceed
the bound — silence is recoverable (the client falls back to TCP), a truncated answer is corrupt.

## Rules

- The peers' canonical encoding **is** the protocol. No HTTP, no second serialization.
- Nothing added here may become load-bearing for correctness. Test the degradation path: tracker down
  ⇒ discovery degrades, mesh and state unaffected.

## Tests

60 unit tests plus the real-binary `TrackerServiceIT` driven from Java — including the decisive
scenario: a world whose every seeder has gone silent past the TTL is **still listed by name, with its
countdown and a DEAD verdict**.

```bash
cd rust && cargo test -p nodera-tracker
```
