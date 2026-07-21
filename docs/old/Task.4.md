# Task 4 — `protocol` + `transport-api` + `transport-neoforge`: Payloads, Streaming, Handshake

**Phase:** 0 · **Depends on:** Task 2 · **Modules:** `protocol`, `transport-api`,
`transport-neoforge`, `neoforge-mod`

## Goal

All Nodera messages defined once (`protocol`), an abstract transport seam
(`transport-api`), and the first concrete transport: NeoForge custom payloads relayed
through the server (`transport-neoforge`) — including the chunked+zstd stream layer that
gets around NeoForge's payload caps (≤1 MiB clientbound, <32 KiB serverbound), and the
configuration-phase handshake that authenticates a client worker.

---

## Folder structure

```
protocol/src/main/java/dev/nodera/protocol/
├── NoderaMessage.java             # sealed root interface: all Nodera wire messages
├── codec/
│   ├── MessageCodec.java          # NoderaMessage ↔ byte[] via core CanonicalEncoder
│   └── ChunkedStreams.java        # split/join: byte[] ↔ List<StreamChunk> (+ zstd)
├── handshake/
│   ├── ClientHello.java           # protocolVersion, NodeId, publicKey, NodeCapabilities,
│   │                              #   rulesVersion, registryFingerprint, signature
│   ├── ServerHello.java           # networkId, currentTick, regionSizeChunks,
│   │                              #   requiredValidators, byte[] challenge
│   ├── ChallengeResponse.java     # signature over challenge
│   └── WorkerActivation.java      # sessionId, maxPrimary, maxReplica, heartbeatTicks
├── assignment/
│   ├── RegionAssigned.java        # RegionId, epoch, RegionReplicaRole, snapshotVersion,
│   │                              #   leaseExpiryTick, committee list
│   ├── RegionRevoked.java         # RegionId, epoch, reason
│   └── LeaseRenewal.java          # RegionId, epoch, newExpiryTick
├── simulationmsg/
│   ├── SnapshotAnnounce.java      # RegionId, version, contentLength, chunkCount, StateRoot
│   ├── StreamChunk.java           # streamId, index, total, byte[] payload  (≤ MAX_CHUNK)
│   ├── ActionBatchMsg.java        # wraps core ActionBatch
│   ├── RegionProposal.java        # region, epoch, baseVersion, tickFrom/To, prevRoot,
│   │                              #   resultingRoot, encodedDelta(byte[]), proposerSig
│   ├── ValidationVote.java        # wraps SignedVote + region/epoch/version
│   ├── CommitAnnounce.java        # region, version, resultingRoot, certificateBytes
│   └── ResyncRequest.java         # region, haveVersion — "send me a fresh snapshot"
├── shadow/
│   ├── ShadowAssignment.java      # Task 5: which regions this client shadow-computes
│   └── ShadowResult.java          # region, batchRef(tickFrom/To,baseVersion), computedRoot, timings
└── health/
    ├── Heartbeat.java             # tick, load stats
    └── WorkerLoad.java            # queue depths, mem, exec nanos

transport-api/src/main/java/dev/nodera/transport/
├── PeerTransport.java             # start/stop; send(PeerAddress, NoderaMessage);
│                                  #   sendStream(PeerAddress, streamId, byte[]);
│                                  #   setHandler(MessageHandler)
├── PeerAddress.java               # opaque: NodeId + transport-specific route
├── MessageHandler.java            # onMessage(PeerAddress, NoderaMessage); onPeerDown(PeerAddress)
└── TransportException.java

transport-neoforge/src/main/java/dev/nodera/transport/neoforge/
├── NeoForgeRelayTransport.java    # PeerTransport impl: server relays client↔client
├── NoderaPayload.java             # record NoderaPayload(byte[] frame) implements CustomPacketPayload
│                                  #   — ONE payload type; frame = MessageCodec bytes
├── PayloadBridge.java             # registrar wiring; splits frames > serverbound cap
├── ServerPayloadHandlers.java     # main-thread + network-thread handlers (server side)
├── ClientPayloadHandlers.java     # client side
└── StreamReassembler.java         # StreamChunk collector w/ timeout + Caffeine-bounded buffers

neoforge-mod additions:
└── dev/nodera/mod/common/ModNetworking.java   # now registers the payload + handshake flow
```

## Class relationships

```
NoderaMessage (sealed) ◄─ every message record above (+ later tasks append — NEVER reorder
                          type tags; tags live in MessageCodec.TypeTags, append-only)

PeerTransport (interface)
      ▲
      ├── NeoForgeRelayTransport      (this task; client↔server native, client↔client relayed)
      └── Libp2pTransport             (Task 10)

MessageCodec ── uses core CanonicalEncoder     # one encoding for hash, sign, AND wire
ChunkedStreams ── zstd-jni ── produces StreamChunk lists; StreamReassembler inverts
```

Design rule: **one NeoForge payload type** (`NoderaPayload`) carrying a self-describing
frame, not one payload class per message. Registrar stays trivial; message evolution is a
`protocol`-module concern; NeoForge sees opaque bytes. (Trade-off accepted: we bypass
per-payload `StreamCodec` niceties; we already have a canonical codec.)

## Implementation details — protocol

- `MessageCodec`: `u16 typeTag + u16 version + body` (same discipline as Task 2). Grows
  append-only.
- `ChunkedStreams`: `MAX_CHUNK = 24 KiB` (safe under the <32 KiB serverbound cap with
  frame overhead). Compress whole logical payload with zstd (level 3 default) *before*
  splitting. Stream id = `(senderNodeId, sequence)`.
- `StreamReassembler`: Caffeine cache keyed by streamId, max weight (bytes) bounded,
  30 s expiry — a malicious/buggy peer cannot balloon memory (Task 0 / Plan §3.13).
  Completed streams → `MessageCodec.decode` → dispatch as a normal message.

## Implementation details — NeoForge mod (`transport-neoforge` + `ModNetworking`)

Registration (play + configuration phases), pattern per NeoForge docs:

```java
@SubscribeEvent
static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar("1");
    registrar.configurationBidirectional(NoderaPayload.TYPE, NoderaPayload.STREAM_CODEC,
        new DirectionalPayloadHandler<>(ClientPayloadHandlers::onConfig, ServerPayloadHandlers::onConfig));
    registrar.playBidirectional(NoderaPayload.TYPE, NoderaPayload.STREAM_CODEC,
        new DirectionalPayloadHandler<>(ClientPayloadHandlers::onPlay, ServerPayloadHandlers::onPlay));
}
```

- `NoderaPayload.STREAM_CODEC` = trivial `byte[]` codec.
- **Thread routing**: payload handlers run on the netty/network thread where registered
  so; decode + reassembly happen there; then dispatch: consensus/coordination messages →
  enqueue to server main thread (`server.execute`) or to the worker executor (client);
  bulk `StreamChunk`s never touch the main thread (Folia lesson: keep heavy work off the
  tick thread).
- **Handshake (configuration phase)**, implemented as a NeoForge configuration task:
  1. Server sends its `ServerHello` container (networkId = stable UUID persisted in
     `SavedData` later; for now generated at boot) with a random 32-byte challenge.
  2. Client replies `ClientHello` (capabilities, public key, rulesVersion,
     registryFingerprint) + `ChallengeResponse` (Ed25519 over challenge ‖ networkId).
  3. Server verifies signature, checks protocolVersion / rulesVersion /
     registryFingerprint match; mismatch ⇒ disconnect with a descriptive reason.
  4. On success: registers the node in an in-memory `NodeRegistry` (Task 6 makes it
     real) and completes the config task; `WorkerActivation` is sent at play-phase start.
  - Vanilla clients without the mod: NeoForge already refuses (payload registration
    mismatch) — verify and record the observed behaviour.
- `NeoForgeRelayTransport`:
  - On the **client**, `PeerAddress` is only ever "the server" or "peer NodeId via
    relay" — the transport wraps messages destined to another client in a
    `RelayEnvelope` (add to `protocol`: `RelayEnvelope(target NodeId, byte[] inner)`).
  - On the **server**, relay handler validates: sender authenticated, target connected,
    rate limits (simple token bucket per sender) — then forwards. Server also injects
    itself as an endpoint (it is a message participant, not just a pipe).

## Implementation details — server peer

- `ServerPayloadHandlers` owns the server-side dispatch table: `NoderaMessage` →
  registered consumer (`Map<Class, BiConsumer<PeerAddress, NoderaMessage>>`), populated
  by later tasks (coordinator, vote collector). This task ships the table + handshake +
  relay + an `EchoTest` message used by acceptance tests.
- Identity: server `NodeIdentity` generated at first boot, persisted under
  `<world>/nodera/server-identity.bin` (plain file for now; Task 9 moves it into the
  peer store). Client identity likewise under the client's game dir
  (`.minecraft/nodera/identity.bin`).

## Acceptance criteria

1. Dev client connects to dev server; handshake completes; server log shows node id,
   capabilities, verified signature. Wrong rulesVersion (forced in a test build) ⇒ clean
   disconnect with reason.
2. `EchoTest` round-trip client↔server, and client↔client via relay, in a two-client dev
   session.
3. Stream test: send an 8 MiB random blob client→server and server→client; reassembled
   bytes hash-equal; no chunk exceeds caps (assert in test); zstd actually applied
   (compressed length < input for compressible data).
4. Reassembler bound test: incomplete streams expire; memory cap honored under a flood of
   bogus streamIds (unit test with fake clock).
5. Unit tests: `MessageCodec` golden vectors per message type; append-only tag test
   (registry snapshot committed, like Task 2).
