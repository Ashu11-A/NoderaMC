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
            assertThat(e.playerCount()).isEqualTo(4);
            assertThat(e.worldIdHex()).isEqualTo("cafebabe");
            assertThat(e.mcRoute()).isEmpty();
            assertThat(e.retentionSecondsRemaining()).isNegative();
        });
    }

    @Test
    void aWorldWhoseHostGameIsClosedListsWithoutAGameEndpoint() {
        String state = "{\"connected_worlds\":["
                + "{\"world_id\":\"deadbeef\",\"name\":\"My World\",\"players\":0,\"mc_route\":\"\"}"
                + "]}";
        assertThat(MultiplayerWorldFeed.buildEntries(state, "Steve"))
                .singleElement()
                .extracting(TorrentWorldEntry::mcRoute)
                .isEqualTo("");
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
