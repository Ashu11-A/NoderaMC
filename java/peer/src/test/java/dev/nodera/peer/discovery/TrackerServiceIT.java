package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.TorrentWorldListView;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.protocol.discovery.TrackerAnnounceAck;
import dev.nodera.protocol.discovery.TrackerResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Task 28 acceptance #3: the <b>real</b> {@code nodera-tracker} binary, driven by real Java peers.
 *
 * <p>This is the test the task exists for. Two Java peers announce two worlds to a freshly spawned
 * Rust process; queries come back with the right peers, seeders and counts; a peer that goes silent
 * expires; a world whose every seeder is gone surfaces the retention countdown and eventually
 * reports {@code DEAD} — and the world list is still served with <i>no Java seeder running at
 * all</i>, which is exactly what the embedded Task 20 tracker could not do (L-44).
 *
 * <p>It also closes the cross-language loop that the golden fixtures cannot: the announces here are
 * signed by a real {@link NodeIdentity} with the JDK's Ed25519 and verified by {@code ed25519-dalek}
 * inside the service, over the JDK's X.509-encoded public key.
 *
 * <p>Skipped (not failed) when the binary has not been built: {@code cargo build} is a separate
 * toolchain, and a Java-only checkout must stay green. Build it with
 * {@code cd rust && cargo build --release --bin nodera-tracker} (or {@code scripts/build-all.sh}).
 *
 * <p>Thread-context: single test thread; the service runs in a child process.
 */
final class TrackerServiceIT {

    /** Short TTL so expiry is observable in a test instead of in five minutes. */
    private static final int ANNOUNCE_INTERVAL_SECONDS = 1;
    private static final int PEER_TTL_SECONDS = 2;

    private Process tracker;
    private Path configFile;

    @AfterEach
    void stopTracker() throws Exception {
        if (tracker != null) {
            tracker.destroy();
            if (!tracker.waitFor(10, TimeUnit.SECONDS)) {
                tracker.destroyForcibly();
            }
        }
        if (configFile != null) {
            Files.deleteIfExists(configFile);
        }
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found");
        }
        return dir;
    }

    private static Optional<Path> trackerBinary() {
        Path root = repoRoot().resolve("rust").resolve("target");
        for (String profile : new String[] {"release", "debug"}) {
            Path candidate = root.resolve(profile).resolve("nodera-tracker");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Start the service on an ephemeral port and return the endpoint it actually bound. */
    private TrackerClient.Endpoint startTracker(Path binary) throws IOException {
        configFile = Files.createTempFile("nodera-tracker", ".toml");
        Files.writeString(configFile, """
                bind_addr = "127.0.0.1:0"
                announce_interval_seconds = %d
                peer_ttl_seconds = %d
                healthy_seeder_floor = 1
                sample_size = 10
                seeder_floor = 5
                """.formatted(ANNOUNCE_INTERVAL_SECONDS, PEER_TTL_SECONDS),
                StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(
                binary.toString(), "--config", configFile.toString());
        builder.redirectErrorStream(true);
        tracker = builder.start();

        // The service prints the bound address; port 0 means only it knows the real port.
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(tracker.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            int marker = line.indexOf("listening on ");
            if (marker >= 0) {
                return TrackerClient.Endpoint.parse(line.substring(marker + "listening on ".length()).trim());
            }
        }
        throw new IOException("nodera-tracker did not report a listening address");
    }

    private static NodeCapabilities capabilities(Set<PeerRole> roles) {
        return new NodeCapabilities(4, 8L << 30, 30, 0.94d, 2, 4, true, roles);
    }

    private static TrackerAnnounce announce(
            TrackerClient client, Bytes world, AnnounceEvent event, Set<PeerRole> roles,
            List<ManifestHolding> holdings, String worldName) {
        // No population: an announce with no game behind it says "I cannot see", which is what
        // every seeder says. The tracker's count used to be its peer count, so these announces
        // produced a player total by existing.
        return announce(client, world, event, roles, holdings, worldName,
                TrackerAnnounce.UNKNOWN_PLAYER_COUNT);
    }

    /** As above, reporting a population — a peer with a game in the world. */
    private static TrackerAnnounce announce(
            TrackerClient client, Bytes world, AnnounceEvent event, Set<PeerRole> roles,
            List<ManifestHolding> holdings, String worldName, long players) {
        return client.buildAnnounce(
                world, event, List.of("127.0.0.1:25599"), capabilities(roles), holdings,
                worldName, 0L, 9_400, players, System.currentTimeMillis());
    }

    private static Bytes world(String name) {
        return DiscoveryFixtures.worldHash(name);
    }

    @Test
    void twoPeersAnnounceTwoWorldsAndQueriesStayIsolated() throws Exception {
        Optional<Path> binary = trackerBinary();
        assumeThat(binary)
                .as("nodera-tracker binary (run: cd rust && cargo build --release)")
                .isPresent();
        TrackerClient.Endpoint endpoint = startTracker(binary.get());

        Bytes worldA = world("survival");
        Bytes worldB = world("creative");
        Bytes manifestA = DiscoveryFixtures.manifestHash(101);

        try (TrackerClient host = new TrackerClient(List.of(endpoint), NodeIdentity.generate());
             TrackerClient player = new TrackerClient(List.of(endpoint), NodeIdentity.generate())) {

            Map<TrackerClient.Endpoint, TrackerAnnounceAck> acks = host.announce(announce(
                    host, worldA, AnnounceEvent.STARTED,
                    Set.of(PeerRole.FULL_ARCHIVE, PeerRole.WORLD_SEEDER),
                    List.of(new ManifestHolding(manifestA, DiscoveryFixtures.bitmapOf(Set.of(0, 1, 2)))),
                    "Survival"));
            assertThat(acks).as("a JDK-signed announce verifies inside the Rust service")
                    .hasSize(1);
            assertThat(acks.get(endpoint).accepted()).isTrue();
            assertThat(acks.get(endpoint).nextAnnounceAfterSeconds())
                    .as("the tracker paces the peer, not the other way round")
                    .isEqualTo(ANNOUNCE_INTERVAL_SECONDS);
            assertThat(host.announceIntervalSeconds()).isEqualTo(ANNOUNCE_INTERVAL_SECONDS);

            player.announce(announce(player, worldA, AnnounceEvent.STARTED,
                    Set.of(PeerRole.PARTIAL_ARCHIVE), List.of(), ""));
            player.announce(announce(player, worldB, AnnounceEvent.STARTED,
                    Set.of(PeerRole.PARTIAL_ARCHIVE), List.of(), ""));

            TrackerResponse a = player.query(worldA).orElseThrow();
            assertThat(a.worldName()).isEqualTo("Survival");
            // Two peers announcing, neither with a game in the world: no players. This asserted
            // `2` — the peer count under a player's name.
            assertThat(a.worldPlayerCount()).isZero();
            assertThat(a.peers()).hasSize(2);
            assertThat(a.seeders()).hasSize(1);
            assertThat(a.seeders().get(0).manifestRoot()).isEqualTo(manifestA);
            assertThat(a.storedChunks()).isEqualTo(3L);
            assertThat(a.reliabilityBps()).isEqualTo(9_400);
            assertThat(a.health()).isEqualTo(WorldHealth.HEALTHY);

            TrackerResponse b = player.query(worldB).orElseThrow();
            assertThat(b.worldPlayerCount()).isZero();
            assertThat(b.seeders()).isEmpty();
            assertThat(b.worldName()).as("a non-host cannot name a world").isEmpty();
        }
    }

    @Test
    void aSilentPeerExpiresAndTheWorldListSurvivesEverySeederLeaving() throws Exception {
        Optional<Path> binary = trackerBinary();
        assumeThat(binary).as("nodera-tracker binary").isPresent();
        TrackerClient.Endpoint endpoint = startTracker(binary.get());

        Bytes worldA = world("survival");

        try (TrackerClient host = new TrackerClient(List.of(endpoint), NodeIdentity.generate());
             TrackerClient observer = new TrackerClient(List.of(endpoint), NodeIdentity.generate())) {

            // The host is the only seeder, and it registers the world's name + a countdown that has
            // already elapsed, so DEAD is reachable as soon as it stops seeding.
            host.announce(host.buildAnnounce(
                    worldA, AnnounceEvent.STARTED, List.of("127.0.0.1:25599"),
                    capabilities(Set.of(PeerRole.FULL_ARCHIVE, PeerRole.WORLD_SEEDER)),
                    List.of(new ManifestHolding(DiscoveryFixtures.manifestHash(1),
                            DiscoveryFixtures.bitmapOf(Set.of(0)))),
                    "Survival", 1L, 9_400, System.currentTimeMillis()));

            assertThat(observer.query(worldA).orElseThrow().health()).isEqualTo(WorldHealth.HEALTHY);

            // Silence past the TTL: no STOPPED, the way a crashed peer leaves.
            Thread.sleep(Duration.ofSeconds(PEER_TTL_SECONDS + 2).toMillis());

            TrackerResponse afterExpiry = observer.query(worldA).orElseThrow();
            assertThat(afterExpiry.peers())
                    .as("the world list is still served with every Java seeder gone (L-44)")
                    .isEmpty();
            assertThat(afterExpiry.worldName()).isEqualTo("Survival");
            assertThat(afterExpiry.health()).isEqualTo(WorldHealth.DEAD);
            assertThat(afterExpiry.retentionDeadlineEpochMillis()).isEqualTo(1L);
        }
    }

    @Test
    void theMultiplayerViewModelRendersRowsFromTheRealBinary() throws Exception {
        // Task 28 acceptance #4: the Task 26 GUI feed, end to end and headless. The view model is
        // the Minecraft-free half of the multiplayer screen, so this proves the whole read path —
        // Rust service → frozen TrackerResponse → rendered row — without a GUI environment.
        Optional<Path> binary = trackerBinary();
        assumeThat(binary).as("nodera-tracker binary").isPresent();
        TrackerClient.Endpoint endpoint = startTracker(binary.get());

        Bytes worldA = world("survival");
        try (TrackerClient host = new TrackerClient(List.of(endpoint), NodeIdentity.generate())) {
            host.announce(announce(host, worldA, AnnounceEvent.STARTED,
                    Set.of(PeerRole.FULL_ARCHIVE, PeerRole.WORLD_SEEDER),
                    List.of(new ManifestHolding(DiscoveryFixtures.manifestHash(1),
                            DiscoveryFixtures.bitmapOf(Set.of(0, 1)))),
                    // A host WITH a game open, reporting the one player in it — otherwise the row
                    // below would be rendering a population nothing had reported, which the view
                    // model now correctly refuses to print as a number.
                    "Survival", 1));

            TrackerResponse response = host.query(worldA).orElseThrow();
            TorrentWorldListView.TorrentWorldEntry entry =
                    new TorrentWorldListView.TorrentWorldEntry(
                            response.worldName(),
                            (int) response.worldPlayerCount(),
                            response.storedChunks(),
                            response.reliabilityBps(),
                            response.health(),
                            -1L,
                            "");

            Panel panel = TorrentWorldListView.panel(List.of(entry), "");
            assertThat(panel.rows()).hasSize(1);
            assertThat(panel.rows().get(0).cells())
                    .extracting(dev.nodera.diagnostics.view.Cell::key)
                    .contains(dev.nodera.diagnostics.view.Cell.RAW,
                            TorrentWorldListView.KEY_PLAYERS,
                            TorrentWorldListView.KEY_CHUNKS,
                            TorrentWorldListView.KEY_RELIABILITY,
                            TorrentWorldListView.healthKey(response.health()));
            assertThat(TorrentWorldListView.semanticOf(response.health()))
                    .isEqualTo(Semantic.WORLD_HEALTHY);
        }
    }

    @Test
    void aStoppedAnnounceRemovesThePeerWithoutWaitingForTheTtl() throws Exception {
        Optional<Path> binary = trackerBinary();
        assumeThat(binary).as("nodera-tracker binary").isPresent();
        TrackerClient.Endpoint endpoint = startTracker(binary.get());

        Bytes worldA = world("survival");
        try (TrackerClient peer = new TrackerClient(List.of(endpoint), NodeIdentity.generate())) {
            peer.announce(announce(peer, worldA, AnnounceEvent.STARTED,
                    Set.of(PeerRole.PARTIAL_ARCHIVE), List.of(), ""));
            assertThat(peer.query(worldA).orElseThrow().peers()).hasSize(1);

            peer.announce(announce(peer, worldA, AnnounceEvent.STOPPED,
                    Set.of(PeerRole.PARTIAL_ARCHIVE), List.of(), ""));
            assertThat(peer.query(worldA).orElseThrow().peers()).isEmpty();
        }
    }

    @Test
    void aTamperedAnnounceIsRejectedByTheService() throws Exception {
        Optional<Path> binary = trackerBinary();
        assumeThat(binary).as("nodera-tracker binary").isPresent();
        TrackerClient.Endpoint endpoint = startTracker(binary.get());

        Bytes worldA = world("survival");
        try (TrackerClient peer = new TrackerClient(List.of(endpoint), NodeIdentity.generate())) {
            TrackerAnnounce honest = announce(peer, worldA, AnnounceEvent.STARTED,
                    Set.of(PeerRole.PARTIAL_ARCHIVE), List.of(), "");
            // Same signature, different payload — a middlebox rewriting a record in flight.
            TrackerAnnounce tampered = new TrackerAnnounce(
                    honest.genesisHash(), honest.peer(), honest.publicKey(), honest.event(),
                    List.of("10.0.0.1:1"), honest.capabilities(), honest.holdings(),
                    honest.worldName(), honest.retentionDeadlineEpochMillis(),
                    honest.reliabilityBps(), honest.worldPlayerCount(),
                    honest.announceEpochMillis(), honest.signature());

            Map<TrackerClient.Endpoint, TrackerAnnounceAck> acks = peer.announce(tampered);
            assertThat(acks.get(endpoint).accepted()).isFalse();
            assertThat(acks.get(endpoint).reason()).isEqualTo("bad-signature");
            assertThat(peer.query(worldA).orElseThrow().worldPlayerCount())
                    .as("a rejected announce never reaches the registry")
                    .isZero();
        }
    }

    /**
     * A player count reaches the directory only from a peer that is IN the world, and is not summed.
     *
     * <p>End to end against the real tracker binary, because this is a cross-language contract: the
     * count is written by the Java announce encoder, verified as part of the signed portion, stored
     * per peer by the Rust registry, and reduced there. It used to be `live.len()` on the Rust side
     * — a peer count under a player's name — and no Java-side change could have fixed that.
     */
    @Test
    void onlyPeersInTheWorldContributeToItsPlayerCount() throws Exception {
        Optional<Path> binary = trackerBinary();
        assumeThat(binary).as("nodera-tracker binary").isPresent();
        TrackerClient.Endpoint endpoint = startTracker(binary.get());
        Bytes world = world("survival");

        try (TrackerClient seeder = new TrackerClient(List.of(endpoint), NodeIdentity.generate());
             TrackerClient hostPeer = new TrackerClient(List.of(endpoint), NodeIdentity.generate());
             TrackerClient joiner = new TrackerClient(List.of(endpoint), NodeIdentity.generate())) {

            // A seeder holds the bytes and has no game: it cannot see, and says so.
            seeder.announce(announce(seeder, world, AnnounceEvent.STARTED,
                    Set.of(PeerRole.WORLD_SEEDER), List.of(), ""));
            assertThat(seeder.query(world).orElseThrow().worldPlayerCount())
                    .as("one seeder is not one player")
                    .isZero();

            // The host and a joiner are both in the world and both see the same two people.
            hostPeer.announce(announce(hostPeer, world, AnnounceEvent.STARTED,
                    Set.of(PeerRole.FULL_ARCHIVE), List.of(), "Survival", 2));
            joiner.announce(announce(joiner, world, AnnounceEvent.STARTED,
                    Set.of(PeerRole.PARTIAL_ARCHIVE), List.of(), "", 2));

            TrackerResponse response = seeder.query(world).orElseThrow();
            assertThat(response.worldPlayerCount())
                    .as("two observers of the same two players is two, not four")
                    .isEqualTo(2);
            assertThat(response.peers()).hasSize(3);
        }
    }

    @Test
    void aRestartedTrackerIsRepopulatedByTheNextAnnounce() throws Exception {
        Optional<Path> binary = trackerBinary();
        assumeThat(binary).as("nodera-tracker binary").isPresent();
        TrackerClient.Endpoint first = startTracker(binary.get());

        Bytes worldA = world("survival");
        NodeIdentity identity = NodeIdentity.generate();
        try (TrackerClient peer = new TrackerClient(List.of(first), identity)) {
            peer.announce(announce(peer, worldA, AnnounceEvent.STARTED,
                    Set.of(PeerRole.WORLD_SEEDER), List.of(), ""));
            assertThat(peer.query(worldA).orElseThrow().peers()).hasSize(1);
        }

        // Restart: announce state is deliberately ephemeral, so the new process starts empty.
        stopTracker();
        TrackerClient.Endpoint second = startTracker(binary.get());
        try (TrackerClient peer = new TrackerClient(List.of(second), identity)) {
            assertThat(peer.query(worldA).orElseThrow().peers())
                    .as("a restarted tracker knows nothing until peers re-announce")
                    .isEmpty();

            peer.announce(announce(peer, worldA, AnnounceEvent.HEARTBEAT,
                    Set.of(PeerRole.WORLD_SEEDER), List.of(), ""));
            assertThat(peer.query(worldA).orElseThrow().peers())
                    .as("one announce interval is all it takes to repopulate")
                    .hasSize(1);
        }
    }
}
