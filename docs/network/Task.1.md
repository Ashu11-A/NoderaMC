# Network Task 1 — Wire Protocol, Transport Seam, Chunked Streams, Handshake

<!-- AI-AGENT-INSTRUCTION: The message-tag registry is APPEND-ONLY and mirrored in Rust. Appending a
     tag means: Java record + tag + golden fixture in fixtures/wire/ + the nodera-codec mirror, ALL
     IN ONE COMMIT. Never renumber, never reuse a retired tag, never regenerate a fixture by hand.
     Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (headless; NeoForge relay implementation → [minecraft 2](../minecraft/Task.2.md))
**Category:** network · **Owns:** A-4 mechanism · **Last audit:** 2026-07-28
**Depends on:** [engine 1](../engine/Task.1.md)
**Consumed by:** every network task, both Rust services, the worker, and the mod

---

## Goal

All Nodera messages defined once, an abstract transport seam, and the concrete transports behind it —
including the chunked, compressed stream layer that gets around NeoForge's payload caps, and the
configuration-phase handshake that authenticates a peer and enforces assumption A0.

## Status detail

Complete headlessly. `library/java/transport` carries 93 tests; message tags run through **60**. The
`SocketPeerTransport` gained an **authenticated** mode: every connection opens with a fresh 32-byte
challenge and answers the remote's challenge with an Ed25519-signed hello over
`challenge ‖ nodeId ‖ route ‖ publicKey`, so a connection's NodeId attribution is key-proven. Legacy
hellos, malformed frames, and forged signatures are torn down before any frame reaches a
`MessageHandler`. All three production call sites construct authenticated.

Remaining: the in-game NeoForge relay transport implementation and the live handshake on a real
client — [`minecraft/Task.2.md`](../minecraft/Task.2.md).

## Dependencies

- [engine 1](../engine/Task.1.md) — canonical encoding, `Bytes`, identity, signatures.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Sealed `NoderaMessage` family + `MessageCodec` (append-only tags) | ✅ |
| 2 | `ChunkedStreams` — 24 KiB chunks + zstd + bounded `StreamReassembler` | ✅ |
| 3 | `PeerTransport` seam (`PeerAddress`, `MessageHandler`, `listenRoute`) | ✅ |
| 4 | `SocketPeerTransport` — real TCP direct/LAN data plane | ✅ |
| 5 | Authenticated challenge–response accept handshake | ✅ |
| 6 | Configuration-phase handshake enforcing A0 + rules/registry match | ✅ |
| 7 | `library/rust/nodera-codec` — the second implementation, held honest by fixtures | ✅ |
| 8 | NeoForge relay transport (the permanent in-game fallback lane) | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**One message family, one codec, two implementations.** Every message is `u16 tag + u16 version`
framed. A second implementation in Rust would normally be a liability; here it is the *test*: the
Java side emits golden frames into `fixtures/wire/`, the Rust side re-encodes them byte-exactly, and a
tag-registry mirror assertion fails CI if one side appends alone. Two implementations that must agree
byte-for-byte catch encoding drift that a single implementation cannot.

**Chunked zstd streams exist because of a platform cap, not a preference.** NeoForge payloads are
capped at ~1 MiB clientbound and < 32 KiB serverbound. Rather than pretend the cap does not exist,
the stream layer chunks and compresses beneath it, with a bounded reassembler so a malicious sender
cannot allocate unbounded memory by never finishing a stream.

**A NodeId is not an identity until a key proves it.** A NodeId is a random UUID unrelated to the
Ed25519 key, so admission by NodeId alone let any TCP client claim any identity. The proof therefore
lives in the **transport handshake**, which covers every message — not in a join message, which
covers only the join. This was a deliberate revision of the original design sketch.

**Version guards refuse rather than degrade.** The handshake checks `rulesVersion` and
`registryFingerprint`. A mismatched peer is refused, because a peer that cannot compute comparable
roots is not a participant, and letting it in produces divergence with no clear cause.

## Files

- `library/java/transport/src/main/java/dev/nodera/protocol/codec/MessageCodec.java` (+ `MessageCodecTypeTagTest`)
- `library/java/transport/src/main/java/dev/nodera/transport/{PeerTransport,Frames,Reachability}.java`
- `library/java/transport/src/main/java/dev/nodera/transport/socket/SocketPeerTransport.java`
- `library/rust/nodera-codec/src/`, `library/rust/nodera-codec/tests/{fixtures,tag_mirror}.rs`, `fixtures/wire/`

## Testing

- Golden-fixture round-trip for every message; the Rust mirror re-encodes byte-exactly.
- `MessageCodecTypeTagTest` snapshots the registry.
- `SocketPeerTransportAuthTest` (5, real TCP): key-proven interop, legacy-hello refusal,
  forged-signature refusal, replay/malformed refusal.
- `SocketPeerTransportBindTest`: a bind on a busy port throws `TransportException` and leaves **no**
  listener, while a port-0 bind still succeeds — the invariants the host's elastic retry relies on.
- Bounded-reassembler tests: an unfinished stream cannot grow without limit.

## Acceptance criteria

1. ✅ Every message round-trips byte-exactly in both languages against a shared fixture.
2. ✅ The tag registry is append-only and mirror-checked in CI.
3. ✅ Streams beat the NeoForge payload caps and reassemble under a bound.
4. ✅ A connection's NodeId attribution is cryptographically proven at accept.
5. ✅ A version-mismatched peer is refused, not degraded.
6. ⏳ Live: the in-game relay transport and the real handshake on a client.

## Limitations

**A-4** (NeoForge payload caps) is an envelope constraint whose hiding mechanism lives here — see
[`LIMITATIONS.md`](LIMITATIONS.md) §A. **L-53** (transport key possession) is RETIRED, see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
