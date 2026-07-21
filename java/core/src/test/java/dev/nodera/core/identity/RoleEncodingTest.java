package dev.nodera.core.identity;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.RegionReplicaRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three role enums whose ordinals are frozen wire contracts: PeerRole, RegionReplicaRole
 * (canonical-encoded) and WorldRole (raw u8 ordinal). A reorder would silently change every
 * signed grant/assignment, so the round-trips and the frozen ordinal snapshots are asserted.
 */
class RoleEncodingTest {

    @Test
    void peerRoleRoundTripsEveryConstant() {
        for (PeerRole role : PeerRole.values()) {
            CanonicalWriter w = new CanonicalWriter();
            role.encode(w);
            assertThat(PeerRole.decode(new CanonicalReader(w.toByteArray()))).isEqualTo(role);
        }
    }

    @Test
    void regionReplicaRoleRoundTripsEveryConstant() {
        for (RegionReplicaRole role : RegionReplicaRole.values()) {
            CanonicalWriter w = new CanonicalWriter();
            role.encode(w);
            assertThat(RegionReplicaRole.decode(new CanonicalReader(w.toByteArray())))
                    .isEqualTo(role);
        }
    }

    @Test
    void regionReplicaRoleRejectsForeignTag() {
        CanonicalWriter w = new CanonicalWriter();
        PeerRole.BOOTSTRAP.encode(w);
        assertThatThrownBy(() -> RegionReplicaRole.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected REGION_REPLICA_ROLE");
    }

    @Test
    void peerRoleRejectsForeignTag() {
        CanonicalWriter w = new CanonicalWriter();
        RegionReplicaRole.PRIMARY.encode(w);
        assertThatThrownBy(() -> PeerRole.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected PEER_ROLE");
    }

    @Test
    void frozenOrdinalSnapshots() {
        assertThat(PeerRole.values()).containsExactly(
                PeerRole.BOOTSTRAP, PeerRole.RELAY, PeerRole.SESSION_GATEWAY,
                PeerRole.REGION_EXECUTOR, PeerRole.REGION_VALIDATOR, PeerRole.PARTIAL_ARCHIVE,
                PeerRole.FULL_ARCHIVE, PeerRole.WORLD_SEEDER);
        assertThat(RegionReplicaRole.values())
                .containsExactly(RegionReplicaRole.PRIMARY, RegionReplicaRole.VALIDATOR);
        assertThat(WorldRole.values()).containsExactly(
                WorldRole.BANNED, WorldRole.MEMBER, WorldRole.OPERATOR, WorldRole.OWNER);
    }

    @Test
    void worldRoleOrdinalMappingAndPredicates() {
        for (WorldRole role : WorldRole.values()) {
            assertThat(WorldRole.fromOrdinal(role.ordinal())).isEqualTo(role);
        }
        assertThatThrownBy(() -> WorldRole.fromOrdinal(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorldRole.fromOrdinal(WorldRole.values().length))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(WorldRole.OWNER.isOperator()).isTrue();
        assertThat(WorldRole.OPERATOR.isOperator()).isTrue();
        assertThat(WorldRole.MEMBER.isOperator()).isFalse();
        assertThat(WorldRole.BANNED.isOperator()).isFalse();
        assertThat(WorldRole.BANNED.canJoin()).isFalse();
        assertThat(WorldRole.MEMBER.canJoin()).isTrue();
        assertThat(WorldRole.OPERATOR.canJoin()).isTrue();
        assertThat(WorldRole.OWNER.canJoin()).isTrue();
    }
}
