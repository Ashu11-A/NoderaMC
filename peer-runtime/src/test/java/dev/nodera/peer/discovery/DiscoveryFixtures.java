package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Fixed-value deterministic builders for the {@code peer-runtime/discovery} (Task 20) tests. */
final class DiscoveryFixtures {

    private DiscoveryFixtures() {}

    /** A genesis hash built from a short label — distinct labels yield distinct worlds. */
    static Bytes worldHash(String label) {
        byte[] out = new byte[32];
        byte[] name = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (name[i % name.length] * 31 + i);
        }
        return Bytes.unsafeWrap(out);
    }

    static Bytes manifestHash(long seed) {
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (seed * 0x9E3779B97F4A7C15L >>> (i * 2));
        }
        return Bytes.unsafeWrap(out);
    }

    static NodeId node(long lsb) {
        return new NodeId(new UUID(0xfeedfaceL, lsb));
    }

    static PeerEntry entry(NodeId id, boolean bootstrap) {
        return new PeerEntry(id, "peer-" + id.value().getLeastSignificantBits(),
                capabilities(0.9), bootstrap);
    }

    static PeerEntry entry(NodeId id, double reliability) {
        return new PeerEntry(id, "peer-" + id.value().getLeastSignificantBits(),
                capabilities(reliability), false);
    }

    static NodeCapabilities capabilities(double reliability) {
        return NodeCapabilities.initial().withRoles(
                EnumSet.of(PeerRole.RELAY, PeerRole.PARTIAL_ARCHIVE))
                .withReliability(reliability);
    }

    /** A packed bitmap claiming the given piece indexes. */
    static Bytes bitmapOf(Set<Integer> indexes) {
        return dev.nodera.protocol.content.PieceBitmap.of(new LinkedHashSet<>(indexes));
    }
}
