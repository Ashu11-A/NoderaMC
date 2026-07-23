package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class InventoryCreditTest {

    private static final NodeId ACTOR = new NodeId(new UUID(1, 2));

    @Test
    void roundTripsUnsignedItemIdAndMaximumCount() {
        InventoryCredit credit = new InventoryCredit(
                ACTOR, new NetworkEntityId(3), 0xF000_0001, 255);
        CanonicalWriter w = new CanonicalWriter();
        credit.encode(w);
        assertThat(InventoryCredit.decode(new CanonicalReader(w.toByteArray()))).isEqualTo(credit);
    }

    @Test
    void rejectsZeroCount() {
        assertThatThrownBy(() -> new InventoryCredit(ACTOR, new NetworkEntityId(3), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCountAboveUnsignedByte() {
        assertThatThrownBy(() -> new InventoryCredit(ACTOR, new NetworkEntityId(3), 1, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullActor() {
        assertThatThrownBy(() -> new InventoryCredit(null, new NetworkEntityId(3), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullEntityId() {
        assertThatThrownBy(() -> new InventoryCredit(ACTOR, null, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongTag() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(999).writeU16(1);
        assertThatThrownBy(() -> InventoryCredit.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVENTORY_CREDIT");
    }
}
