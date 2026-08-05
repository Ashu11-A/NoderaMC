package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.peer.discovery.CommonsPresence;
import dev.nodera.protocol.discovery.TrackerCatalogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A world this node announces but holds nothing of must be repaired.
 *
 * <p>Watched live: the companion showed a world as <em>"Yours — hosted here"</em>, <em>"You
 * administer this"</em>, <em>"0.0% · 0 of 73 pieces"</em> and <em>"3 peers holding it besides this
 * node"</em> — permanently. The node was advertising to the network a world it could not serve one
 * byte of, and nothing was ever going to change that.
 *
 * <p>The sweep's skip read {@code holdsCompletely(...) || hosts(...)}, commented "already this
 * node's problem, one way or the other". But {@code hosts()} asks the registry what this node
 * <em>claims</em>, and {@code restoreFromRegistry} reloads every row as hosted at boot whether or
 * not any content survived. So the one state that most needs repairing was the exact state that
 * disqualified it from repair — and it is self-perpetuating, because the claim is what causes the
 * skip.
 *
 * <p>{@code holdsCompletely} already covers a host that really holds its world, so removing the
 * second clause costs nothing and closes the hole.
 */
final class ReplicationRepairsEmptyClaimsTest {

    @Test
    @DisplayName("a hosted world with no content is adopted, whatever the placement policy says")
    void anEmptyClaimIsRepaired() {
        // Not placed here, and the bounds are exhausted: neither may excuse a broken claim, because
        // no other node can fix this one's advertisement.
        assertThat(WorldReplicationService.shouldAdopt(false, true, false, false)).isTrue();
    }

    @Test
    @DisplayName("a hosted world already held completely is left alone")
    void aHealthyHostIsNotRefetched() {
        assertThat(WorldReplicationService.shouldAdopt(true, true, true, true)).isFalse();
    }

    @Test
    @DisplayName("a world this node neither holds nor claims still obeys placement and bounds")
    void volunteeredReplicasStayBounded() {
        // The guarantee the class doc makes: a peer is never volunteered into filling its disk.
        assertThat(WorldReplicationService.shouldAdopt(false, false, true, false)).isFalse();
        assertThat(WorldReplicationService.shouldAdopt(false, false, false, true)).isFalse();
        assertThat(WorldReplicationService.shouldAdopt(false, false, true, true)).isTrue();
    }

    @Test
    @DisplayName("the replica bounds are a conjunction of the count and the byte budget")
    void boundsHoldOnEitherAxis() {
        assertThat(WorldReplicationService.withinBounds(0, 0L, 1_000L)).isTrue();
        assertThat(WorldReplicationService.withinBounds(0, 1_000L, 1_000L)).isFalse();
        assertThat(WorldReplicationService.withinBounds(2, 0L, 1_000L)).isFalse();
    }

    @Test
    @DisplayName("the peer-presence namespace is never offered to world replication")
    void commonsIsNotAReplicableWorld() {
        TrackerCatalogEntry commons = entry(CommonsPresence.WORLD_ID, "Nodera commons");
        TrackerCatalogEntry world = entry(Bytes.fromHex("07".repeat(32)), "A real world");

        assertThat(WorldReplicationService.replicableCatalog(java.util.List.of(commons, world)))
                .containsExactly(world);
    }

    private static TrackerCatalogEntry entry(Bytes id, String name) {
        return new TrackerCatalogEntry(id, name, 0, 0, 10_000, WorldHealth.HEALTHY, 0);
    }
}
