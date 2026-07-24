package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One container block's canonical slot contents (Task 16 / L-10) — chest/hopper inventories are
 * REGION STATE: they live in the hashed root beside the block palette, mutate only through engine
 * rules and validated actions, and replicate like any other committed state (Invariant 10 — a
 * container outside the hash is the "peers agree on blocks yet diverge on contents" class).
 *
 * <p>Slots are ordered (the slot index IS the list index) and empty slots are stored as
 * {@code (0, 0)} so the slot count is structural, not data-dependent. Two replicas holding the
 * same chest encode identical bytes by construction.
 *
 * <p>Wire form: {@code [u16 CONTAINER_ENTRY][u16 ENCODING_VERSION][NBlockPos pos]
 * [list (u32 itemStackId, u8 count)]}.
 *
 * @param pos   the container block's position.
 * @param slots the ordered slot contents; empty slots are {@code ItemSlot.EMPTY}.
 * @Thread-context immutable, any thread.
 */
public record ContainerEntry(NBlockPos pos, List<ItemSlot> slots) implements Encodable {

    public static final int ENCODING_VERSION = 1;

    /** The canonical container order inside the root: by {@code (y, z, x)} like block mutations. */
    public static final Comparator<ContainerEntry> POS_ORDER = Comparator
            .comparingInt((ContainerEntry c) -> c.pos().y())
            .thenComparingInt(c -> c.pos().z())
            .thenComparingInt(c -> c.pos().x());

    /** The largest slot table any container kind declares (double-chest ceiling). */
    public static final int MAX_SLOTS = 54;

    /** One slot: an opaque unsigned item-stack id + a count in [0, 255]; (0,0) = empty. */
    public record ItemSlot(int itemStackId, int count) {

        /** The canonical empty slot. */
        public static final ItemSlot EMPTY = new ItemSlot(0, 0);

        public ItemSlot {
            if (count < 0 || count > 255) {
                throw new IllegalArgumentException("count must be in [0, 255]: " + count);
            }
            if (count == 0 && itemStackId != 0) {
                throw new IllegalArgumentException("an empty slot must be (0, 0)");
            }
            if (count > 0 && itemStackId == 0) {
                throw new IllegalArgumentException("a filled slot needs a non-zero item id");
            }
        }

        /** @return whether this slot holds nothing. */
        public boolean isEmpty() {
            return count == 0;
        }
    }

    public ContainerEntry {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(slots, "slots");
        if (slots.isEmpty() || slots.size() > MAX_SLOTS) {
            throw new IllegalArgumentException(
                    "slot count must be in [1, " + MAX_SLOTS + "]: " + slots.size());
        }
        slots = List.copyOf(slots);
    }

    /** @return a same-position entry with {@code slot} replaced. */
    public ContainerEntry withSlot(int slot, ItemSlot value) {
        List<ItemSlot> next = new java.util.ArrayList<>(slots);
        next.set(slot, value);
        return new ContainerEntry(pos, next);
    }

    /** @return whether every slot is empty. */
    public boolean isEmpty() {
        return slots.stream().allMatch(ItemSlot::isEmpty);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.CONTAINER_ENTRY).writeU16(ENCODING_VERSION);
        pos.encode(w);
        w.writeList(slots, (ww, s) -> {
            ww.writeU32(Integer.toUnsignedLong(s.itemStackId()));
            ww.writeU8(s.count());
        });
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code CONTAINER_ENTRY}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ContainerEntry decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.CONTAINER_ENTRY) {
            throw new IllegalStateException("expected CONTAINER_ENTRY tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        NBlockPos pos = NBlockPos.decode(r);
        List<ItemSlot> slots = r.readList(rr -> new ItemSlot((int) rr.readU32(), rr.readU8()));
        return new ContainerEntry(pos, slots);
    }
}
