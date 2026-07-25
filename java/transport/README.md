# `java/transport`

<!-- AI-AGENT-INSTRUCTION: Two invariants govern this module. (1) The message-tag registry is
     APPEND-ONLY and mirrored in rust/nodera-codec: adding a tag means a Java record + the tag + a
     golden fixture in fixtures/wire/ + the Rust mirror, ALL IN ONE COMMIT — never renumber, never
     reuse a retired tag, never hand-edit a fixture. (2) The PeerTransport seam is sacred: no call
     site anywhere in the project may know which transport carried a message. Update this file when a
     package is added or its responsibility changes. -->

**The frozen wire contract and the carriers that move it.** Everything above this module is written
once and works over loopback, LAN sockets, punched paths, and relays.

- **Depends on:** `core`.
- **Depended on by:** `peer`, `testing`, `neoforge-mod`.
- **Docs:** [`docs/network/Task.1.md`](../../docs/network/Task.1.md) ·
  [`docs/rendezvous/Task.2.md`](../../docs/rendezvous/Task.2.md) ·
  reference: [`docs/network/REFERENCE.md`](../../docs/network/REFERENCE.md)

---

## Architecture

```
dev.nodera.protocol            the sealed NoderaMessage family (append-only tags, through 60)
├── codec/                     MessageCodec + ChunkedStreams (24 KiB chunks + zstd)
├── handshake/                 configuration-phase Ed25519 challenge; enforces assumption A0
├── membership/                peer join, entries, keep-alive (v1/v2 dual-version bodies)
├── assignment/                region assignment and leases
├── simulationmsg/             action batches, proposals, votes, commit announcements
├── content/                   piece requests, chunks, availability, manifest exchange
├── discovery/                 tracker queries, announces, catalog and route queries
├── rendezvous/                registration, discovery, reservations, circuits, punching
└── health/                    health and status reporting

dev.nodera.transport           PeerTransport seam: PeerAddress, MessageHandler, listenRoute
├── socket/                    SocketPeerTransport — real TCP, the LAN/direct data plane,
│                              with an authenticated challenge-response accept handshake
└── rendezvous/                RendezvousPeerTransport — direct-first / punch-upgrade /
                               E2E-encrypted relay-fallback, TransportSelector, EndToEndCipher
```

## Why it is shaped this way

**One family, one codec, two implementations.** A second implementation in Rust would normally be a
liability; here it is the *test*. Java emits golden frames into `fixtures/wire/`, Rust re-encodes them
byte-exactly, and a registry mirror assertion fails CI if one side appends alone. Two implementations
that must agree byte-for-byte catch encoding drift a single implementation cannot.

**Chunked zstd streams exist because of a platform cap.** NeoForge payloads are capped at about 1 MiB
clientbound and under 32 KiB serverbound. The stream layer chunks and compresses beneath the cap, with
a **bounded** reassembler so a sender that never finishes a stream cannot allocate without limit.

**A NodeId is not an identity until a key proves it.** A NodeId is a random UUID unrelated to the
Ed25519 key, so admission by NodeId alone let any TCP client claim any identity. The proof lives in the
**transport handshake**, which covers every message — not in a join message, which covers only the
join.

**The seam is what makes the rest of the project portable.** Membership, committee traffic, and
distribution are written once. The moment a call site asks "am I relayed?", that property is gone.

**Direct-first only works if a direct candidate exists.** The rendezvous transport must advertise a
HOST candidate derived from the direct transport's `listenRoute()`; without it, a correct preference
policy has nothing to prefer and every byte silently crosses the relay.

## Rules

- Tags are append-only and never renumbered. Body-version bumps require dual-version decoders (the
  keep-alive v1/v2 pair is the precedent).
- Every appended tag lands with a Java golden fixture and the Rust re-encode test in the same commit.
- Relayed legs are end-to-end encrypted **before any application byte**.

## Tests

93 tests: golden-fixture round-trips, the registry snapshot, the authenticated handshake against real
TCP (key-proven interop; legacy, forged, and replayed hellos refused), bind-failure invariants, path
policy, cipher identity binding and tamper rejection, and `RendezvousRelayIT` against the real
rendezvous binary.

```bash
./gradlew :transport:test
```
