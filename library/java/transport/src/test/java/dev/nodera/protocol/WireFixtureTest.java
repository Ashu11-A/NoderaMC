package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import dev.nodera.protocol.discovery.ManifestSeeders;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.protocol.discovery.TrackerAnnounceAck;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.ObservedAddress;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.PunchSync;
import dev.nodera.protocol.rendezvous.RegistrationEvent;
import dev.nodera.protocol.rendezvous.RelayConnect;
import dev.nodera.protocol.rendezvous.RelayIncoming;
import dev.nodera.protocol.rendezvous.RelayReservation;
import dev.nodera.protocol.rendezvous.RelayReserve;
import dev.nodera.protocol.rendezvous.RendezvousDiscover;
import dev.nodera.protocol.rendezvous.RendezvousPeers;
import dev.nodera.protocol.rendezvous.RendezvousRegister;
import dev.nodera.protocol.rendezvous.SignedPeerRecord;
import dev.nodera.protocol.rendezvous.SignedRecord;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // Task 28 announce family. The signature is fixed filler: these fixtures pin the ENCODING,
        // and a filler value keeps them reproducible without embedding a private key. The real
        // cross-language signature check is exercised at runtime by TrackerServiceIT, where a Java
        // peer signs with its NodeIdentity and the Rust binary verifies.
        corpus.put("tracker-announce-started.bin", new TrackerAnnounce(
                filled(32, 0x11),
                seederEntry.nodeId(),
                filled(44, 0x66),
                AnnounceEvent.STARTED,
                List.of("198.51.100.4:25599", "[2001:db8::4]:25599"),
                capabilities(Set.of(PeerRole.FULL_ARCHIVE, PeerRole.WORLD_SEEDER)),
                List.of(new ManifestHolding(filled(32, 0x22), Bytes.unsafeWrap(
                        new byte[] {(byte) 0xFF, (byte) 0b1100_0000}))),
                "nodera-overworld",
                0L,
                9_400,
                // A FULL_ARCHIVE host with people in its world: the case the field exists for.
                3L,
                1_700_000_000_000L,
                filled(64, 0x77)));

        // A STOPPED announce carries no routes and no holdings: it only asks to be forgotten.
        corpus.put("tracker-announce-stopped.bin", new TrackerAnnounce(
                filled(32, 0x11),
                playerEntry.nodeId(),
                filled(44, 0x66),
                AnnounceEvent.STOPPED,
                List.of(),
                capabilities(Set.of(PeerRole.PARTIAL_ARCHIVE)),
                List.of(),
                "",
                0L,
                0,
                // A leaving peer reports "I cannot see" — the sentinel, pinned on the wire so the
                // offset-by-one encoding that carries it is covered by a golden fixture.
                TrackerAnnounce.UNKNOWN_PLAYER_COUNT,
                1_700_000_060_000L,
                filled(64, 0x88)));

        corpus.put("tracker-announce-ack-accepted.bin", TrackerAnnounceAck.accepted(120));
        corpus.put("tracker-announce-ack-rejected.bin",
                TrackerAnnounceAck.rejected(300, "bad-signature"));

        corpus.put("inventory-advertisement.bin", new InventoryAdvertisement(
                filled(32, 0x11),
                seederEntry.nodeId(),
                List.of(
                        new ManifestHolding(filled(32, 0x22), Bytes.unsafeWrap(
                                new byte[] {(byte) 0b1010_0000, (byte) 0xFF})),
                        new ManifestHolding(filled(32, 0x55), Bytes.unsafeWrap(new byte[] {0})))));

        // --- Task 29 rendezvous / relay family (tags 35–43). Signature/proof fields are fixed
        // filler: these fixtures pin the ENCODING; the real cross-language Ed25519 verification is
        // exercised at runtime by RendezvousRelayIT, where a Java peer signs with its NodeIdentity
        // and the Rust binary verifies the identical canonical bytes. ---
        UUID networkId = new UUID(0x0102030405060708L, 0x1112131415161718L);
        SignedPeerRecord record = new SignedPeerRecord(
                networkId,
                filled(32, 0x11),
                seederEntry.nodeId(),
                filled(44, 0x66),
                RegistrationEvent.REGISTER,
                List.of(
                        new PeerCandidate(CandidateKind.HOST, "10.0.0.4:25566", 100),
                        new PeerCandidate(CandidateKind.RELAY, "198.51.100.9:25601", 1)),
                capabilities(Set.of(PeerRole.PARTIAL_ARCHIVE)),
                1_700_000_000_000L,
                1_700_000_300_000L);
        SignedRecord signed = new SignedRecord(record, filled(64, 0x77));

        corpus.put("rendezvous-register.bin", new RendezvousRegister(signed));
        corpus.put("rendezvous-discover.bin",
                new RendezvousDiscover(networkId, filled(32, 0x11), 0, 50));
        corpus.put("rendezvous-peers.bin", new RendezvousPeers(7, List.of(signed)));
        corpus.put("relay-reserve.bin",
                new RelayReserve(networkId, filled(32, 0x11), seederEntry.nodeId()));
        corpus.put("relay-reservation.bin", new RelayReservation(
                true, "198.51.100.9:25601", 1_700_000_300_000L, 67_108_864L, 300_000L,
                filled(32, 0x55), ""));
        corpus.put("relay-connect.bin", new RelayConnect(
                networkId, filled(32, 0x11), playerEntry.nodeId(), seederEntry.nodeId()));
        corpus.put("relay-incoming.bin", new RelayIncoming(
                networkId, filled(32, 0x11), playerEntry.nodeId(), seederEntry.nodeId(),
                filled(32, 0x55)));
        corpus.put("punch-sync.bin", new PunchSync(
                networkId, filled(32, 0x11), playerEntry.nodeId(), seederEntry.nodeId(),
                List.of(new PeerCandidate(CandidateKind.SERVER_REFLEXIVE, "198.51.100.7:40000", 50)),
                1_700_000_001_000L));
        corpus.put("observed-address.bin",
                new ObservedAddress(playerEntry.nodeId(), "198.51.100.7:40000"));

        // The service-directory family. These matter more than most: the record is signed by a *Rust*
        // service and verified by a *Java* peer, which is the opposite direction from every other
        // signed message here — so byte identity is what makes a rendezvous' own record checkable at
        // all, and the score's composite is a function both sides must compute identically.
        dev.nodera.protocol.service.ServiceRecord serviceRecord =
                new dev.nodera.protocol.service.ServiceRecord(
                        seederEntry.nodeId(),
                        filled(44, 0x42),
                        dev.nodera.protocol.service.ServiceKind.RENDEZVOUS,
                        dev.nodera.protocol.service.ServiceLifecycle.SERVING,
                        networkId,
                        List.of("rdv.example:25601", "tcp://198.51.100.9:25601"),
                        "0.1.0",
                        120, 5_000, 4, 64, 3,
                        1_700_000_000_000L,
                        1_700_000_300_000L,
                        0L);
        dev.nodera.protocol.service.ServiceScore serviceScore =
                new dev.nodera.protocol.service.ServiceScore(980, 35, 90, 900, 1_000, 7, 0)
                        .withComposite();
        dev.nodera.protocol.service.ServiceDirectoryEntry serviceEntry =
                new dev.nodera.protocol.service.ServiceDirectoryEntry(
                        serviceRecord, filled(64, 0x77), serviceScore);

        corpus.put("service-announce.bin",
                new dev.nodera.protocol.service.ServiceAnnounce(serviceRecord, filled(64, 0x77)));
        corpus.put("service-announce-ack.bin", new dev.nodera.protocol.service.ServiceAnnounceAck(
                true, 120, "", List.of(serviceEntry)));
        corpus.put("service-directory-query.bin",
                new dev.nodera.protocol.service.ServiceDirectoryQuery(
                        dev.nodera.protocol.service.ServiceKind.RENDEZVOUS, networkId, 8));
        corpus.put("service-directory-response.bin",
                new dev.nodera.protocol.service.ServiceDirectoryResponse(List.of(serviceEntry)));
        corpus.put("service-score-report.bin", new dev.nodera.protocol.service.ServiceScoreReport(
                playerEntry.nodeId(), filled(44, 0x66), networkId,
                List.of(new dev.nodera.protocol.service.ServiceObservation(
                        seederEntry.nodeId(),
                        dev.nodera.protocol.service.ServiceKind.RENDEZVOUS,
                        20, 19, 35, 90, 1_700_000_060_000L)),
                1_700_000_060_000L, filled(64, 0x88)));
        corpus.put("service-drain-notice.bin", new dev.nodera.protocol.service.ServiceDrainNotice(
                new dev.nodera.protocol.service.ServiceRecord(
                        seederEntry.nodeId(), filled(44, 0x42),
                        dev.nodera.protocol.service.ServiceKind.RENDEZVOUS,
                        dev.nodera.protocol.service.ServiceLifecycle.DRAINING,
                        networkId,
                        List.of("rdv.example:25601", "tcp://198.51.100.9:25601"),
                        "0.1.0", 120, 5_000, 4, 64, 3,
                        1_700_000_000_000L, 1_700_000_300_000L, 1_700_000_030_000L),
                filled(64, 0x77), List.of(serviceEntry), "update"));

        // The four tracker directory verbs. `nodera-codec` declares all four in
        // SUPPORTED_MESSAGE_TAGS and implements all four in `wire.rs`, but their golden bytes sat
        // in `java-only/` — which the Rust conformance test does not read — so four Rust decoders
        // on live tracker paths had never been held to the Java bytes at all. Neither guard saw
        // it: `tag_mirror.rs` only checks a supported tag is <= NEXT_TAG, and the coverage test
        // below used to name five tags by hand. The samples are reused rather than re-typed so the
        // bytes are exactly the ones java-only pinned before the move.
        for (int tag : List.of(
                MessageCodec.TAG_TRACKER_CATALOG_QUERY,
                MessageCodec.TAG_TRACKER_CATALOG_RESPONSE,
                MessageCodec.TAG_TRACKER_ROUTES_QUERY,
                MessageCodec.TAG_TRACKER_ROUTES_RESPONSE)) {
            corpus.put(MessageSamples.fixtureName(tag), MessageSamples.byTag().get(tag));
        }

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

    /**
     * The Java-only half of the corpus: a golden frame for every registry tag the cross-language
     * corpus does not carry.
     *
     * <p>These live in {@code fixtures/wire/java-only/} because Rust deliberately implements only
     * the discovery, rendezvous, and service families — the game and consensus messages have no
     * second implementation to agree with. They are still pinned here: byte stability is what makes
     * "the wire did not change" a checkable claim rather than a review opinion, and it is what a
     * later generated codec will be verified against.
     *
     * @return file name → message, for every tag not in {@link #corpus()}.
     */
    private static Map<String, NoderaMessage> javaOnlyCorpus() {
        Set<Integer> crossLanguage = new java.util.TreeSet<>();
        for (NoderaMessage msg : corpus().values()) {
            crossLanguage.add(MessageCodec.typeTagOf(msg));
        }
        Map<String, NoderaMessage> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, NoderaMessage> entry : MessageSamples.byTag().entrySet()) {
            if (!crossLanguage.contains(entry.getKey())) {
                out.put(MessageSamples.fixtureName(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    /**
     * Shared fixtures another suite owns and byte-pins.
     *
     * <p>{@code fixtures/wire/} is not this test's private directory. {@code WorldLifecycleTest}
     * writes the deletion and revival frames there, and has to: Rust's {@code tombstone.rs},
     * {@code tracker/src/deletion.rs} and {@code tests/fixtures.rs} all call {@code verified()} on
     * them, so those two files must hold <b>real owner-signed records</b>, which the fixed filler
     * signatures in {@link MessageSamples} cannot be. That is also why the same two kinds have a
     * second, synthetic sample of their own under {@code java-only/} — same file name, different
     * bytes, different owner.
     *
     * <p>Naming them here is what lets two checks see them. The sweep in
     * {@link #checkCorpus(Path, Map, Set)} would otherwise report them as strays, and
     * {@link #crossLanguageCorpusCoversEveryTagRustDeclaresItSupports()} would otherwise report
     * their golden bytes as missing from a directory they have been committed in all along.
     *
     * @return shared file name → the registry tag that file carries.
     */
    private static Map<String, Integer> externallyOwnedShared() {
        Map<String, Integer> owned = new LinkedHashMap<>();
        owned.put("world-deletion-gossip.bin", MessageCodec.TAG_WORLD_DELETION_GOSSIP);
        owned.put("world-revival-gossip.bin", MessageCodec.TAG_WORLD_REVIVAL_GOSSIP);
        return owned;
    }

    /**
     * Compare one corpus against its committed files, in both directions.
     *
     * <p>A missing fixture <b>fails</b>. It used to be written silently, which meant a new message
     * could reach production with a fixture nobody had ever reviewed — the file appeared in the
     * working tree as a side effect of a green test run, and a side effect is not a decision.
     *
     * <p>And an <b>unaccounted</b> fixture fails too. This method used to walk the corpus map only,
     * so a {@code .bin} the map no longer names — a renamed message, a sample moved between the two
     * directories, a file committed by hand — was asserted by nothing at all while still being read
     * by Rust's {@code fixture_files()}, which lists the directory rather than a map. The sweep is
     * non-recursive for the same reason Rust's is: {@code java-only/} and {@code v1-rejected/} are
     * separate corpora with their own checks.
     *
     * @param dir the directory to compare
     * @param corpus file name → message, the fixtures this test owns in {@code dir}
     * @param alsoAllowed file names in {@code dir} that another suite owns and pins
     */
    private static void checkCorpus(Path dir, Map<String, NoderaMessage> corpus,
            Set<String> alsoAllowed) throws IOException {
        boolean regenerate = Boolean.getBoolean(REGENERATE_PROPERTY);
        if (regenerate) {
            Files.createDirectories(dir);
        }
        for (Map.Entry<String, NoderaMessage> entry : corpus.entrySet()) {
            // The corpus pins the CURRENT wire: NDR2 frames with TLV infrastructure bodies. The
            // frames these files used to hold are kept under `v1-rejected/`, where they are asserted
            // to be refused rather than misparsed.
            byte[] encoded = WireCodec.encode(entry.getValue());
            Path file = dir.resolve(entry.getKey());

            if (regenerate) {
                Files.write(file, encoded);
                continue;
            }
            assertTrue(Files.exists(file),
                    entry.getKey() + ": no committed fixture. A new message needs golden bytes — "
                            + "re-run with -D" + REGENERATE_PROPERTY + "=true, then review and "
                            + "commit the file it emits.");
            byte[] golden = Files.readAllBytes(file);
            assertArrayEquals(golden, encoded,
                    entry.getKey() + ": encoded bytes differ from the committed fixture. This is a "
                            + "wire-contract change — review it, then re-run with -D"
                            + REGENERATE_PROPERTY + "=true to accept.");
        }

        Set<String> accounted = new java.util.TreeSet<>(corpus.keySet());
        accounted.addAll(alsoAllowed);
        List<String> unaccounted = new java.util.ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file) || !name.endsWith(".bin")) {
                    continue;
                }
                if (!accounted.contains(name)) {
                    unaccounted.add(name);
                }
            }
        }
        assertTrue(unaccounted.isEmpty(),
                dir + " holds golden frames nothing accounts for: " + unaccounted + ". Rust reads "
                        + "this directory by listing it, so a file no corpus names is still being "
                        + "decoded while nothing asserts its bytes. Either add it to the corpus, "
                        + "name its owning suite in externallyOwnedShared(), or delete it.");
    }

    @Test
    void goldenFixturesMatchTheCommittedBytes() throws IOException {
        Path dir = repoRoot().resolve("fixtures").resolve("wire");
        checkCorpus(dir, corpus(), externallyOwnedShared().keySet());

        // The exemption has to name files that are really there and really carry the kind claimed,
        // or it quietly becomes a licence to omit a fixture: the sweep stops reporting the name and
        // the cross-language coverage check counts the tag as present on the strength of the map.
        for (Map.Entry<String, Integer> owned : externallyOwnedShared().entrySet()) {
            Path file = dir.resolve(owned.getKey());
            assertTrue(Files.exists(file), owned.getKey() + " is exempted from this corpus as "
                    + "externally owned, but no such file is committed: " + file);
            assertEquals(owned.getValue().intValue(),
                    WireCodec.decodeFrame(Files.readAllBytes(file)).kind(),
                    owned.getKey() + " does not carry the kind externallyOwnedShared() claims");
        }
    }

    @Test
    void javaOnlyGoldenFixturesMatchTheCommittedBytes() throws IOException {
        checkCorpus(repoRoot().resolve("fixtures").resolve("wire").resolve("java-only"),
                javaOnlyCorpus(), Set.of());
    }

    @Test
    void everyFixtureRoundTripsThroughTheJavaCodec() {
        Map<String, NoderaMessage> all = new LinkedHashMap<>(corpus());
        all.putAll(javaOnlyCorpus());
        for (Map.Entry<String, NoderaMessage> entry : all.entrySet()) {
            byte[] encoded = WireCodec.encode(entry.getValue());
            NoderaMessage decoded = WireCodec.decode(encoded);
            assertEquals(entry.getValue(), decoded, entry.getKey());
            assertArrayEquals(encoded, WireCodec.encode(decoded), entry.getKey());

            // The strict canonical encoding is still the one that is hashed and signed, so it is
            // held to the same round trip — it is simply no longer what crosses the wire.
            byte[] canonical = MessageCodec.encode(entry.getValue());
            assertEquals(entry.getValue(), MessageCodec.decode(canonical), entry.getKey());
        }
    }

    /**
     * The flag day, asserted rather than assumed.
     *
     * <p>Every one of these files is a frame this project really used to emit. A peer that still
     * speaks it must be told so — loudly, at the first four bytes — because the alternative is what
     * the old frame actually did: begin parsing, produce nonsense, and report the nonsense.
     */
    @Test
    void everyRetiredV1FrameIsRefusedRatherThanMisparsed() throws IOException {
        Path dir = repoRoot().resolve("fixtures").resolve("wire").resolve("v1-rejected");
        assertTrue(Files.isDirectory(dir), "the retired v1 corpus must stay committed: " + dir);
        try (var files = Files.list(dir)) {
            List<Path> retired = files.filter(f -> f.toString().endsWith(".bin")).sorted().toList();
            assertFalse(retired.isEmpty(), "the v1 rejection set is empty");
            for (Path file : retired) {
                byte[] v1 = Files.readAllBytes(file);
                assertThrows(IllegalStateException.class, () -> WireCodec.decodeFrame(v1),
                        file.getFileName() + ": a pre-NDR2 frame must be refused, not interpreted");
            }
        }
    }

    @Test
    void corpusCoversEveryRegistryTag() {
        MessageSamples.assertTotal();

        Set<Integer> covered = new java.util.TreeSet<>();
        for (NoderaMessage msg : corpus().values()) {
            covered.add(MessageCodec.typeTagOf(msg));
        }
        for (NoderaMessage msg : javaOnlyCorpus().values()) {
            covered.add(MessageCodec.typeTagOf(msg));
        }
        Set<Integer> missing = new java.util.TreeSet<>(MessageCodec.KNOWN_TAGS);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(),
                "every registry tag needs golden bytes; missing " + missing);
    }

    /**
     * Every kind the Rust crate <i>claims</i> it can decode must have golden bytes in the SHARED
     * directory, because the shared directory is the only one {@code tests/fixtures.rs} reads.
     *
     * <p>This assertion used to name five tags as literals — 27/28/29 and 33/34 — and it was
     * therefore blind to the four tracker directory verbs (44, 45, 49, 50) that
     * {@code tags.rs::SUPPORTED_MESSAGE_TAGS} declares and {@code wire.rs} implements, whose
     * fixtures sat in {@code java-only/}. Four Rust decoders on live tracker paths were never held
     * to the Java bytes and nothing anywhere said so: {@code tag_mirror.rs} only asserts a
     * supported tag is not above the highest assigned one. The list is now <b>derived from Rust's
     * own declaration</b>, so the next tag added there fails here until its fixture moves across.
     *
     * <p>Deriving the list is only half of it — the declaration itself has to be complete. It was
     * missing kinds 66 and 76, which {@code tracker/src/service.rs} dispatches and
     * {@code tracker/src/deletion.rs} verifies owner signatures on, so this check could not see the
     * two kinds where a Rust/Java disagreement decides whether a world stays deleted. Their golden
     * bytes have always been in the shared directory, written by another suite; see
     * {@link #externallyOwnedShared()}.
     */
    @Test
    void crossLanguageCorpusCoversEveryTagRustDeclaresItSupports() throws IOException {
        Set<Integer> covered = new java.util.TreeSet<>();
        for (NoderaMessage msg : corpus().values()) {
            covered.add(MessageCodec.typeTagOf(msg));
        }
        // Present in the directory Rust lists, just not written by this test. Two separate checks
        // keep this from being an exemption nothing verifies: the loop below asserts each exempted
        // file exists and really carries the kind it claims, and checkCorpus's reverse sweep fails on
        // any .bin in either directory that neither corpus nor this map accounts for.
        covered.addAll(externallyOwnedShared().values());

        Set<String> declared = rustSupportedTagNames();
        // Cardinality first: a parse that silently matched nothing would make every check below
        // vacuous, which is the exact failure this whole test exists to end.
        assertTrue(declared.size() >= 20,
                "parsed only " + declared.size() + " entries out of SUPPORTED_MESSAGE_TAGS — the "
                        + "declaration moved or changed shape, and this test is now vacuous");

        Map<String, Integer> byRustName = new LinkedHashMap<>();
        for (dev.nodera.protocol.wire.WireKind kind
                : dev.nodera.protocol.wire.WireRegistry.kinds()) {
            byRustName.put(kind.rustConstantName(), kind.kind());
        }

        List<String> unknown = new java.util.ArrayList<>();
        List<String> uncovered = new java.util.ArrayList<>();
        for (String name : declared) {
            Integer tag = byRustName.get(name);
            if (tag == null) {
                unknown.add(name);
            } else if (!covered.contains(tag)) {
                uncovered.add(name + " (" + tag + ")");
            }
        }
        assertTrue(unknown.isEmpty(),
                "nodera-codec declares support for kinds the Java registry does not have: " + unknown);
        assertTrue(uncovered.isEmpty(),
                "nodera-codec decodes these kinds but their golden bytes are not in the shared "
                        + "corpus, so tests/fixtures.rs never checks them against Java: " + uncovered);
    }

    /**
     * The constant names listed in {@code nodera-codec}'s {@code SUPPORTED_MESSAGE_TAGS}.
     *
     * <p>Read out of the Rust source, in the same direction {@code tests/tag_mirror.rs} already
     * reads Java's: the declaration lives on one side, so the check has to cross the language
     * boundary or it is a second copy of the list that can disagree with the first.
     */
    private static Set<String> rustSupportedTagNames() throws IOException {
        Path crate = dev.nodera.testkit.harness.LayoutManifest.load().crates().get("nodera-codec");
        assertTrue(crate != null, "layout.properties has no crate.nodera-codec");
        Path tags = crate.resolve("src").resolve("tags.rs");
        assertTrue(Files.exists(tags), "nodera-codec tags.rs not found at " + tags);
        String source = Files.readString(tags);
        int start = source.indexOf("SUPPORTED_MESSAGE_TAGS");
        assertTrue(start >= 0, "SUPPORTED_MESSAGE_TAGS not found in " + tags);
        int open = source.indexOf('[', start);
        int close = source.indexOf("];", open);
        assertTrue(open >= 0 && close > open, "SUPPORTED_MESSAGE_TAGS is not a bracketed list");
        Set<String> names = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("message_tags::([A-Z0-9_]+)")
                .matcher(source.substring(open, close));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
