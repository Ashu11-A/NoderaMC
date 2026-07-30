package dev.nodera.mod.common;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Clientbound: the deterministic inputs of the current region-ownership plan. Every member —
 * the session server included, as just another player node — derives the <b>identical</b> plan
 * from these inputs ({@code EntityLaneBootstrap.plan} is a pure function), activates the regions
 * where its node is primary or validator, and registers every other member as a committee peer.
 * No member receives an assignment "from" anyone: the plan is a shared computation, which is what
 * makes ownership host-free.
 *
 * <p>Thread-context: immutable record; codec on the netty thread, handler hops to main.
 *
 * @param worldSeed           the genesis world seed.
 * @param rulesVersion        the genesis rules version.
 * @param registryFingerprint the genesis registry fingerprint.
 * @param genesisRootB64      base64 of the genesis root hash (all members must open on it).
 * @param actionSignerKeyB64  base64 Ed25519 key that signs action envelopes this session
 *                            (interim single signer — the capture point still rides the vanilla
 *                            session; per-player action signing is the staged follow-up).
 * @param gameTime            the tick the plan's leases start at.
 * @param committeeSize       the committee cap ({@code QUORUM_MVP_SIZE}).
 * @param members             every participating player node with its current field of view.
 * @param residents           every playerless session member eligible for a leftover validator
 *                            seat — the always-on workers. These are a <b>plan input</b>, not a
 *                            courtesy: {@code ViewOwnershipPlanner} staffs leftover seats from
 *                            them, so a member that derives the plan without them derives
 *                            <i>different leases</i>. A client that then primaries a region drops
 *                            the votes its own committee's resident validators send, because that
 *                            validator is not in the lease the client computed. Both halves of the
 *                            session have to see the same resident pool or the shared computation
 *                            is not shared (network L-30).
 */
public record NoderaLanePlanPayload(
        long worldSeed,
        int rulesVersion,
        long registryFingerprint,
        String genesisRootB64,
        String actionSignerKeyB64,
        long gameTime,
        int committeeSize,
        List<Member> members,
        List<Resident> residents) implements CustomPacketPayload {

    /**
     * One participating player node.
     *
     * @param nodeIdUuid   the member's peer {@code NodeId} as a UUID string.
     * @param publicKeyB64 base64 Ed25519 public key.
     * @param route        dialable {@code host:port} P2P route.
     * @param actorUuid    the player UUID whose actions this node signs for.
     * @param dimNamespace field-of-view dimension namespace.
     * @param dimPath      field-of-view dimension path.
     * @param blockX       the player's block X.
     * @param blockZ       the player's block Z.
     * @param viewDistance the player's view distance in chunks.
     */
    public record Member(String nodeIdUuid, String publicKeyB64, String route, String actorUuid,
                         String dimNamespace, String dimPath, int blockX, int blockZ,
                         int viewDistance) {
    }

    /**
     * One always-on session member that holds no player view.
     *
     * <p>It carries no field of view because it has none — that absence is exactly what makes it a
     * resident. It carries a route and a key because a seat needs both: unaddressable means the
     * proposal cannot reach it, unverifiable means its vote cannot be counted.
     *
     * @param nodeIdUuid   the resident's peer {@code NodeId} as a UUID string.
     * @param publicKeyB64 base64 Ed25519 public key — the vote-verification key.
     * @param route        dialable {@code host:port} P2P route.
     */
    public record Resident(String nodeIdUuid, String publicKeyB64, String route) {
    }

    /** The payload type id ({@code nodera:plan}). */
    public static final Type<NoderaLanePlanPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "plan"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NoderaLanePlanPayload> STREAM_CODEC =
            CustomPacketPayload.codec(NoderaLanePlanPayload::write, NoderaLanePlanPayload::read);

    private static void write(NoderaLanePlanPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeLong(payload.worldSeed());
        buf.writeVarInt(payload.rulesVersion());
        buf.writeLong(payload.registryFingerprint());
        buf.writeUtf(payload.genesisRootB64());
        buf.writeUtf(payload.actionSignerKeyB64());
        buf.writeLong(payload.gameTime());
        buf.writeVarInt(payload.committeeSize());
        buf.writeVarInt(payload.members().size());
        for (Member m : payload.members()) {
            buf.writeUtf(m.nodeIdUuid());
            buf.writeUtf(m.publicKeyB64());
            buf.writeUtf(m.route());
            buf.writeUtf(m.actorUuid());
            buf.writeUtf(m.dimNamespace());
            buf.writeUtf(m.dimPath());
            buf.writeVarInt(m.blockX());
            buf.writeVarInt(m.blockZ());
            buf.writeVarInt(m.viewDistance());
        }
        buf.writeVarInt(payload.residents().size());
        for (Resident r : payload.residents()) {
            buf.writeUtf(r.nodeIdUuid());
            buf.writeUtf(r.publicKeyB64());
            buf.writeUtf(r.route());
        }
    }

    private static NoderaLanePlanPayload read(RegistryFriendlyByteBuf buf) {
        long worldSeed = buf.readLong();
        int rulesVersion = buf.readVarInt();
        long registryFingerprint = buf.readLong();
        String genesisRoot = buf.readUtf();
        String actionSigner = buf.readUtf();
        long gameTime = buf.readLong();
        int committeeSize = buf.readVarInt();
        int count = buf.readVarInt();
        List<Member> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            members.add(new Member(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt()));
        }
        int residentCount = buf.readVarInt();
        List<Resident> residents = new ArrayList<>(residentCount);
        for (int i = 0; i < residentCount; i++) {
            residents.add(new Resident(buf.readUtf(), buf.readUtf(), buf.readUtf()));
        }
        return new NoderaLanePlanPayload(worldSeed, rulesVersion, registryFingerprint, genesisRoot,
                actionSigner, gameTime, committeeSize, List.copyOf(members),
                List.copyOf(residents));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
