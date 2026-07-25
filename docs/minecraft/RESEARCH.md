There are P2P or servidores descentralizada Minecraft servers where all users act as servers, and each player validates the actions of the other players.

Systems close to this idea exist, but **there is no widely used production Minecraft Java/Bedrock implementation in which every player runs the complete server simulation and collectively validates every action**.

The concepts are commonly mixed together:

### 1. P2P connection, but one authoritative host

Mods such as **Seamless – Serverless Multiplayer** establish direct P2P connections and handle NAT traversal, but one player still starts and hosts the Minecraft server. The other players are ordinary clients; they do not independently validate the world state. ([CurseForge][1])

This is effectively:

```text
Player A: client + authoritative server
Player B: client
Player C: client
```

It removes the need for a dedicated hosting provider, but it is not decentralized consensus.

### 2. Distributed Minecraft servers

There have been experimental architectures that divide the Minecraft world into regions and assign each region to a different server. One research proposal divides chunks among servers, replicates their state, and lets the servers collectively reconstruct the global world state. However, the validating nodes are servers—not every player—and each region still has a primary authoritative server. 

An older GitHub experiment called **P2P-Minecraft-server** also attempted to distribute Minecraft processing through proxies and spatial publish/subscribe components. Nevertheless, it still depended on Spigot server instances and was built around Minecraft 1.11.2; it was not a fully replicated player-consensus system. ([GitHub][2])

### 3. Fully decentralized, verifiable multiplayer

The closest general implementation I found is **Playerchains**. Every player's machine becomes a node, exchanges cryptographically signed inputs, maintains action history, and combines histories into a DAG. The game runs through deterministic lockstep with prediction and rollback. It is a proof-of-concept game architecture, not a Minecraft server implementation. ([GitHub][3])

Conceptually, this is what you described:

```text
Player A ─┬─ receives and validates signed actions
Player B ─┼─ receives and validates signed actions
Player C ─┴─ receives and validates signed actions

All nodes execute the same deterministic simulation.
Consensus decides the accepted ordering of actions.
```

There are also generic proof-of-concept libraries such as **Norn**, designed for coordinating P2P game state through a distributed ledger. Its documentation explicitly warns that it is not production-ready. ([GitHub][4])

Minecraft-like blockchain experiments have existed, such as OPCraft, but it was a separate Minecraft-inspired game rather than a decentralized Minecraft Java server.

## Why nobody simply makes every Minecraft player validate everything

Minecraft runs at **20 ticks per second**, leaving approximately 50 milliseconds for each simulation step. In standard Minecraft, one authoritative server processes player commands and distributes the resulting state. 

Requiring global agreement for every movement, block update, entity tick, inventory operation and redstone event creates several problems:

* Consensus latency would frequently exceed one tick.
* Communication grows rapidly as the player count increases.
* Every node would need compatible server code, mods and deterministic random-number generation.
* A slow player could delay everyone.
* Network partitions could create competing versions of the world.
* Malicious users could create many fake validator nodes—a Sybil attack.
* Minecraft plugins frequently use clocks, asynchronous tasks and external databases, making deterministic replay difficult.
* Every player's computer would potentially need to simulate far more of the world than the chunks around them.

A pure all-to-all design approximately produces:

```text
Messages per round ≈ n × (n − 1)
```

With 1,000 players, that can approach one million peer transmissions per consensus round before accounting for retransmission, signatures and state synchronization.

## A practical decentralized Minecraft design

A realistic implementation should **not have every player validate every action**. It should use regional validator committees:

```text
                         Discovery / DHT
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
     Region A              Region B              Region C
   chunks 0–999        chunks 1000–1999       chunks 2000–2999
          │                    │                    │
   5 validators          5 validators          5 validators
          │                    │                    │
   quorum certificate    quorum certificate    quorum certificate
```

A strong design would use:

1. **Signed player inputs**
   Each action contains the player identity, sequence number, tick, target region and cryptographic signature.

2. **Deterministic simulation**
   Validators execute the same action against the same previous state.

3. **Small regional committees**
   Three to seven nodes validate a chunk region instead of involving the entire network.

4. **Local prediction and rollback**
   Movement is shown immediately, while final validation happens asynchronously.

5. **Selective consensus**
   Consensus protects block changes, combat, inventories, containers and economic operations. Ordinary movement can use optimistic validation.

6. **Merkle-root checkpoints**
   Regions periodically publish a cryptographic digest of their state. Other nodes can verify snapshots without replaying the complete history.

7. **Replicated world snapshots**
   Several peers retain chunk snapshots so that the world survives when its current validators disconnect.

8. **Committee rotation**
   Validators change periodically to reduce collusion and prevent one machine from permanently controlling a region.

9. **Conflict rules at region boundaries**
   Cross-chunk pistons, entities, explosions and redstone require an atomic transaction between neighboring committees.

For trusted community nodes, a protocol similar to **Raft** would be simpler. For untrusted player-operated nodes, a Byzantine fault-tolerant protocol such as **HotStuff** or **Tendermint-style BFT** would be more appropriate. CRDTs can help with noncompetitive, mergeable state, but they cannot safely resolve all Minecraft interactions. Recent P2P virtual-environment research demonstrates CRDT synchronization, while explicitly treating Byzantine or malicious behavior as a separate unresolved concern. ([arXiv][5])

## Conclusion

Your idea is technically valid and is generally called a:

**peer-to-peer replicated state machine**,
**decentralized deterministic game simulation**, or
**verifiable P2P multiplayer architecture**.

However:

* P2P Minecraft hosting exists.
* Distributed Minecraft server prototypes exist.
* Verifiable P2P game prototypes exist.
* A mature Minecraft implementation where every player's computer validates every other player's actions does not appear to exist.

The technically viable direction would be a **Fabric client/server mod plus a custom P2P protocol**, with chunk-based sharding and small validator committees—not an ordinary Paper/Spigot plugin and not global validation by every connected player.

[1]: https://www.curseforge.com/minecraft/mc-mods/seamless-serverless-multiplayer "Seamless – Serverless Multiplayer - Minecraft Mods - CurseForge"
[2]: https://github.com/MiguelSmith/P2P-Minecraft-server "GitHub - MiguelSmith/P2P-Minecraft-server · GitHub"
[3]: https://github.com/playmint/playerchain-demo "GitHub - playmint/playerchain-demo: A Playerchains proof of concept demo game. P2P, responsive, multiplayer without any third parties, via DAG consensus. · GitHub"
[4]: https://github.com/jspahrsummers/norn "GitHub - jspahrsummers/norn: Embeddable blockchain library for low-latency P2P multiplayer games (proof of concept) · GitHub"
[5]: https://arxiv.org/abs/2503.17826 "CRDT-Based Game State Synchronization in Peer-to-Peer VR"

Design a project structure using a NeoForge mod architecture; identify the necessary libraries to control client behavior and launch it as part of the server; organize the system into chunks, with each player responsible for a specific region of the game.

## Recommended architecture

Use a **hybrid decentralized architecture**:

```text
                     NeoForge dedicated server
               ┌────────────────────────────────┐
               │ Coordinator / canonical state  │
               │                                │
               │ • assigns regions              │
               │ • orders player actions        │
               │ • collects validation votes    │
               │ • applies accepted deltas      │
               │ • saves the world              │
               │ • replaces disconnected peers  │
               └───────────────┬────────────────┘
                               │ control plane
              ┌────────────────┼────────────────┐
              │                │                │
       Player A client   Player B client   Player C client
       Region worker     Region worker     Region worker
       Region (0,0)      Region (1,0)      Region (0,1)
              │                │                │
              └──────── optional P2P data plane ────────┘
```

The server should initially remain the **canonical commit authority**, while players execute and validate region simulation. This is significantly safer than allowing one client to directly mutate authoritative chunks.

Minecraft still separates logical client and logical server: world updates, entity ticking, inventories, time and weather normally belong to the logical server. A dedicated server also lacks client-only classes, so client functionality must remain isolated in client packages. ([NeoForged Documentation][1])

## Critical security decision

Do not make one player the sole authority for a region.

Assign:

```text
Region:
  1 primary executor
  2 validation replicas
  server fallback executor
```

A result is committed when:

```text
primary result == validator A result
or
primary result == validator B result
or
validator A result == validator B result
```

For an initial prototype:

```text
quorum = 2 of 3
```

The player “responsible” for a region is therefore its primary executor, not its unrestricted owner.

---

# Repository structure

I recommend a multi-module Gradle project producing one final NeoForge mod JAR.

```text
decentralized-minecraft/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│
├── build-logic/
│   └── src/main/kotlin/
│       ├── java-library-conventions.gradle.kts
│       └── neoforge-mod-conventions.gradle.kts
│
├── core/
│   └── src/main/java/dev/ashu/decentralized/core/
│       ├── identity/
│       │   ├── NodeId.java
│       │   ├── NodeIdentity.java
│       │   └── NodeCapabilities.java
│       │
│       ├── region/
│       │   ├── RegionId.java
│       │   ├── RegionBounds.java
│       │   ├── RegionLease.java
│       │   ├── RegionEpoch.java
│       │   ├── RegionReplicaRole.java
│       │   └── RegionAssignment.java
│       │
│       ├── action/
│       │   ├── GameAction.java
│       │   ├── ActionEnvelope.java
│       │   ├── ActionSequence.java
│       │   └── ActionBatch.java
│       │
│       ├── state/
│       │   ├── RegionSnapshot.java
│       │   ├── RegionDelta.java
│       │   ├── StateRoot.java
│       │   ├── ChunkDigest.java
│       │   └── QuorumCertificate.java
│       │
│       └── crypto/
│           ├── SignatureService.java
│           ├── HashService.java
│           └── CanonicalEncoder.java
│
├── protocol/
│   └── src/main/java/dev/ashu/decentralized/protocol/
│       ├── codec/
│       │   ├── ProtocolCodecs.java
│       │   ├── SnapshotCodec.java
│       │   └── CanonicalActionCodec.java
│       │
│       ├── handshake/
│       │   ├── ClientHelloPayload.java
│       │   ├── ServerHelloPayload.java
│       │   ├── CapabilityPayload.java
│       │   └── WorkerActivationPayload.java
│       │
│       ├── assignment/
│       │   ├── RegionAssignedPayload.java
│       │   ├── RegionRevokedPayload.java
│       │   └── LeaseRenewalPayload.java
│       │
│       ├── simulation/
│       │   ├── SnapshotPayload.java
│       │   ├── ActionBatchPayload.java
│       │   ├── RegionProposalPayload.java
│       │   ├── ValidationVotePayload.java
│       │   └── CommitPayload.java
│       │
│       └── health/
│           ├── HeartbeatPayload.java
│           ├── WorkerLoadPayload.java
│           └── ResyncRequestPayload.java
│
├── simulation/
│   └── src/main/java/dev/ashu/decentralized/simulation/
│       ├── DeterministicRegionEngine.java
│       ├── RegionExecutionContext.java
│       ├── RegionInput.java
│       ├── RegionExecutionResult.java
│       ├── DeterministicRandom.java
│       ├── BlockMutationBuffer.java
│       ├── EntityMutationBuffer.java
│       ├── InventoryMutationBuffer.java
│       └── border/
│           ├── RegionHalo.java
│           ├── BorderEvent.java
│           └── CrossRegionTransaction.java
│
├── consensus/
│   └── src/main/java/dev/ashu/decentralized/consensus/
│       ├── Proposal.java
│       ├── Vote.java
│       ├── VoteCollector.java
│       ├── QuorumPolicy.java
│       ├── CommitDecision.java
│       └── EquivocationDetector.java
│
├── transport-api/
│   └── src/main/java/dev/ashu/decentralized/transport/
│       ├── PeerTransport.java
│       ├── PeerConnection.java
│       ├── MessageHandler.java
│       └── TransportAddress.java
│
├── transport-neoforge/
│   └── src/main/java/dev/ashu/decentralized/transport/neoforge/
│       ├── NeoForgeTransport.java
│       ├── PayloadRegistration.java
│       ├── ServerPayloadHandlers.java
│       └── ClientPayloadHandlers.java
│
├── transport-libp2p/
│   └── src/main/java/dev/ashu/decentralized/transport/libp2p/
│       ├── Libp2pTransport.java
│       ├── PeerDiscovery.java
│       ├── RelayManager.java
│       └── NatTraversalManager.java
│
├── neoforge-mod/
│   ├── src/main/java/dev/ashu/decentralized/
│   │   ├── DecentralizedMinecraftMod.java
│   │   │
│   │   ├── common/
│   │   │   ├── ModConfiguration.java
│   │   │   ├── ModAttachments.java
│   │   │   └── ModNetworking.java
│   │   │
│   │   ├── server/
│   │   │   ├── ServerBootstrap.java
│   │   │   ├── coordinator/
│   │   │   │   ├── NetworkCoordinator.java
│   │   │   │   ├── NodeRegistry.java
│   │   │   │   ├── RegionAllocator.java
│   │   │   │   ├── LeaseManager.java
│   │   │   │   └── HeartbeatMonitor.java
│   │   │   │
│   │   │   ├── routing/
│   │   │   │   ├── ActionRouter.java
│   │   │   │   ├── RegionCommandQueue.java
│   │   │   │   └── CrossRegionRouter.java
│   │   │   │
│   │   │   ├── commit/
│   │   │   │   ├── ProposalManager.java
│   │   │   │   ├── RegionCommitter.java
│   │   │   │   └── WorldMutationApplier.java
│   │   │   │
│   │   │   ├── persistence/
│   │   │   │   ├── NetworkSavedData.java
│   │   │   │   ├── CheckpointStore.java
│   │   │   │   └── ActionJournal.java
│   │   │   │
│   │   │   └── fallback/
│   │   │       ├── ServerRegionExecutor.java
│   │   │       └── FailedRegionRecovery.java
│   │   │
│   │   └── client/
│   │       ├── ClientBootstrap.java
│   │       ├── WorkerRuntime.java
│   │       ├── WorkerState.java
│   │       ├── ClientCapabilityCollector.java
│   │       ├── replica/
│   │       │   ├── RegionReplica.java
│   │       │   ├── ReplicaRepository.java
│   │       │   └── SnapshotReceiver.java
│   │       ├── execution/
│   │       │   ├── ClientRegionExecutor.java
│   │       │   ├── ProposalProducer.java
│   │       │   └── ProposalValidator.java
│   │       └── debug/
│   │           ├── RegionOverlayRenderer.java
│   │           └── WorkerStatusScreen.java
│   │
│   └── src/main/resources/
│       ├── META-INF/neoforge.mods.toml
│       ├── decentralized_minecraft.mixins.json
│       └── assets/decentralized_minecraft/
│
├── testkit/
│   └── src/test/java/dev/ashu/decentralized/testkit/
│       ├── FakeRegion.java
│       ├── FakePeer.java
│       ├── DeterminismTest.java
│       ├── RegionFailoverTest.java
│       └── ByzantineWorkerTest.java
│
└── integration-tests/
    ├── three-client-quorum/
    ├── peer-disconnection/
    ├── invalid-state-root/
    └── cross-region-explosion/
```

The separation between `common`, `client` and `server` matters because accessing `net.minecraft.client` classes on a dedicated server can crash due to missing client classes. NeoForge explicitly recommends isolating client-only code. ([NeoForged Documentation][1])

---

# Region model

## Region dimensions

Start with:

```text
1 region = 8 × 8 chunks
         = 64 chunks
         = 128 × 128 horizontal blocks
```

Add a one-chunk simulation halo:

```text
┌──────────────────────────┐
│        halo chunks       │
│  ┌────────────────────┐  │
│  │                    │  │
│  │   owned 8×8 area   │  │
│  │                    │  │
│  └────────────────────┘  │
│        halo chunks       │
└──────────────────────────┘
```

The halo allows the worker to evaluate:

* neighbor block state;
* entity movement across boundaries;
* fluids;
* redstone adjacency;
* explosions;
* scheduled ticks near a border.

The worker may read halo state but cannot commit mutations outside its owned region.

## Region identity

```java
public record RegionId(
    ResourceKey<Level> dimension,
    int regionX,
    int regionZ
) {
    public static final int REGION_SIZE_CHUNKS = 8;

    public static RegionId fromChunk(
        ResourceKey<Level> dimension,
        ChunkPos chunk
    ) {
        return new RegionId(
            dimension,
            Math.floorDiv(chunk.x, REGION_SIZE_CHUNKS),
            Math.floorDiv(chunk.z, REGION_SIZE_CHUNKS)
        );
    }
}
```

Use `Math.floorDiv`, especially because Minecraft coordinates can be negative.

## Region lease

```java
public record RegionLease(
    RegionId region,
    long epoch,
    UUID primary,
    List<UUID> validators,
    long validFromTick,
    long expiresAtTick
) {
    public boolean contains(UUID nodeId) {
        return primary.equals(nodeId) || validators.contains(nodeId);
    }
}
```

The `epoch` prevents a disconnected former owner from continuing to submit proposals after reassignment.

---

# Assigning players to regions

Use **weighted rendezvous hashing** rather than assigning only by proximity.

Inputs:

```java
public record NodeCapabilities(
    int logicalCores,
    long availableMemoryBytes,
    int measuredLatencyMs,
    double historicalReliability,
    int maximumRegions,
    boolean acceptsWorkerRole
) {}
```

A simplified score:

```text
score(node, region) =
    stableHash(nodeId, regionId)
    × capacityWeight
    × reliabilityWeight
    × latencyWeight
```

Suggested policy:

```text
primary:
    highest eligible score

validator 1:
    next highest score, different network address when possible

validator 2:
    next highest score, different network address when possible
```

Do not assign the same player:

* the primary role for too many adjacent regions;
* all validation roles for neighboring regions;
* a region containing only their own actions without independent validators.

## Assignment constraints

```java
public interface RegionPlacementPolicy {
    boolean canAssign(
        NodeDescriptor node,
        RegionId region,
        RegionReplicaRole role,
        AssignmentSnapshot current
    );

    double score(
        NodeDescriptor node,
        RegionId region,
        RegionReplicaRole role
    );
}
```

Recommended constraints:

```text
maximum primary regions per player: 1–4
maximum validation regions: 2–8
minimum reliability: 0.95
maximum heartbeat latency: 2 seconds
lease length: 200 ticks
lease renewal: every 40 ticks
```

The exact numbers should be configurable.

---

# How the client worker is launched

Distribute the **same final mod JAR** to the dedicated server and every client.

NeoForge allows a mod entrypoint to load on both physical sides, while also permitting separate client-only entrypoints. ([NeoForged Documentation][2])

```java
@Mod(DecentralizedMinecraftMod.MOD_ID)
public final class DecentralizedMinecraftMod {
    public static final String MOD_ID = "decentralized_minecraft";

    public DecentralizedMinecraftMod(
        IEventBus modBus,
        ModContainer container,
        Dist dist
    ) {
        ModNetworking.register(modBus);
        ModAttachments.register(modBus);

        if (dist == Dist.DEDICATED_SERVER) {
            ServerBootstrap.register();
        }
    }
}
```

Client-only entrypoint:

```java
@Mod(
    value = DecentralizedMinecraftMod.MOD_ID,
    dist = Dist.CLIENT
)
public final class DecentralizedMinecraftClientMod {
    public DecentralizedMinecraftClientMod(
        IEventBus modBus,
        ModContainer container
    ) {
        ClientBootstrap.register(modBus, container);
    }
}
```

After connection:

```text
Client connects
    ↓
configuration handshake
    ↓
client sends capabilities
    ↓
server authenticates and registers node
    ↓
server sends WorkerActivationPayload
    ↓
client starts WorkerRuntime
    ↓
server sends RegionAssignedPayload
    ↓
client downloads snapshot and begins execution
```

The server does not remotely install code or launch arbitrary executables. The already-installed client mod receives an activation message and starts its in-process worker.

```java
public final class WorkerRuntime implements AutoCloseable {
    private final ExecutorService executor;
    private final AtomicReference<WorkerState> state;

    public WorkerRuntime() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.state = new AtomicReference<>(WorkerState.INACTIVE);
    }

    public void activate() {
        if (!state.compareAndSet(
            WorkerState.INACTIVE,
            WorkerState.ACTIVE
        )) {
            return;
        }
    }

    public CompletableFuture<RegionExecutionResult> execute(
        RegionExecutionRequest request
    ) {
        if (state.get() != WorkerState.ACTIVE) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Worker is inactive")
            );
        }

        return CompletableFuture.supplyAsync(
            () -> request.engine().execute(request.input()),
            executor
        );
    }

    @Override
    public void close() {
        state.set(WorkerState.STOPPED);
        executor.close();
    }
}
```

Do not start another complete `MinecraftServer` instance inside each client for the first version. Running a second world simulation inside the physical client creates lifecycle, memory, mod-compatibility and synchronization problems. Use a purpose-built deterministic region engine instead.

---

# NeoForge networking

Use NeoForge payloads for the initial implementation.

Payloads are registered through `RegisterPayloadHandlersEvent`, represented as `CustomPacketPayload` records and encoded through `StreamCodec`. NeoForge supports play-phase, configuration-phase and bidirectional registrations. ([NeoForged Documentation][3])

```java
@EventBusSubscriber(modid = DecentralizedMinecraftMod.MOD_ID)
public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "1";

    @SubscribeEvent
    public static void registerPayloads(
        RegisterPayloadHandlersEvent event
    ) {
        PayloadRegistrar main = event.registrar(PROTOCOL_VERSION);

        main.configurationToServer(
            ClientHelloPayload.TYPE,
            ClientHelloPayload.STREAM_CODEC,
            ServerHandshakeHandler::handleHello
        );

        main.playToServer(
            RegionProposalPayload.TYPE,
            RegionProposalPayload.STREAM_CODEC,
            ServerProposalHandler::handle
        );

        main.playToServer(
            ValidationVotePayload.TYPE,
            ValidationVotePayload.STREAM_CODEC,
            ServerVoteHandler::handle
        );

        main.playToServer(
            HeartbeatPayload.TYPE,
            HeartbeatPayload.STREAM_CODEC,
            ServerHeartbeatHandler::handle
        );
    }
}
```

Client registration:

```java
@EventBusSubscriber(
    modid = DecentralizedMinecraftMod.MOD_ID,
    value = Dist.CLIENT
)
public final class ClientNetworking {
    @SubscribeEvent
    public static void registerClientPayloads(
        RegisterClientPayloadHandlersEvent event
    ) {
        event.register(
            WorkerActivationPayload.TYPE,
            WorkerActivationPayloadHandler::handle
        );

        event.register(
            RegionAssignedPayload.TYPE,
            RegionAssignmentPayloadHandler::handle
        );

        event.register(
            ActionBatchPayload.TYPE,
            HandlerThread.NETWORK,
            ActionBatchPayloadHandler::handle
        );
    }
}
```

NeoForge payload handlers normally execute on the main thread, but handlers can be registered for the network thread and later enqueue only the required world mutations onto the main thread. This distinction is essential because region simulation must not block the render or server tick threads. ([NeoForged Documentation][3])

NeoForge also imposes payload-size limits: clientbound custom payloads are limited to 1 MiB and serverbound payloads to less than 32 KiB. Large region snapshots therefore need chunking, compression or a secondary transport. ([NeoForged Documentation][3])

---

# Protocol messages

## Handshake

```java
public record ClientHelloPayload(
    int protocolVersion,
    UUID nodeId,
    byte[] publicKey,
    NodeCapabilities capabilities,
    byte[] signature
) implements CustomPacketPayload {}
```

The server responds with:

```java
public record ServerHelloPayload(
    UUID networkId,
    long currentTick,
    int regionSizeChunks,
    int requiredValidatorCount,
    byte[] challenge
) implements CustomPacketPayload {}
```

The challenge prevents another user from simply copying a node identity.

## Worker activation

```java
public record WorkerActivationPayload(
    UUID sessionId,
    int maximumPrimaryRegions,
    int maximumReplicaRegions,
    long heartbeatIntervalTicks
) implements CustomPacketPayload {}
```

## Assignment

```java
public record RegionAssignedPayload(
    RegionId region,
    long epoch,
    RegionReplicaRole role,
    long snapshotVersion,
    long leaseExpirationTick,
    List<UUID> committee
) implements CustomPacketPayload {}
```

## Proposal

```java
public record RegionProposalPayload(
    RegionId region,
    long epoch,
    long baseVersion,
    long tickFrom,
    long tickTo,
    byte[] previousStateRoot,
    byte[] resultingStateRoot,
    byte[] encodedDelta,
    byte[] proposerSignature
) implements CustomPacketPayload {}
```

## Vote

```java
public enum VoteDecision {
    ACCEPT,
    REJECT_STATE_ROOT,
    REJECT_INVALID_ACTION,
    REJECT_WRONG_EPOCH,
    RESYNC_REQUIRED
}

public record ValidationVotePayload(
    RegionId region,
    long epoch,
    long proposedVersion,
    byte[] resultingStateRoot,
    VoteDecision decision,
    byte[] validatorSignature
) implements CustomPacketPayload {}
```

---

# Simulation workflow

## Tick processing

Do not perform network consensus independently for every individual Minecraft tick. Group several ticks or actions into short batches.

Suggested initial values:

```text
Minecraft tick rate:       20 ticks/second
execution batch:           2 ticks
maximum batch duration:    100 ms
checkpoint interval:       100 ticks
heartbeat interval:        20–40 ticks
```

Flow:

```text
1. Server receives player action
2. Server validates basic legality
3. Server assigns global sequence number
4. Server routes action to the region committee
5. Primary and validators execute the same action batch
6. Primary sends RegionProposal
7. Validators send hashes and votes
8. Server forms quorum certificate
9. Server applies RegionDelta
10. Server broadcasts committed result
```

## Action envelope

```java
public record ActionEnvelope(
    UUID actor,
    long playerSequence,
    long serverSequence,
    long targetTick,
    RegionId region,
    GameAction action,
    byte[] actorSignature
) {}
```

Examples of `GameAction`:

```java
public sealed interface GameAction permits
    MoveAction,
    BreakBlockAction,
    PlaceBlockAction,
    InteractBlockAction,
    AttackEntityAction,
    InventoryAction,
    UseItemAction {}
```

Do not initially distribute every vanilla tick. Start only with discrete, externally observable actions:

1. block placement;
2. block breaking;
3. container modifications;
4. item transfers;
5. combat;
6. entity spawning;
7. simple entity movement.

Delay these systems until later:

* complex redstone;
* fluids;
* random ticks;
* mob AI;
* portals;
* commands;
* modded machines;
* cross-dimensional entities.

---

# Deterministic execution

This is the hardest component.

Every replica must receive:

```text
same starting state
same ordered actions
same random seed
same simulation version
same registry configuration
same mod list
same configuration values
```

## Deterministic context

```java
public record RegionExecutionContext(
    RegionId region,
    long epoch,
    long baseVersion,
    long tick,
    long deterministicSeed,
    RegistryFingerprint registryFingerprint
) {}
```

Never use:

```java
System.currentTimeMillis();
System.nanoTime();
ThreadLocalRandom.current();
UUID.randomUUID();
unordered HashMap iteration;
local filesystem state;
external HTTP requests;
```

Instead:

```java
public final class DeterministicRandom {
    private final RandomGenerator generator;

    public DeterministicRandom(
        long worldSeed,
        RegionId region,
        long tick,
        long actionSequence
    ) {
        long seed = stableHash(
            worldSeed,
            region.dimension().location(),
            region.regionX(),
            region.regionZ(),
            tick,
            actionSequence
        );

        this.generator = RandomGeneratorFactory
            .<RandomGenerator>of("L64X128MixRandom")
            .create(seed);
    }
}
```

Do not hash raw Java object serialization. Define a canonical binary representation where field order, integer encoding and collection ordering are fixed.

---

# Region state and deltas

Do not transmit an entire chunk after every batch.

```java
public record RegionDelta(
    RegionId region,
    long baseVersion,
    long resultingVersion,
    List<BlockMutation> blockMutations,
    List<BlockEntityMutation> blockEntityMutations,
    List<EntityMutation> entityMutations,
    List<InventoryMutation> inventoryMutations,
    List<ScheduledTickMutation> scheduledTicks,
    byte[] resultingStateRoot
) {}
```

Example block mutation:

```java
public record BlockMutation(
    BlockPos position,
    int expectedPreviousStateId,
    int newStateId,
    int flags
) {}
```

The `expectedPreviousStateId` makes commit application compare-and-set:

```java
if (currentStateId != mutation.expectedPreviousStateId()) {
    rejectAndResync(region);
    return;
}
```

This prevents applying a delta against an unexpected base world.

---

# Persistence strategy

Use two NeoForge storage mechanisms:

## Chunk data attachments

Store region-related metadata directly on chunks:

```java
public record ChunkNetworkMetadata(
    RegionId region,
    long committedVersion,
    byte[] stateRoot,
    long lastCheckpointTick
) {}
```

NeoForge data attachments can persist custom data on chunks, entities, block entities and levels. Persistent chunk changes are automatically marked unsaved when changed through the attachment API. ([NeoForged Documentation][4])

Useful attachments:

```text
chunk:
  region ID
  committed version
  chunk state hash
  last checkpoint

player entity:
  node identity
  reliability score
  current worker status
```

## Level-wide `SavedData`

Use `SavedData` for:

```text
region assignments
current epochs
node reliability history
active leases
network ID
latest global checkpoint
quorum configuration
```

`SavedData` is intended for additional level-wide data and must be marked dirty when modified so it is written to disk. ([NeoForged Documentation][5])

```java
public final class NetworkSavedData extends SavedData {
    private final Map<RegionId, PersistedRegionAssignment> assignments;
    private final Map<UUID, PersistedNodeReputation> reputations;

    public void updateAssignment(
        RegionId region,
        PersistedRegionAssignment assignment
    ) {
        assignments.put(region, assignment);
        setDirty();
    }
}
```

---

# Cross-region operations

Cross-region events cannot be committed independently.

Examples:

* player crosses a boundary;
* piston moves a block into another region;
* explosion touches multiple regions;
* entity attacks across a boundary;
* hopper transfers items across regions;
* fluid propagates across a boundary.

Use a server-coordinated transaction:

```text
PREPARE region A
PREPARE region B
        ↓
both regions produce compatible deltas
        ↓
server validates expected versions
        ↓
COMMIT A and B atomically
```

Model:

```java
public record CrossRegionTransaction(
    UUID transactionId,
    Set<RegionId> participants,
    Map<RegionId, Long> expectedVersions,
    List<GameAction> actions,
    long expirationTick
) {}
```

For the first prototype, route all cross-region operations to the server fallback executor rather than attempting distributed atomic commits.

---

# Client control boundaries

The server may require the client mod to:

* activate or deactivate worker mode;
* execute region batches;
* retain snapshots;
* submit proofs;
* render debug overlays;
* report CPU and memory limits;
* relinquish assignments;
* resynchronize corrupted replicas.

The server should not allow the worker to:

* directly edit the local client world as canonical state;
* choose its own region;
* choose action ordering;
* invent snapshots;
* change lease epochs;
* commit results;
* assign validators;
* bypass server-side interaction checks.

The client worker computes a proposal. Only the server applies accepted mutations to the actual `ServerLevel`.

---

# Necessary libraries

## Required

### NeoForge

Use for:

* mod lifecycle;
* side-specific entrypoints;
* event system;
* networking payloads;
* `StreamCodec`;
* chunk attachments;
* `SavedData`;
* configuration;
* client overlays;
* player connection lifecycle.

NeoForge payloads are built on Minecraft’s packet system, and convenience distributors can target an individual player, all players or players tracking a chunk. ([NeoForged Documentation][3])

### Java standard cryptography

Use the JDK directly:

```text
Ed25519       signatures
SHA-256       state roots initially
SecureRandom  identity generation
KeyPairGenerator
Signature
MessageDigest
```

No external cryptography library is necessary for the first version.

### Java concurrency

Use:

```text
ExecutorService
CompletableFuture
virtual threads
ConcurrentHashMap
BlockingQueue
StampedLock
```

Never mutate Minecraft world state from a worker thread. Return a `RegionDelta`, then enqueue its application on the server main thread.

## Strongly recommended

### Caffeine

Purpose:

```text
bounded snapshot cache
proposal deduplication
recent action cache
peer penalty cache
```

Avoid unbounded maps controlled by remote payloads.

### Zstandard JNI

Purpose:

```text
region snapshot compression
checkpoint compression
large delta compression
```

Snapshots will frequently exceed normal serverbound payload limits, so compression and segmentation are necessary.

### RoaringBitmap

Purpose:

```text
changed chunk sections
dirty block indexes
entity-presence indexes
snapshot difference masks
```

It is more compact than sending thousands of individual booleans or block indexes.

### fastutil

Purpose:

```text
primitive-key collections
long-to-object chunk maps
integer state ID maps
reduced allocation during simulation
```

Shade your selected version rather than assuming Minecraft’s internal version is stable.

## Optional P2P transport

### Phase 1: NeoForge relay

Initially send everything through:

```text
client → server → other clients
```

Advantages:

* no NAT traversal;
* simpler authentication;
* simpler debugging;
* server controls bandwidth;
* no extra native libraries;
* no peer address exposure.

This is distributed computation, but not direct peer networking.

### Phase 2: libp2p

libp2p provides encrypted connections, multiplexed protocols, multiple transports and NAT traversal mechanisms such as hole punching and relay support. ([libp2p][6])

Possible JVM dependency:

```kotlin
dependencies {
    implementation("io.libp2p:jvm-libp2p:<pinned-version>")
}
```

However, the JVM implementation’s own repository states that its maintainers are not aware of production deployments. Treat it as experimental and isolate it behind `PeerTransport`. ([GitHub][7])

For a serious production system, I would consider:

```text
NeoForge Java mod
        ↕ local authenticated IPC
Rust libp2p sidecar
```

This keeps Minecraft integration in Java while using a more mature libp2p implementation for direct peer networking.

## Testing

Use:

```text
JUnit 5
AssertJ
jqwik
Mockito only at boundaries
JMH
```

Most important tests:

```text
same snapshot + same actions = same state root
different action order = different state root
stale epoch is rejected
duplicate proposal is idempotent
malicious validator cannot form quorum alone
primary disconnection reassigns region
cross-region transaction cannot partially commit
negative chunk coordinates map correctly
```

---

# Mixin usage

NeoForge supports declaring Mixin configurations in `neoforge.mods.toml`. ([NeoForged Documentation][2])

You may eventually need Mixins for:

* intercepting chunk tick scheduling;
* preventing the canonical server from executing offloaded regions twice;
* capturing scheduled ticks;
* capturing random tick inputs;
* intercepting entity ticking by region;
* atomically applying mutation batches.

Suggested mixins:

```text
MinecraftServerMixin
ServerLevelMixin
ServerChunkCacheMixin
LevelChunkMixin
EntityTickListMixin
LevelTicksMixin
```

Do not begin by replacing the whole chunk tick loop.

First use Mixins only as observation hooks:

```text
capture action
capture scheduled tick
capture entity transition
measure execution
compare client-computed result with server result
```

Only after determinism is proven should the system disable server-side execution for delegated regions.

---

# Development phases

## Phase 1 — Shadow validation

```text
Server executes everything normally.
Clients independently recompute selected actions.
Results are compared but never committed.
```

Goal:

```text
prove deterministic execution
measure bandwidth
detect divergence sources
```

## Phase 2 — Client proposal, server verification

```text
Client executes region.
Server independently verifies.
Server commits only matching result.
```

This does not reduce server CPU much, but tests correctness.

## Phase 3 — Committee validation

```text
Primary + two validators execute.
Server verifies hashes and applies delta.
Server no longer fully reexecutes every action.
```

## Phase 4 — Server fallback only

```text
Client committee executes most regions.
Server executes only:
  unassigned regions
  disputed proposals
  cross-region actions
  disconnected regions
```

## Phase 5 — Direct P2P data plane

```text
Server sends assignments and final commits.
Peers exchange:
  snapshots
  action batches
  proposals
  votes
directly through P2P.
```

---

# Recommended first implementation scope

Build the MVP around a restricted game mode:

```text
fixed flat world
no mobs
no redstone
no fluids
no portals
limited block set
no arbitrary commands
no other gameplay mods
region size 8×8 chunks
three replicas per active region
server-relayed networking
block placement and breaking only
```

The first milestone should be:

```text
Three clients connect.

Client A is assigned primary for region (0,0).
Clients B and C are assigned validators.

A player places a block.

All three workers calculate the same RegionDelta and state root.

The server receives two matching votes and applies the block.

When client A disconnects, client B becomes primary under a new epoch.
```

That gives you a real decentralized region execution prototype without immediately attempting to reproduce every detail of the Minecraft server simulation.

[1]: https://docs.neoforged.net/docs/concepts/sides/ "Sides | NeoForged docs"
[2]: https://docs.neoforged.net/docs/gettingstarted/modfiles/ "Mod Files | NeoForged docs"
[3]: https://docs.neoforged.net/docs/networking/payload/ "Registering Payloads | NeoForged docs"
[4]: https://docs.neoforged.net/docs/datastorage/attachments/ "Data Attachments | NeoForged docs"
[5]: https://docs.neoforged.net/docs/1.21.5/datastorage/saveddata "Saved Data | NeoForged docs"
[6]: https://libp2p.io/ "libp2p - A modular network stack | libp2p"
[7]: https://github.com/libp2p/jvm-libp2p "GitHub - libp2p/jvm-libp2p: a libp2p implementation for the JVM, written in Kotlin  · GitHub"

Add a server to this plan to serve as a base; it will act essentially as a system peer, serving as an entry point to the P2P network and functioning as a seeder for the world's data (much like a standard network user). While it must validate user actions just as other peers do, a key difference is that it must store and retain all modified world information—such as entities and events. It acts as an essential P2P peer and seeder, yet it is designed so that even if it disconnects from the network, clients can continue to operate without it, as it is ultimately just a conventional user.

## Revised architecture: full archival bootstrap peer

The dedicated server becomes a **full peer** with additional storage and discovery responsibilities, but no additional consensus authority.

```text
                    Public bootstrap/full peer
              ┌──────────────────────────────────┐
              │ NeoForge dedicated server        │
              │                                  │
              │ Roles:                           │
              │ • bootstrap peer                 │
              │ • P2P relay                      │
              │ • full archival node             │
              │ • world-data seeder              │
              │ • ordinary region validator      │
              │ • optional region executor       │
              │ • initial session gateway        │
              │                                  │
              │ Consensus weight: one peer       │
              └───────────────┬──────────────────┘
                              │
                    P2P overlay network
          ┌───────────────────┼───────────────────┐
          │                   │                   │
    Player peer A       Player peer B       Player peer C
    partial archive     partial archive     partial archive
    region primary      validator           validator
    gateway-capable     gateway-capable     gateway-capable
          │                   │                   │
          └───────────────────┴───────────────────┘

If the full peer disconnects:
    • existing peer links remain active;
    • committees continue validating;
    • another gateway is selected;
    • archival shards remain replicated;
    • the full peer catches up when it returns.
```

The dedicated server should be treated as a **preferred full node**, not a central server.

---

# 1. Peer roles

Every installation runs the same logical `PeerRuntime`. Capabilities determine which roles it can perform.

```java
public enum PeerRole {
    BOOTSTRAP,
    RELAY,
    SESSION_GATEWAY,
    REGION_EXECUTOR,
    REGION_VALIDATOR,
    PARTIAL_ARCHIVE,
    FULL_ARCHIVE,
    WORLD_SEEDER
}
```

A client might advertise:

```text
REGION_EXECUTOR
REGION_VALIDATOR
PARTIAL_ARCHIVE
SESSION_GATEWAY
```

The dedicated server advertises:

```text
BOOTSTRAP
RELAY
SESSION_GATEWAY
REGION_EXECUTOR
REGION_VALIDATOR
FULL_ARCHIVE
WORLD_SEEDER
```

The role set is descriptive. It must not give the server additional voting power.

```java
public record PeerCapabilities(
    Set<PeerRole> roles,
    int logicalProcessors,
    long storageCapacityBytes,
    long availableMemoryBytes,
    int maximumPrimaryRegions,
    int maximumValidationRegions,
    boolean acceptsInboundConnections
) {
    public boolean supports(PeerRole role) {
        return roles.contains(role);
    }
}
```

---

# 2. Responsibilities of the base server

## Bootstrap peer

The server exposes a stable public address used by new clients to discover the network.

It returns:

* network identifier;
* genesis world hash;
* protocol version;
* current peer addresses;
* latest finalized checkpoints;
* region committee assignments;
* mod and registry fingerprints;
* available snapshot seeders.

After discovery, clients establish direct P2P links and no longer depend on the bootstrap connection.

## Full archival peer

It retains the complete committed world history:

* every region snapshot;
* every committed delta;
* entity creation, mutation and removal;
* scheduled ticks and delayed events;
* cross-region transactions;
* committee assignments;
* quorum certificates;
* player-visible world events;
* dimension metadata;
* chunk state roots;
* world checkpoints.

## Seeder

It supplies world data to:

* new clients;
* reconnecting clients;
* peers assigned to new regions;
* peers recovering damaged storage;
* new archival replicas.

## Ordinary validator

It participates in committees exactly like a player peer.

```text
Base server vote = one vote
Player A vote    = one vote
Player B vote    = one vote
```

It cannot:

* commit alone;
* override a quorum;
* reorder finalized actions;
* alter an accepted checkpoint;
* assign itself permanent ownership;
* create state without a quorum certificate.

## Initial session gateway

The server initially translates finalized P2P state into the packets and local state transitions expected by Minecraft clients.

This gateway role must be transferable to another peer.

---

# 3. Separate the control, consensus and data planes

```text
Control plane
    peer discovery
    capabilities
    region assignments
    gateway election
    heartbeats

Consensus plane
    ordered actions
    proposals
    validation votes
    quorum certificates
    checkpoints

Data plane
    snapshots
    chunk sections
    entities
    event logs
    archive replication
```

The bootstrap peer can help with all three planes while online, but the P2P network must have alternative paths for each.

---

# 4. Authoritative state model

There should be no globally mutable server-owned world object.

The authoritative state becomes:

```text
Genesis manifest
        +
Per-region append-only logs
        +
Finalized region checkpoints
        +
Cross-region transaction certificates
```

A region state is identified by:

```java
public record RegionStateReference(
    RegionId region,
    long epoch,
    long version,
    Hash stateRoot,
    Hash eventLogRoot,
    Hash snapshotHash,
    QuorumCertificate certificate
) {}
```

A state is canonical only when it includes a valid quorum certificate.

```java
public record QuorumCertificate(
    RegionId region,
    long epoch,
    long version,
    Hash previousStateRoot,
    Hash resultingStateRoot,
    List<SignedVote> votes
) {}
```

Neither the dedicated server nor a client may independently declare its local copy canonical.

---

# 5. Region committees

Each active region has a committee.

Recommended initial configuration:

```text
Committee size: 4 peers

1 primary executor
3 validators

Commit threshold: 3 of 4
```

The base server may occupy one position:

```text
Region (0,0)

Primary:
    Player A

Validators:
    Player B
    Player C
    Base server
```

If the base server disconnects:

```text
Region (0,0)

Primary:
    Player A

Validators:
    Player B
    Player C
    Player D
```

A new validator is selected under a new committee epoch.

## Committee record

```java
public record RegionCommittee(
    RegionId region,
    long epoch,
    PeerId primary,
    List<PeerId> validators,
    int quorumThreshold,
    long validFromVersion
) {
    public Set<PeerId> members() {
        return Stream.concat(
            Stream.of(primary),
            validators.stream()
        ).collect(Collectors.toUnmodifiableSet());
    }
}
```

## Committee-change certificate

Committee membership must not be changed by the server alone.

```java
public record CommitteeChangeCertificate(
    RegionId region,
    long previousEpoch,
    long newEpoch,
    RegionCommittee newCommittee,
    List<SignedVote> approvals
) {}
```

The previous committee approves its successor. When too many previous members disappear, a broader recovery committee validates the latest checkpoint and performs reassignment.

---

# 6. Full archival storage

The server should use an event-sourced storage layout rather than saving only ordinary Minecraft region files.

```text
world-store/
├── genesis/
│   ├── world-manifest.bin
│   ├── registry-fingerprint.bin
│   └── genesis-certificate.bin
│
├── regions/
│   └── <dimension>/
│       └── <region-x>_<region-z>/
│           ├── manifest.bin
│           ├── checkpoints/
│           │   ├── 0000000100.snapshot.zst
│           │   └── 0000000200.snapshot.zst
│           ├── events/
│           │   ├── 0000000101-0000000200.log
│           │   └── 0000000201-active.log
│           ├── entities/
│           │   └── entity-index.bin
│           └── certificates/
│               ├── 0000000101.qc
│               └── 0000000102.qc
│
├── global/
│   ├── peer-directory.log
│   ├── committee-history.log
│   ├── cross-region-transactions.log
│   └── global-checkpoints/
│
└── content/
    └── <content-hash>
```

## Region event log

```java
public sealed interface RegionEvent permits
    BlockChangedEvent,
    EntityCreatedEvent,
    EntityUpdatedEvent,
    EntityRemovedEvent,
    ScheduledTickAddedEvent,
    ScheduledTickExecutedEvent,
    InventoryChangedEvent,
    PlayerEnteredRegionEvent,
    PlayerLeftRegionEvent,
    CrossRegionPreparedEvent,
    CrossRegionCommittedEvent {}
```

Every event envelope contains:

```java
public record CommittedEventEnvelope(
    RegionId region,
    long epoch,
    long version,
    long logicalTick,
    EventId eventId,
    RegionEvent event,
    Hash previousStateRoot,
    Hash resultingStateRoot,
    QuorumCertificate certificate
) {}
```

## Entity persistence

Entities need stable network identities independent of local Minecraft runtime IDs.

```java
public record NetworkEntityId(
    UUID value
) {}

public record PersistedEntityState(
    NetworkEntityId entityId,
    ResourceLocation entityType,
    RegionId ownerRegion,
    Vec3 position,
    Vec3 velocity,
    CompoundTag data,
    long version
) {}
```

Entity movement between regions is recorded as an ownership transfer:

```text
Region A:
    EntityTransferPrepared

Region B:
    EntityTransferAccepted

Both regions:
    CrossRegionCommitCertificate

Region A:
    EntityRemoved

Region B:
    EntityCreated with same NetworkEntityId
```

## Scheduled events

Scheduled ticks must be part of committed state.

```java
public record PersistedScheduledEvent(
    ScheduledEventId id,
    RegionId region,
    BlockPos position,
    ResourceLocation eventType,
    long executionTick,
    int priority,
    CompoundTag payload
) {}
```

Otherwise, peers could agree on blocks and entities while diverging later due to missing future events.

---

# 7. Client archival responsibilities

Clients should not normally retain the entire world.

Each peer stores:

```text
Assigned regions:
    complete snapshot
    complete recent event log

Adjacent regions:
    current snapshot
    short event window

Archival responsibility:
    deterministic subset of historical shards

Global data:
    genesis manifest
    peer directory
    latest committee map
    recent global checkpoints
```

## Deterministic archive assignment

Use rendezvous hashing to assign archive replicas.

```java
public interface ArchivePlacementPolicy {
    List<PeerId> selectReplicas(
        ArchiveObjectId object,
        Collection<PeerDescriptor> eligiblePeers,
        int replicationFactor
    );
}
```

Recommended initial replication:

```text
Current region snapshot:
    5 copies

Recent region log:
    4 copies

Historical compacted log:
    3 copies

Global checkpoint:
    every peer

Genesis manifest:
    every peer
```

The full archival server stores everything in addition to these distributed replicas.

---

# 8. Content-addressed world data

Large immutable world objects should be addressed by cryptographic hash.

```java
public record ContentId(
    Hash hash,
    long uncompressedSize,
    Compression compression
) {}
```

Examples:

* region snapshots;
* chunk sections;
* entity tables;
* checkpoint manifests;
* archived event-log segments.

A checkpoint references hashes rather than embedding all data.

```java
public record RegionCheckpointManifest(
    RegionId region,
    long version,
    Hash stateRoot,
    List<ContentId> chunkSections,
    ContentId entityTable,
    ContentId scheduledEvents,
    ContentId recentEventLog,
    QuorumCertificate certificate
) {}
```

Benefits:

* seeders cannot silently modify data;
* clients can download from multiple peers;
* duplicate data is naturally deduplicated;
* partial downloads can be resumed;
* corrupted data is rejected by hash.

---

# 9. P2P synchronization protocol

## New peer startup

```text
1. Connect to one bootstrap address
2. Receive network and genesis manifest
3. Verify network identity
4. Receive several peer addresses
5. Establish independent peer connections
6. Download latest global checkpoint
7. Select seeders for required regions
8. Download snapshots by content hash
9. Replay later committed events
10. Begin validation or execution duties
```

The bootstrap peer does not stream every object itself. It gives the newcomer multiple seeder candidates.

## Snapshot request

```java
public record SnapshotRequest(
    RegionId region,
    long minimumVersion,
    Optional<Hash> knownSnapshotHash
) {}
```

## Seeder response

```java
public record SnapshotOffer(
    PeerId seeder,
    RegionId region,
    long version,
    ContentId manifest,
    List<ContentId> requiredObjects,
    long availableBandwidthBytesPerSecond
) {}
```

## Event catch-up

```java
public record EventRangeRequest(
    RegionId region,
    long afterVersion,
    int maximumEvents
) {}
```

Every returned event must contain or reference its quorum certificate.

---

# 10. Operation when the base peer disconnects

## Existing network

Existing peers retain:

* open P2P connections;
* peer routing tables;
* committee state;
* latest checkpoints;
* replicated region data;
* archival shards;
* outstanding action queues.

They continue processing normally.

```text
Base peer timeout detected
        ↓
Remove it from active routing
        ↓
Replace its committee positions
        ↓
Select another relay if necessary
        ↓
Elect another session gateway
        ↓
Reassign missing archive replicas
        ↓
Continue region execution
```

## New clients

Existing clients can continue without the server, but entirely new clients need another discovery route.

A design with only one bootstrap address has this limitation:

```text
Base peer offline
Existing network: continues
Unknown new client: cannot discover network
```

Provide at least three bootstrap mechanisms:

```text
1. configured public bootstrap peers;
2. cached peer addresses from previous sessions;
3. peer invitation containing addresses and network ID.
```

Optional additional mechanisms:

* DNS seeds;
* LAN multicast discovery;
* community-operated bootstrap peers;
* distributed hash table rendezvous;
* signed peer lists shared out of band.

The base server can remain the preferred bootstrap node without being the only one.

---

# 11. Session-gateway failover

This is distinct from P2P consensus.

A normal Minecraft client expects a connection that behaves like a logical Minecraft server. Therefore, preserving the P2P network is easier than preserving a seamless active Minecraft screen.

Every capable peer should include a dormant `SessionGatewayRuntime`.

```java
public interface SessionGateway {
    GatewaySession start(GatewayContext context);

    void publishCommit(CommittedRegionUpdate update);

    void transferSession(
        GatewayTransferCertificate certificate,
        PeerId successor
    );

    void stop();
}
```

## Gateway responsibilities

The gateway:

* presents finalized state to Minecraft client code;
* relays local actions into the P2P action protocol;
* streams committed entity and chunk changes;
* handles client tracking ranges;
* translates peer state into ordinary Minecraft updates;
* never decides whether an action is valid by itself.

## Gateway election

```java
public record GatewayCandidate(
    PeerId peer,
    boolean acceptsInboundConnections,
    int latencyMedianMs,
    double reliability,
    long availableMemoryBytes
) {}
```

Election inputs:

* connectivity;
* recent reliability;
* latency;
* memory;
* current workload;
* whether inbound connections are possible.

The dedicated server is preferred initially because it has a stable public address, but this preference is not consensus authority.

## Gateway failover flow

```text
Gateway heartbeat expires
        ↓
Peers freeze new action admission briefly
        ↓
Deterministic candidate selection
        ↓
Committee signs GatewayTransferCertificate
        ↓
New gateway loads latest committed checkpoints
        ↓
Clients migrate connection
        ↓
Pending signed actions are resubmitted
```

A realistic first version may involve a brief reconnect screen. Seamless migration requires deeper changes to Minecraft’s connection and world lifecycle.

---

# 12. Revised project structure

Add a generic peer runtime and remove server-centric ownership from the previous structure.

```text
decentralized-minecraft/
├── core/
│   ├── identity/
│   ├── region/
│   ├── action/
│   ├── state/
│   ├── event/
│   ├── checkpoint/
│   └── crypto/
│
├── peer-runtime/
│   └── src/main/java/dev/ashu/decentralized/peer/
│       ├── PeerRuntime.java
│       ├── PeerLifecycle.java
│       ├── PeerCapabilities.java
│       ├── PeerRole.java
│       ├── PeerState.java
│       ├── PeerHealth.java
│       │
│       ├── discovery/
│       │   ├── BootstrapClient.java
│       │   ├── BootstrapService.java
│       │   ├── PeerDirectory.java
│       │   ├── PeerExchange.java
│       │   └── CachedPeerStore.java
│       │
│       ├── routing/
│       │   ├── PeerRouter.java
│       │   ├── RegionRouter.java
│       │   └── ContentRouter.java
│       │
│       ├── committee/
│       │   ├── CommitteeManager.java
│       │   ├── CommitteeElection.java
│       │   └── CommitteeRecovery.java
│       │
│       ├── gateway/
│       │   ├── SessionGateway.java
│       │   ├── SessionGatewayRuntime.java
│       │   ├── GatewayElection.java
│       │   └── GatewayTransferCertificate.java
│       │
│       └── archival/
│           ├── ArchiveManager.java
│           ├── ArchivePlacementPolicy.java
│           ├── ArchiveRepairService.java
│           └── ArchiveInventory.java
│
├── storage-api/
│   └── src/main/java/dev/ashu/decentralized/storage/
│       ├── WorldStore.java
│       ├── RegionEventStore.java
│       ├── SnapshotStore.java
│       ├── ContentStore.java
│       ├── CertificateStore.java
│       └── PeerMetadataStore.java
│
├── storage-rocksdb/
│   └── src/main/java/dev/ashu/decentralized/storage/rocksdb/
│       ├── RocksWorldStore.java
│       ├── RocksRegionEventStore.java
│       ├── RocksSnapshotStore.java
│       └── RocksWriteBatch.java
│
├── storage-client/
│   └── src/main/java/dev/ashu/decentralized/storage/client/
│       ├── BoundedClientWorldStore.java
│       ├── StorageQuotaManager.java
│       └── ArchiveEvictionPolicy.java
│
├── protocol/
│   ├── discovery/
│   ├── consensus/
│   ├── synchronization/
│   ├── content/
│   ├── gateway/
│   └── health/
│
├── simulation/
├── consensus/
├── transport-api/
├── transport-neoforge/
├── transport-libp2p/
│
├── neoforge-mod/
│   └── src/main/java/dev/ashu/decentralized/
│       ├── common/
│       │   ├── CommonPeerBootstrap.java
│       │   └── PeerRuntimeFactory.java
│       │
│       ├── dedicated/
│       │   ├── FullPeerBootstrap.java
│       │   ├── FullArchiveConfiguration.java
│       │   ├── PublicBootstrapEndpoint.java
│       │   └── DedicatedGatewayAdapter.java
│       │
│       └── client/
│           ├── ClientPeerBootstrap.java
│           ├── PartialArchiveConfiguration.java
│           ├── ClientGatewayAdapter.java
│           └── GatewayMigrationController.java
│
└── integration-tests/
    ├── full-peer-bootstrap/
    ├── base-peer-disconnection/
    ├── gateway-migration/
    ├── archive-repair/
    ├── late-peer-catch-up/
    └── full-peer-reconnection/
```

---

# 13. Shared peer runtime

Both physical clients and the dedicated server create the same runtime.

```java
public final class PeerRuntime implements AutoCloseable {
    private final PeerIdentity identity;
    private final PeerCapabilities capabilities;
    private final PeerTransport transport;
    private final WorldStore worldStore;
    private final ConsensusRuntime consensus;
    private final ArchiveManager archiveManager;
    private final GatewayManager gatewayManager;
    private final PeerDirectory peerDirectory;

    public PeerRuntime(
        PeerIdentity identity,
        PeerCapabilities capabilities,
        PeerTransport transport,
        WorldStore worldStore,
        ConsensusRuntime consensus,
        ArchiveManager archiveManager,
        GatewayManager gatewayManager,
        PeerDirectory peerDirectory
    ) {
        this.identity = identity;
        this.capabilities = capabilities;
        this.transport = transport;
        this.worldStore = worldStore;
        this.consensus = consensus;
        this.archiveManager = archiveManager;
        this.gatewayManager = gatewayManager;
        this.peerDirectory = peerDirectory;
    }

    public void start() {
        transport.start();
        peerDirectory.start();
        consensus.start();
        archiveManager.start();

        if (capabilities.supports(PeerRole.SESSION_GATEWAY)) {
            gatewayManager.registerCandidate(identity.peerId());
        }
    }

    @Override
    public void close() {
        gatewayManager.close();
        archiveManager.close();
        consensus.close();
        peerDirectory.close();
        transport.close();
        worldStore.close();
    }
}
```

## Dedicated-server construction

```java
PeerCapabilities capabilities = new PeerCapabilities(
    EnumSet.of(
        PeerRole.BOOTSTRAP,
        PeerRole.RELAY,
        PeerRole.SESSION_GATEWAY,
        PeerRole.REGION_EXECUTOR,
        PeerRole.REGION_VALIDATOR,
        PeerRole.FULL_ARCHIVE,
        PeerRole.WORLD_SEEDER
    ),
    Runtime.getRuntime().availableProcessors(),
    configuredStorageCapacity,
    Runtime.getRuntime().maxMemory(),
    configuredPrimaryLimit,
    configuredValidatorLimit,
    true
);
```

## Client construction

```java
PeerCapabilities capabilities = new PeerCapabilities(
    EnumSet.of(
        PeerRole.SESSION_GATEWAY,
        PeerRole.REGION_EXECUTOR,
        PeerRole.REGION_VALIDATOR,
        PeerRole.PARTIAL_ARCHIVE
    ),
    Runtime.getRuntime().availableProcessors(),
    configuredClientStorageQuota,
    Runtime.getRuntime().maxMemory(),
    configuredPrimaryLimit,
    configuredValidatorLimit,
    canAcceptInboundConnections
);
```

---

# 14. Storage interface

```java
public interface WorldStore extends AutoCloseable {
    Optional<GenesisManifest> loadGenesis();

    void saveGenesis(GenesisManifest genesis);

    Optional<RegionCheckpointManifest> latestCheckpoint(
        RegionId region
    );

    void storeCheckpoint(
        RegionCheckpointManifest checkpoint
    );

    void appendCommittedEvents(
        RegionId region,
        List<CommittedEventEnvelope> events
    );

    List<CommittedEventEnvelope> readEvents(
        RegionId region,
        long afterVersion,
        int maximumCount
    );

    void storeContent(
        ContentId id,
        ByteBuffer content
    );

    Optional<ByteBuffer> readContent(
        ContentId id
    );

    void storeCertificate(
        QuorumCertificate certificate
    );
}
```

The full peer implements this with unlimited or administrator-defined retention.

The client implementation wraps the same interface with quotas and eviction rules.

---

# 15. Additional protocol messages

```text
BootstrapRequest
BootstrapResponse
PeerExchangeRequest
PeerExchangeResponse

ArchiveInventoryAdvertisement
ContentRequest
ContentChunk
ContentAvailability

CheckpointAnnouncement
CheckpointRequest
CheckpointResponse
EventRangeRequest
EventRangeResponse

GatewayCandidateAnnouncement
GatewayElectionVote
GatewayTransferCertificate
GatewayReady
GatewayMigrationInstruction

ArchiveReplicaAssignment
ArchiveReplicaAcknowledgement
ArchiveRepairRequest
ArchiveRepairComplete
```

## Bootstrap response

```java
public record BootstrapResponse(
    NetworkId networkId,
    Hash genesisHash,
    int protocolVersion,
    List<PeerAdvertisement> peers,
    List<CheckpointSummary> checkpoints,
    List<PeerId> alternativeBootstrapPeers,
    Signature signature
) {}
```

A malicious bootstrap peer may provide bad addresses, but it cannot forge valid checkpoints without committee certificates.

---

# 16. Reconnection of the full peer

When the base server returns:

```text
1. Load the latest locally stored checkpoint
2. Connect to several active peers
3. Compare global checkpoint certificates
4. Select the highest valid finalized checkpoint
5. Download missing region manifests
6. Download missing content from multiple seeders
7. Replay committed event ranges
8. Verify every state root
9. Resume archival replication
10. Become eligible for committees again
11. Optionally become the preferred gateway
```

It must never overwrite newer network state with its older local world.

```java
if (localCheckpoint.version() < networkCheckpoint.version()) {
    synchronizeForward();
} else if (localCheckpoint.version() == networkCheckpoint.version()) {
    verifyRoots();
} else {
    investigateUnfinalizedLocalData();
}
```

A locally newer but uncertified event must be treated as uncommitted.

---

# 17. New libraries

In addition to the previously proposed dependencies:

## RocksDB JNI

Use on the full archival peer for:

* append-only regional event indexes;
* checkpoint metadata;
* entity indexes;
* content references;
* atomic write batches;
* crash recovery.

Avoid storing large snapshot blobs directly inside RocksDB. Store blobs as content-addressed files and metadata in RocksDB.

## Reed-Solomon erasure coding

Optional for historical archival data.

For example:

```text
6 data shards
3 parity shards

Any 6 of the 9 shards reconstruct the object.
```

Use erasure coding for cold snapshots and compacted history, not active state. Active region data should use complete replicas for fast recovery.

## Zstandard

Use for:

* snapshots;
* event-log segments;
* entity tables;
* content-addressed blobs.

## Caffeine

Use for:

* recent checkpoints;
* certificate validation;
* peer records;
* content-location caches;
* event deduplication.

## libp2p or equivalent sidecar

Use for:

* peer discovery;
* encrypted peer sessions;
* relaying;
* NAT traversal;
* peer multiplexing.

Keep it behind the existing `PeerTransport` abstraction.

---

# 18. Required invariants

The architecture should enforce these rules:

```text
1. The full peer has no exclusive signing key.

2. The full peer has no additional consensus vote.

3. No world state is canonical without a quorum certificate.

4. Every current region state exists on multiple peers.

5. Every finalized checkpoint is retained outside the full peer.

6. Existing peers can discover one another without the full peer.

7. At least one other peer can become session gateway.

8. A returning full peer synchronizes from the network.

9. Committee changes require certified agreement.

10. Entity and scheduled-event state is part of the region root.

11. Cross-region effects cannot partially commit.

12. The disappearance of one peer cannot stop an adequately replicated region.
```

---

# 19. Recommended implementation order

## Milestone 1: full peer as ordinary validator

The dedicated server:

* starts the P2P runtime;
* stores all committed region data;
* participates in committees;
* seeds snapshots;
* has no exclusive commit privilege.

The Minecraft gameplay session may still require it to remain online.

## Milestone 2: network continuity

After disconnecting the full peer:

* existing peers retain connections;
* committees are repaired;
* blocks and entities continue updating;
* event logs remain replicated;
* the full peer later catches up.

## Milestone 3: gateway migration

Implement another peer taking over the Minecraft-facing gateway role.

Initially allow a short reconnect rather than attempting seamless migration.

## Milestone 4: distributed archival repair

Remove one or more peers and verify that missing archive replicas are automatically recreated.

## Milestone 5: multiple bootstrap peers

Allow clients to enter through:

* the original full peer;
* another community full peer;
* a cached peer;
* a signed invitation.

---

## Resulting model

The server is special in **capacity and availability**, not in authority:

```text
Dedicated server:
    full archive
    preferred bootstrap
    preferred seeder
    preferred gateway
    normal validator

Player client:
    partial archive
    peer discovery participant
    region executor
    region validator
    potential seeder
    potential gateway
```

This preserves the benefit of a stable server—fast entry, complete history and reliable seeding—while ensuring that consensus, region simulation, world history and session control can migrate to the remaining peers.
