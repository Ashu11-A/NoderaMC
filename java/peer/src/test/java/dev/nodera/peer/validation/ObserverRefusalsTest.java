package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refusal half of a node that validates nothing. What is under test is small and load-bearing:
 * a seatless node must be able to tell the mesh that a region cannot be validated, exactly once per
 * region, and one unreachable member must not stop the rest being told.
 */
final class ObserverRefusalsTest {

    private static final RegionId REGION = new RegionId(DimensionKey.of("minecraft", "the_nether"), 0, 0);
    private static final RegionId OTHER = new RegionId(DimensionKey.of("minecraft", "the_nether"), 1, 0);

    /** Records what was sent where; optionally refuses one target. */
    private static final class RecordingTransport implements PeerTransport {
        private final List<PeerAddress> sent = new ArrayList<>();
        private final AtomicReference<NodeId> unreachable = new AtomicReference<>();

        @Override public void start() { }

        @Override public void stop() { }

        @Override public void send(PeerAddress peer, byte[] frame) {
            if (peer.nodeId().equals(unreachable.get())) {
                throw new TransportException("unreachable");
            }
            sent.add(peer);
        }

        @Override public void sendStream(PeerAddress to, long streamId, byte[] payload) {
            send(to, payload);
        }

        @Override public void setHandler(dev.nodera.transport.MessageHandler handler) { }

        @Override public String listenRoute() {
            return "127.0.0.1:0";
        }
    }

    private static PeerEntry member(long id, String route) {
        return new PeerEntry(new NodeId(new UUID(0, id)), route, NodeCapabilities.initial(),
                false, Bytes.unsafeWrap(new byte[32]), "test");
    }

    @Test
    @DisplayName("a refusal reaches every member with a route")
    void everyMemberIsTold() {
        RecordingTransport transport = new RecordingTransport();
        List<PeerEntry> members = List.of(member(1, "127.0.0.1:1"), member(2, "127.0.0.1:2"));
        ObserverRefusals refusals = new ObserverRefusals(transport, () -> members);

        assertThat(refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY)).isTrue();

        assertThat(transport.sent).hasSize(2);
        assertThat(refusals.isRefused(REGION)).isTrue();
        assertThat(refusals.refusedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a region is refused once, however many mobs walk through it")
    void repeatsAnnounceNothing() {
        RecordingTransport transport = new RecordingTransport();
        ObserverRefusals refusals = new ObserverRefusals(
                transport, () -> List.of(member(1, "127.0.0.1:1")));

        assertThat(refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY)).isTrue();
        for (int i = 0; i < 10; i++) {
            assertThat(refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY)).isFalse();
        }

        assertThat(transport.sent)
                .as("a nether full of piglins is one announcement, not a hundred")
                .hasSize(1);
    }

    @Test
    @DisplayName("one unreachable member never stops the others hearing it")
    void anUnreachableMemberIsNotFatal() {
        RecordingTransport transport = new RecordingTransport();
        transport.unreachable.set(new NodeId(new UUID(0, 1)));
        List<PeerEntry> members = List.of(
                member(1, "127.0.0.1:1"), member(2, "127.0.0.1:2"), member(3, "127.0.0.1:3"));
        ObserverRefusals refusals = new ObserverRefusals(transport, () -> members);

        assertThat(refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY)).isTrue();

        // A member that never hears it goes on validating a region this node knows it cannot —
        // which is the divergence the refusal exists to prevent.
        assertThat(transport.sent).hasSize(2);
    }

    @Test
    @DisplayName("a member with no route is skipped rather than dialled")
    void routelessMembersAreSkipped() {
        RecordingTransport transport = new RecordingTransport();
        ObserverRefusals refusals = new ObserverRefusals(
                transport, () -> List.of(member(1, ""), member(2, "127.0.0.1:2")));

        refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);

        assertThat(transport.sent).hasSize(1);
    }

    @Test
    @DisplayName("the member list is read per announcement, so a late joiner is told")
    void membersAreResolvedFreshly() {
        RecordingTransport transport = new RecordingTransport();
        List<PeerEntry> live = new ArrayList<>(List.of(member(1, "127.0.0.1:1")));
        ObserverRefusals refusals = new ObserverRefusals(transport, () -> live);

        refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
        live.add(member(2, "127.0.0.1:2"));
        refusals.refuse(OTHER, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);

        assertThat(transport.sent).hasSize(3);
        assertThat(refusals.refusedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("clearing forgets the refusals — a new plan starts from nothing")
    void clearResets() {
        RecordingTransport transport = new RecordingTransport();
        ObserverRefusals refusals = new ObserverRefusals(
                transport, () -> List.of(member(1, "127.0.0.1:1")));
        refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY);

        refusals.clear();

        assertThat(refusals.isRefused(REGION)).isFalse();
        assertThat(refusals.refuse(REGION, RegionRefusal.Reason.NON_DELEGABLE_ENTITY)).isTrue();
    }

    @Test
    @DisplayName("nulls are refused at construction and ignored at the call")
    void argumentsAreChecked() {
        RecordingTransport transport = new RecordingTransport();
        assertThatThrownBy(() -> new ObserverRefusals(null, List::of))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObserverRefusals(transport, null))
                .isInstanceOf(IllegalArgumentException.class);

        ObserverRefusals refusals = new ObserverRefusals(transport, List::of);
        assertThat(refusals.refuse(null, RegionRefusal.Reason.NON_DELEGABLE_ENTITY)).isFalse();
        assertThat(refusals.refuse(REGION, null)).isFalse();
        assertThat(refusals.isRefused(null)).isFalse();
    }
}
