package dev.nodera.mod.common;

import dev.nodera.endpoint.lane.LanePlan;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Clientbound: the Minecraft wire wrapper around a {@link LanePlan}.
 *
 * <p>It holds the plan and adds nothing but a {@code StreamCodec}. Everything that reasons ABOUT a
 * plan reads {@link LanePlan} instead, in {@code :endpoint}, with no Minecraft class on the path.
 * The wire bytes are unchanged by that split — the field order below is the wire contract.
 *
 * <p>Thread-context: immutable record; codec on the netty thread, handler hops to main.
 *
 * @param plan the deterministic ownership-plan inputs every member derives the same leases from.
 */
public record NoderaLanePlanPayload(LanePlan plan) implements CustomPacketPayload {

    /** The payload type id ({@code nodera:plan}). */
    public static final Type<NoderaLanePlanPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "plan"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NoderaLanePlanPayload> STREAM_CODEC =
            CustomPacketPayload.codec(NoderaLanePlanPayload::write, NoderaLanePlanPayload::read);

    private static void write(NoderaLanePlanPayload payload, RegistryFriendlyByteBuf buf) {
        LanePlan plan = payload.plan();
        buf.writeLong(plan.worldSeed());
        buf.writeVarInt(plan.rulesVersion());
        buf.writeLong(plan.registryFingerprint());
        buf.writeUtf(plan.genesisRootB64());
        buf.writeUtf(plan.actionSignerKeyB64());
        buf.writeLong(plan.gameTime());
        buf.writeVarInt(plan.committeeSize());
        buf.writeVarInt(plan.members().size());
        for (LanePlan.Member m : plan.members()) {
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
        buf.writeVarInt(plan.residents().size());
        for (LanePlan.Resident r : plan.residents()) {
            buf.writeUtf(r.nodeIdUuid());
            buf.writeUtf(r.publicKeyB64());
            buf.writeUtf(r.route());
        }
        // Appended: a plan that names no bases writes a zero-length list, which is exactly what an
        // older client's absent field meant. Both halves of a session ship together — this is the
        // mod's own payload, not the peer wire — so the list is read unconditionally.
        buf.writeVarInt(plan.bases().size());
        for (LanePlan.RegionBase b : plan.bases()) {
            buf.writeUtf(b.dimNamespace());
            buf.writeUtf(b.dimPath());
            buf.writeVarInt(b.regionX());
            buf.writeVarInt(b.regionZ());
            buf.writeUtf(b.indexRootHex());
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
        List<LanePlan.Member> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            members.add(new LanePlan.Member(buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt()));
        }
        int residentCount = buf.readVarInt();
        List<LanePlan.Resident> residents = new ArrayList<>(residentCount);
        for (int i = 0; i < residentCount; i++) {
            residents.add(new LanePlan.Resident(buf.readUtf(), buf.readUtf(), buf.readUtf()));
        }
        int baseCount = buf.readVarInt();
        List<LanePlan.RegionBase> bases = new ArrayList<>(baseCount);
        for (int i = 0; i < baseCount; i++) {
            bases.add(new LanePlan.RegionBase(buf.readUtf(), buf.readUtf(), buf.readVarInt(),
                    buf.readVarInt(), buf.readUtf()));
        }
        return new NoderaLanePlanPayload(new LanePlan(worldSeed, rulesVersion, registryFingerprint,
                genesisRoot, actionSigner, gameTime, committeeSize, List.copyOf(members),
                List.copyOf(residents), List.copyOf(bases)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
