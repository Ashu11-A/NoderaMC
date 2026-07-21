package dev.nodera.transport.rendezvous;

import dev.nodera.core.identity.NodeId;
import dev.nodera.transport.rendezvous.TransportSelector.MessageClass;
import dev.nodera.transport.rendezvous.TransportSelector.Path;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The per-peer path policy: preference order, demotion, and the bulk-avoids-relay rule (Task 29). */
final class TransportSelectorTest {

    private static final NodeId PEER =
            new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void prefersDirectThenPunchedThenRelayed() {
        TransportSelector selector = new TransportSelector();
        assertThat(selector.select(PEER, MessageClass.CONTROL, EnumSet.allOf(Path.class)))
                .isEqualTo(Path.DIRECT);
        assertThat(selector.select(PEER, MessageClass.CONTROL, EnumSet.of(Path.PUNCHED, Path.RELAYED)))
                .isEqualTo(Path.PUNCHED);
        assertThat(selector.select(PEER, MessageClass.CONTROL, EnumSet.of(Path.RELAYED)))
                .isEqualTo(Path.RELAYED);
    }

    @Test
    void aFailureDemotesTheDirectPathToTheNextBest() {
        TransportSelector selector = new TransportSelector();
        selector.recordFailure(PEER, Path.DIRECT);
        assertThat(selector.select(PEER, MessageClass.CONTROL, EnumSet.allOf(Path.class)))
                .isEqualTo(Path.PUNCHED);
    }

    @Test
    void successRePromotesADemotedPath() {
        TransportSelector selector = new TransportSelector();
        selector.recordFailure(PEER, Path.DIRECT);
        selector.recordSuccess(PEER, Path.DIRECT);
        assertThat(selector.select(PEER, MessageClass.CONTROL, EnumSet.allOf(Path.class)))
                .isEqualTo(Path.DIRECT);
    }

    @Test
    void whenEveryPathIsDemotedItRetriesTheMostPreferred() {
        TransportSelector selector = new TransportSelector();
        for (Path path : Path.values()) {
            selector.recordFailure(PEER, path);
        }
        // Nothing is reachable by policy; rather than fail, it clears and retries the best.
        assertThat(selector.select(PEER, MessageClass.CONTROL, EnumSet.allOf(Path.class)))
                .isEqualTo(Path.DIRECT);
    }

    @Test
    void bulkTrafficAvoidsTheRelayWhileADirectPathIsUp() {
        TransportSelector selector = new TransportSelector();
        assertThat(selector.select(PEER, MessageClass.BULK, EnumSet.of(Path.DIRECT, Path.RELAYED)))
                .isEqualTo(Path.DIRECT);
        // With only the relay available, bulk still uses it — a last resort, not a refusal.
        assertThat(selector.select(PEER, MessageClass.BULK, EnumSet.of(Path.RELAYED)))
                .isEqualTo(Path.RELAYED);
    }

    @Test
    void anEmptyPathSetIsRejected() {
        TransportSelector selector = new TransportSelector();
        assertThatThrownBy(() -> selector.select(PEER, MessageClass.CONTROL, EnumSet.noneOf(Path.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
