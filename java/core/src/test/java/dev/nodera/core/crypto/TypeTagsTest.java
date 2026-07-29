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

    /**
     * The registry snapshot: every constant {@link TypeTags} declares, with its frozen value.
     *
     * <p>This map is compared against reflection in both directions, so it is a <b>total</b>
     * snapshot rather than a list somebody maintains by hand. The previous version of this test
     * asserted a hand-written subset and had silently dropped {@code CONTAINER_ACTION} — a tag that
     * is live on the wire — which is exactly the failure mode a snapshot test exists to prevent.
     */
    private static Map<String, Integer> expectedTags() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        // identity
        expected.put("NODE_ID", 1);
        expected.put("NODE_CAPABILITIES", 2);
        expected.put("PEER_ROLE", 3);
        // region
        expected.put("DIMENSION_KEY", 10);
        expected.put("REGION_ID", 11);
        expected.put("REGION_EPOCH", 12);
        expected.put("REGION_BOUNDS", 13);
        expected.put("REGION_REPLICA_ROLE", 14);
        expected.put("REGION_LEASE", 15);
        expected.put("REGION_COMMITTEE", 16);
        expected.put("REGION_PLACEMENT_POL", 17);
        // action
        expected.put("N_BLOCK_POS", 20);
        expected.put("ACTION_ENVELOPE", 21);
        expected.put("ACTION_BATCH", 22);
        expected.put("PLACE_BLOCK_ACTION", 23);
        expected.put("BREAK_BLOCK_ACTION", 24);
        expected.put("DROP_ITEM_ACTION", 25);
        expected.put("PICKUP_ITEM_ACTION", 26);
        expected.put("INTERACT_BLOCK_ACTION", 27);
        expected.put("ATTACK_ENTITY_ACTION", 28);
        expected.put("CONTAINER_ACTION", 29);
        // state
        expected.put("SNAPSHOT_VERSION", 30);
        expected.put("STATE_ROOT", 31);
        expected.put("BLOCK_MUTATION", 32);
        expected.put("CHUNK_COLUMN_STATE", 33);
        expected.put("REGION_SNAPSHOT", 34);
        expected.put("REGION_DELTA", 35);
        expected.put("SCHEDULED_TICK_ENTRY", 36);
        expected.put("BLOCK_EVENT_ENTRY", 37);
        // events
        expected.put("COMMITTED_EVENT_ENV", 40);
        expected.put("BLOCK_CHANGED_EVENT", 41);
        // consensus certificates
        expected.put("SIGNED_VOTE", 50);
        expected.put("VOTE_DECISION", 51);
        expected.put("QUORUM_CERTIFICATE", 52);
        expected.put("COMMITTEE_CHANGE_CERT", 53);
        expected.put("SERVER_AUTH_CERT", 54);
        expected.put("GATEWAY_TRANSFER_CERT", 55);
        // coordinator persistence
        expected.put("RELIABILITY_LEDGER", 60);
        expected.put("COORDINATOR_STATE", 61);
        // torrent distribution data plane (Task 19)
        expected.put("PIECE", 70);
        expected.put("PIECE_MANIFEST", 71);
        expected.put("WORLD_KEY_MATERIAL", 72);
        // tracker / discovery (Task 20)
        expected.put("WORLD_HEALTH", 73);
        expected.put("NODE_IDENTITY_SECRET", 74);
        expected.put("INVITATION", 75);
        expected.put("CACHED_PEER", 76);
        // archive replication / repair (Task 21)
        expected.put("ARCHIVE_REPLICA_ASSIGNMENT", 77);
        expected.put("ARCHIVE_REPLICA_ACK", 78);
        // reliability multi-factor signals (Task 22)
        expected.put("RELIABILITY_FACTORS", 79);
        // per-world content encryption (Task 23)
        expected.put("ENCRYPTED_PIECE", 80);
        // event-sourced storage persistence (Task 9)
        expected.put("CONTENT_ID", 81);
        expected.put("CHECKPOINT", 82);
        expected.put("GENESIS_MANIFEST", 83);
        // entity lane foundation (Task 12a)
        expected.put("FIXED_VEC3", 84);
        expected.put("NETWORK_ENTITY_ID", 85);
        expected.put("PERSISTED_ENTITY_STATE", 86);
        expected.put("ENTITY_CREATED_EVENT", 87);
        expected.put("ENTITY_UPDATED_EVENT", 88);
        expected.put("ENTITY_REMOVED_EVENT", 89);
        // rendezvous / relay (Task 29)
        expected.put("PEER_CANDIDATE", 90);
        expected.put("SIGNED_PEER_RECORD", 91);
        // world identity + permissions (Task 33)
        expected.put("WORLD_IDENTITY", 92);
        expected.put("WORLD_PERMISSION_GRANT", 93);
        // entity state transitions (Task 12a)
        expected.put("ENTITY_MUTATION", 94);
        expected.put("INVENTORY_CREDIT", 95);
        expected.put("ENTITY_TRANSFER_CERT", 96);
        expected.put("ENTITY_TRANSFER_PREPARED_EVENT", 97);
        expected.put("ENTITY_TRANSFER_COMMITTED_EVENT", 98);
        expected.put("ENTITY_TRANSFER_INTENT", 99);
        expected.put("ENTITY_TRANSFER_DESCRIPTOR", 100);
        expected.put("ENTITY_TRANSFER_ACCEPTED_EVENT", 101);
        expected.put("ENTITY_TRANSFER_RECORD", 102);
        expected.put("CERTIFIED_WORLD_GENESIS", 103);
        // watermark
        expected.put("GENESIS_RECERTIFICATION", 104);
        expected.put("CONTAINER_ENTRY", 105);
        expected.put("MOVE_PLAYER_ACTION", 106);
        expected.put("WORLD_PERMISSION_SET", 107);
        expected.put("COMMAND_ACTION", 108);
        // world ownership: a world's own key pair, the claim that binds it to its creator, the
        // challenge proof signed with it, and the peer's persisted list of worlds.
        expected.put("WORLD_OWNERSHIP", 109);
        expected.put("WORLD_KEY_SECRET", 110);
        expected.put("WORLD_ADMIN_PROOF", 111);
        expected.put("WORLD_REGISTRY", 112);
        expected.put("WORLD_SHARE_LINK", 113);
        expected.put("WORLD_TOMBSTONE", 114);
        // The service directory: a rendezvous' or tracker's own signed record, the score a tracker
        // aggregates for it, one peer's measurement of it, and the directory row that carries both.
        expected.put("SERVICE_RECORD", 115);
        expected.put("SERVICE_SCORE", 116);
        expected.put("SERVICE_OBSERVATION", 117);
        expected.put("SERVICE_DIRECTORY_ENTRY", 118);
        expected.put("HALO_ENDORSEMENT", 119);
        expected.put("NEXT", 119);
        return expected;
    }

    /** Every declared {@code public static final int} on {@link TypeTags}, by name. */
    private static Map<String, Integer> declaredTags() throws IllegalAccessException {
        Map<String, Integer> declared = new LinkedHashMap<>();
        for (Field f : TypeTags.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            declared.put(f.getName(), f.getInt(null));
        }
        return declared;
    }

    @Test
    void everyAssignedTagMatchesItsExpectedConstantValue() throws IllegalAccessException {
        assertThat(declaredTags())
                .as("the TypeTags registry is a frozen wire/hash contract: append only, never "
                        + "renumber. A new tag must be pinned here in the same commit that adds it.")
                .containsExactlyInAnyOrderEntriesOf(expectedTags());
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
