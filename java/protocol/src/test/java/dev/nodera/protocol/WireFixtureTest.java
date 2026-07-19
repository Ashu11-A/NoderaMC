package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import dev.nodera.protocol.discovery.ManifestSeeders;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.membership.PeerEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Emits and verifies the shared cross-language golden fixtures (Task 27).
 *
 * <p>The canonical encoding is a consensus contract, and after Tasks 28/29 there are two
 * implementations of it: Java's {@link MessageCodec} and the Rust {@code nodera-codec} crate. The
 * only acceptable proof that they agree is byte identity on a fixed corpus, so this test writes
 * {@code fixtures/wire/*.bin} at the repo root and the Rust {@code tests/fixtures.rs} decodes and
 * re-encodes the very same files.
 *
 * <p>The fixtures are <b>committed</b>. This test regenerates them only when the bytes would be
 * unchanged (a no-op rewrite) or when a file is missing; a byte difference fails instead of
 * silently rewriting, because a change here is a wire-contract change and must be reviewed. Pass
 * {@code -Dnodera.fixtures.regenerate=true} to accept new bytes deliberately.
 *
 * <p>Every message is built from fixed, hand-written values — no clocks, no random UUIDs — so the
 * emitted bytes are stable across runs and machines.
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
class WireFixtureTest {

    /** System property that allows deliberate regeneration after an intended wire change. */
    private static final String REGENERATE_PROPERTY = "nodera.fixtures.regenerate";

    private static NodeId nodeId(long msb, long lsb) {
        return new NodeId(new UUID(msb, lsb));
    }

    private static Bytes filled(int length, int value) {
        byte[] raw = new byte[length];
        Arrays.fill(raw, (byte) value);
        return Bytes.unsafeWrap(raw);
    }

    private static NodeCapabilities capabilities(Set<PeerRole> roles) {
        // Fixed values: reliability is encoded as raw doubleToLongBits, so 1.0 keeps the golden
        // bytes readable (0x3FF0000000000000) and platform-independent.
        return new NodeCapabilities(8, 17_179_869_184L, 42, 1.0d, 4, 8, true, roles);
    }

    /** The frozen fixture corpus: file name → message. Append entries; never repurpose a name. */
    private static Map<String, NoderaMessage> corpus() {
        Map<String, NoderaMessage> corpus = new LinkedHashMap<>();

        corpus.put("tracker-query.bin", new TrackerQuery(filled(32, 0x11)));

        PeerEntry seederEntry = new PeerEntry(
                nodeId(0x0102030405060708L, 0x090A0B0C0D0E0F10L),
                "198.51.100.4:25599",
                capabilities(Set.of(PeerRole.FULL_ARCHIVE, PeerRole.WORLD_SEEDER)),
                true);
        PeerEntry playerEntry = new PeerEntry(
                nodeId(0x1112131415161718L, 0x191A1B1C1D1E1F20L),
                "203.0.113.7:25599",
                capabilities(Set.of(PeerRole.PARTIAL_ARCHIVE)),
                false);

        corpus.put("tracker-response-healthy.bin", new TrackerResponse(
                filled(32, 0x11),
                "nodera-overworld",
                List.of(seederEntry, playerEntry),
                List.of(new ManifestSeeders(filled(32, 0x22), List.of(seederEntry.nodeId()))),
                3L,
                4096L,
                9_400,
                WorldHealth.HEALTHY,
                0L));

        // Zero seeders inside the retention window: red + countdown (Task 22/26 surface).
        corpus.put("tracker-response-countdown.bin", new TrackerResponse(
                filled(32, 0x33),
                "nodera-nether",
                List.of(),
                List.of(),
                0L,
                0L,
                0,
                WorldHealth.DEGRADED,
                1_700_000_000_000L));

        corpus.put("tracker-response-dead.bin", new TrackerResponse(
                filled(32, 0x44),
                "",
                List.of(playerEntry),
                List.of(),
                0L,
                17L,
                10_000,
                WorldHealth.DEAD,
                1L));

        corpus.put("inventory-advertisement.bin", new InventoryAdvertisement(
                filled(32, 0x11),
                seederEntry.nodeId(),
                List.of(
                        new ManifestHolding(filled(32, 0x22), Bytes.unsafeWrap(
                                new byte[] {(byte) 0b1010_0000, (byte) 0xFF})),
                        new ManifestHolding(filled(32, 0x55), Bytes.unsafeWrap(new byte[] {0})))));

        return corpus;
    }

    /**
     * Locate the repo root by walking up from the module directory until {@code settings.gradle.kts}
     * is found. Robust against the Task 27 move (modules now live under {@code java/}) and against
     * being run from an IDE with a different working directory.
     */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root (settings.gradle.kts) not found above "
                    + Paths.get("").toAbsolutePath());
        }
        return dir;
    }

    @Test
    void goldenFixturesMatchTheCommittedBytes() throws IOException {
        Path dir = repoRoot().resolve("fixtures").resolve("wire");
        Files.createDirectories(dir);
        boolean regenerate = Boolean.getBoolean(REGENERATE_PROPERTY);

        for (Map.Entry<String, NoderaMessage> entry : corpus().entrySet()) {
            byte[] encoded = MessageCodec.encode(entry.getValue());
            Path file = dir.resolve(entry.getKey());

            if (Files.exists(file) && !regenerate) {
                byte[] golden = Files.readAllBytes(file);
                assertArrayEquals(golden, encoded,
                        entry.getKey() + ": encoded bytes differ from the committed fixture. This is a "
                                + "wire-contract change — review it, then re-run with -D"
                                + REGENERATE_PROPERTY + "=true to accept.");
            } else {
                Files.write(file, encoded);
            }
        }
    }

    @Test
    void everyFixtureRoundTripsThroughTheJavaCodec() {
        for (Map.Entry<String, NoderaMessage> entry : corpus().entrySet()) {
            byte[] encoded = MessageCodec.encode(entry.getValue());
            NoderaMessage decoded = MessageCodec.decode(encoded);
            assertEquals(entry.getValue(), decoded, entry.getKey());
            assertArrayEquals(encoded, MessageCodec.encode(decoded), entry.getKey());
        }
    }

    @Test
    void corpusCoversTheDiscoveryTagsTheRustServicesSpeak() {
        Set<Integer> covered = new java.util.TreeSet<>();
        for (NoderaMessage msg : corpus().values()) {
            covered.add(MessageCodec.typeTagOf(msg));
        }
        // Tags 27–29 are the frozen discovery family the Rust tracker answers (Task 28).
        assertTrue(covered.containsAll(List.of(
                        MessageCodec.TAG_TRACKER_QUERY,
                        MessageCodec.TAG_TRACKER_RESPONSE,
                        MessageCodec.TAG_INVENTORY_ADVERTISEMENT)),
                "fixture corpus must cover every discovery tag; covered=" + covered);
    }
}
