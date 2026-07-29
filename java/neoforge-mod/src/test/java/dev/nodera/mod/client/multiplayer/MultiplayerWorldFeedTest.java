package dev.nodera.mod.client.multiplayer;

import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure mapping behind the Worlds tab: worker STATE {@code connected_worlds} + owner name →
 * {@link TorrentWorldEntry} rows. Proves a hosted world becomes a listed world with the local player
 * as owner — the fix for "my shared world never appears in Multiplayer".
 */
final class MultiplayerWorldFeedTest {

    @Test
    void hostedWorldsBecomeListedWorldsOwnedByTheLocalPlayer() {
        String state = "{\"connected_worlds\":["
                + "{\"world_id\":\"deadbeef\",\"name\":\"My World\",\"players\":2,"
                + "\"mc_route\":\"192.168.0.9:25565\"}"
                + "],\"daemon_up\":true}";
        List<TorrentWorldEntry> entries = MultiplayerWorldFeed.buildEntries(state, "Steve");

        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("My World");
            assertThat(e.playerCount()).isEqualTo(2);
            assertThat(e.hostName()).isEqualTo("Steve");
            assertThat(e.hasHost()).isTrue();
            assertThat(e.health()).isEqualTo(WorldHealth.HEALTHY);
            assertThat(e.worldIdHex()).isEqualTo("deadbeef");
            assertThat(e.mcRoute()).isEqualTo("192.168.0.9:25565");
            assertThat(e.joinable()).isTrue();
        });
    }

    @Test
    void trackerCatalogEntriesBecomeNetworkWorlds() {
        var catalog = List.of(new dev.nodera.protocol.discovery.TrackerCatalogEntry(
                dev.nodera.core.Bytes.fromHex("cafebabe"), "Ashu's SMP", 4, 128, 9_500,
                WorldHealth.HEALTHY, 0L));
        List<TorrentWorldEntry> entries = MultiplayerWorldFeed.buildNetworkEntries(catalog);

        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("Ashu's SMP");
            // A real population: the count now rides each host's tracker announce, and the tracker
            // reports the maximum across the peers that could see rather than counting announcing
            // peers. It used to be the peer count under a player's name.
            assertThat(e.playersKnown()).isTrue();
            assertThat(e.playerCount()).isEqualTo(4);
            assertThat(e.worldIdHex()).isEqualTo("cafebabe");
            assertThat(e.mcRoute()).isEmpty();
            assertThat(e.retentionSecondsRemaining()).isNegative();
        });
    }

    /**
     * MC-JOIN-1: a world this install seeds but whose game is closed is still LISTED — it is a
     * real world and its content is real — but it is not offered as enterable, and it does not
     * claim the health and reliability of a world that is up.
     */
    @Test
    void aWorldWhoseHostGameIsClosedIsListedButNotJoinable() {
        String state = "{\"connected_worlds\":["
                + "{\"world_id\":\"deadbeef\",\"name\":\"My World\",\"players\":0,\"mc_route\":\"\"}"
                + "]}";
        assertThat(MultiplayerWorldFeed.buildEntries(state, "Steve"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.mcRoute()).isEmpty();
                    assertThat(e.health()).isEqualTo(WorldHealth.DEAD);
                    assertThat(e.reliabilityBps()).isZero();
                    assertThat(e.joinable()).isFalse();
                });
    }

    /** The negative control: reopening the world (a route reappears) makes it joinable again. */
    @Test
    void reopeningTheHostGameMakesTheWorldJoinableAgain() {
        String closed = "{\"connected_worlds\":[{\"world_id\":\"deadbeef\",\"name\":\"W\","
                + "\"players\":0,\"mc_route\":\"\"}]}";
        String open = "{\"connected_worlds\":[{\"world_id\":\"deadbeef\",\"name\":\"W\","
                + "\"players\":1,\"mc_route\":\"10.0.0.4:25565\"}]}";
        assertThat(MultiplayerWorldFeed.buildEntries(closed, "Steve").getFirst().joinable()).isFalse();
        assertThat(MultiplayerWorldFeed.buildEntries(open, "Steve").getFirst().joinable()).isTrue();
    }

    /** A tracker row the directory itself calls DEAD is not offered either. */
    @Test
    void aDeadTrackerWorldIsNotJoinable() {
        var catalog = List.of(new dev.nodera.protocol.discovery.TrackerCatalogEntry(
                dev.nodera.core.Bytes.fromHex("cafebabe"), "Gone", 0, 0, 0,
                WorldHealth.DEAD, 0L));
        assertThat(MultiplayerWorldFeed.buildNetworkEntries(catalog))
                .singleElement()
                .satisfies(e -> assertThat(e.joinable()).isFalse());
    }

    /**
     * The defect that made a live world unreachable from the peers helping to host it.
     *
     * <p>A supporting peer's worker lists the world in {@code connected_worlds} with
     * {@code seeding:true} and no {@code mc_route} — it holds the bytes and runs no game for it.
     * The row therefore reads DEAD, and the merge used to let that row displace the tracker's,
     * which said HEALTHY with a player in it. Live: "Hello by Dev · players unknown · 0% reliable ·
     * Offline" on the peer while the host showed "Joinable now · 1 in this world".
     */
    @Test
    void aSupportedWorldTakesItsLivenessFromTheNetworkNotFromThisNode() {
        String state = "{\"connected_worlds\":[{\"world_id\":\"deadbeef\",\"name\":\"Hello\","
                + "\"players\":-1,\"mc_route\":\"\",\"seeding\":true}]}";
        List<TorrentWorldEntry> own = MultiplayerWorldFeed.buildEntries(state, "Dev");
        List<TorrentWorldEntry> network = MultiplayerWorldFeed.buildNetworkEntries(
                List.of(new dev.nodera.protocol.discovery.TrackerCatalogEntry(
                        dev.nodera.core.Bytes.fromHex("deadbeef"), "Hello", 1, 209, 9_000,
                        WorldHealth.HEALTHY, 0L)));

        assertThat(MultiplayerWorldFeed.hostedHere(state))
                .as("this node stores the world; it does not host it")
                .isEmpty();
        assertThat(MultiplayerWorldFeed.merge(own, network, MultiplayerWorldFeed.hostedHere(state)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.health()).isEqualTo(WorldHealth.HEALTHY);
                    assertThat(e.playerCount()).isEqualTo(1);
                    assertThat(e.joinable()).as("the player can walk into it").isTrue();
                    assertThat(e.name()).isEqualTo("Hello");
                    assertThat(e.hostName()).as("local identity is kept").isEqualTo("Dev");
                });
    }

    /**
     * The negative control, and the reason the rule is about hosting rather than about routes:
     * for a world this node HOSTS, "no game endpoint here" really does mean nobody can enter, and
     * the tracker's cheerful row must not override it (MC-JOIN-1).
     */
    @Test
    void aHostedWorldWithItsGameClosedStaysUnjoinableWhateverTheTrackerSays() {
        String state = "{\"connected_worlds\":[{\"world_id\":\"deadbeef\",\"name\":\"Mine\","
                + "\"players\":0,\"mc_route\":\"\",\"seeding\":false}]}";
        List<TorrentWorldEntry> own = MultiplayerWorldFeed.buildEntries(state, "Dev");
        List<TorrentWorldEntry> network = MultiplayerWorldFeed.buildNetworkEntries(
                List.of(new dev.nodera.protocol.discovery.TrackerCatalogEntry(
                        dev.nodera.core.Bytes.fromHex("deadbeef"), "Mine", 0, 209, 9_000,
                        WorldHealth.HEALTHY, 0L)));

        assertThat(MultiplayerWorldFeed.hostedHere(state)).containsExactly("deadbeef");
        assertThat(MultiplayerWorldFeed.merge(own, network, MultiplayerWorldFeed.hostedHere(state)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.health()).isEqualTo(WorldHealth.DEAD);
                    assertThat(e.joinable()).isFalse();
                });
    }

    /** A world only the tracker knows about is listed unchanged. */
    @Test
    void networkOnlyWorldsSurviveTheMerge() {
        List<TorrentWorldEntry> network = MultiplayerWorldFeed.buildNetworkEntries(
                List.of(new dev.nodera.protocol.discovery.TrackerCatalogEntry(
                        dev.nodera.core.Bytes.fromHex("cafebabe"), "Elsewhere", 3, 10, 8_000,
                        WorldHealth.HEALTHY, 0L)));
        assertThat(MultiplayerWorldFeed.merge(List.of(), network, java.util.Set.of()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.name()).isEqualTo("Elsewhere");
                    assertThat(e.joinable()).isTrue();
                });
    }

    @Test
    void noWorkerOrNoWorldsYieldsEmptyList() {
        assertThat(MultiplayerWorldFeed.buildEntries(null, "Steve")).isEmpty();
        assertThat(MultiplayerWorldFeed.buildEntries("{\"connected_worlds\":[]}", "Steve")).isEmpty();
    }

    @Test
    void blankNameFallsBackToWorldId() {
        String state = "{\"connected_worlds\":[{\"world_id\":\"abcd1234\",\"name\":\"\",\"players\":0}]}";
        assertThat(MultiplayerWorldFeed.buildEntries(state, "Alex"))
                .singleElement()
                .extracting(TorrentWorldEntry::name)
                .isEqualTo("abcd1234");
    }
}
