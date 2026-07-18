package dev.nodera.core.crypto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Append-only registry snapshot for the core {@link TypeTags} (mirrors
 * {@code MessageCodecTypeTagTest} in protocol). {@link TypeTags} declares its numbers a "frozen
 * wire/hash contract: never renumber an existing tag — append only." This test pins the numeric
 * value of every assigned tag so an accidental renumber fails loudly here rather than silently
 * corrupting the hash/wire identity at the network boundary, and asserts all assigned tags are
 * distinct.
 *
 * <p>Thread-context: single test thread.
 */
final class TypeTagsTest {

    @Test
    void everyAssignedTagMatchesItsExpectedConstantValue() {
        // identity
        assertThat(TypeTags.NODE_ID).isEqualTo(1);
        assertThat(TypeTags.NODE_CAPABILITIES).isEqualTo(2);
        assertThat(TypeTags.PEER_ROLE).isEqualTo(3);
        // region
        assertThat(TypeTags.DIMENSION_KEY).isEqualTo(10);
        assertThat(TypeTags.REGION_ID).isEqualTo(11);
        assertThat(TypeTags.REGION_EPOCH).isEqualTo(12);
        assertThat(TypeTags.REGION_BOUNDS).isEqualTo(13);
        assertThat(TypeTags.REGION_REPLICA_ROLE).isEqualTo(14);
        assertThat(TypeTags.REGION_LEASE).isEqualTo(15);
        assertThat(TypeTags.REGION_COMMITTEE).isEqualTo(16);
        assertThat(TypeTags.REGION_PLACEMENT_POL).isEqualTo(17);
        // action
        assertThat(TypeTags.N_BLOCK_POS).isEqualTo(20);
        assertThat(TypeTags.ACTION_ENVELOPE).isEqualTo(21);
        assertThat(TypeTags.ACTION_BATCH).isEqualTo(22);
        assertThat(TypeTags.PLACE_BLOCK_ACTION).isEqualTo(23);
        assertThat(TypeTags.BREAK_BLOCK_ACTION).isEqualTo(24);
        assertThat(TypeTags.DROP_ITEM_ACTION).isEqualTo(25);
        assertThat(TypeTags.PICKUP_ITEM_ACTION).isEqualTo(26);
        assertThat(TypeTags.INTERACT_BLOCK_ACTION).isEqualTo(27);
        assertThat(TypeTags.ATTACK_ENTITY_ACTION).isEqualTo(28);
        // state
        assertThat(TypeTags.SNAPSHOT_VERSION).isEqualTo(30);
        assertThat(TypeTags.STATE_ROOT).isEqualTo(31);
        assertThat(TypeTags.BLOCK_MUTATION).isEqualTo(32);
        assertThat(TypeTags.CHUNK_COLUMN_STATE).isEqualTo(33);
        assertThat(TypeTags.REGION_SNAPSHOT).isEqualTo(34);
        assertThat(TypeTags.REGION_DELTA).isEqualTo(35);
        assertThat(TypeTags.SCHEDULED_TICK_ENTRY).isEqualTo(36);
        assertThat(TypeTags.BLOCK_EVENT_ENTRY).isEqualTo(37);
        // events
        assertThat(TypeTags.COMMITTED_EVENT_ENV).isEqualTo(40);
        assertThat(TypeTags.BLOCK_CHANGED_EVENT).isEqualTo(41);
        // consensus certificates
        assertThat(TypeTags.SIGNED_VOTE).isEqualTo(50);
        assertThat(TypeTags.VOTE_DECISION).isEqualTo(51);
        assertThat(TypeTags.QUORUM_CERTIFICATE).isEqualTo(52);
        assertThat(TypeTags.COMMITTEE_CHANGE_CERT).isEqualTo(53);
        assertThat(TypeTags.SERVER_AUTH_CERT).isEqualTo(54);
        assertThat(TypeTags.GATEWAY_TRANSFER_CERT).isEqualTo(55);
        // coordinator persistence
        assertThat(TypeTags.RELIABILITY_LEDGER).isEqualTo(60);
        assertThat(TypeTags.COORDINATOR_STATE).isEqualTo(61);
        // torrent distribution data plane (Task 19)
        assertThat(TypeTags.PIECE).isEqualTo(70);
        assertThat(TypeTags.PIECE_MANIFEST).isEqualTo(71);
        assertThat(TypeTags.WORLD_KEY_MATERIAL).isEqualTo(72);
        // tracker / discovery (Task 20)
        assertThat(TypeTags.WORLD_HEALTH).isEqualTo(73);
        assertThat(TypeTags.NODE_IDENTITY_SECRET).isEqualTo(74);
        assertThat(TypeTags.INVITATION).isEqualTo(75);
        assertThat(TypeTags.CACHED_PEER).isEqualTo(76);
        // watermark
        assertThat(TypeTags.NEXT).isEqualTo(76);
    }

    @Test
    void allAssignedTagsAreDistinct() throws IllegalAccessException {
        Map<Integer, String> seen = new LinkedHashMap<>();
        for (Field f : TypeTags.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            if (f.getName().equals("NEXT")) {
                continue; // NEXT is a watermark alias, expected to equal the highest tag
            }
            int value = f.getInt(null);
            String prior = seen.put(value, f.getName());
            assertThat(prior)
                    .as("duplicate tag %d: %s and %s", value, prior, f.getName())
                    .isNull();
        }
    }
}
