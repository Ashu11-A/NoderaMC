package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;

import java.util.UUID;

/** Fixed-value deterministic builders for the {@code peer-runtime/archival} (Task 21) tests. */
final class ArchivalFixtures {

    private ArchivalFixtures() {}

    static Bytes manifestHash(long seed) {
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (seed * 0x9E3779B97F4A7C15L >>> (i * 2));
        }
        return Bytes.unsafeWrap(out);
    }

    static NodeId node(long lsb) {
        return new NodeId(new UUID(0x21a1L, lsb));
    }
}
