package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The worlds a peer keeps on the network — the ones it <b>shares</b> (it created them and holds
 * their key) and the ones it <b>supports</b> (it keeps somebody else's world alive).
 *
 * <h2>Why this had to become a persisted thing</h2>
 *
 * <p>The worker's hosting service held this set in memory only. A restart — a crash, a settings
 * change, the companion app respawning its supervised worker, or simply the machine rebooting —
 * therefore dropped every world the node was hosting or seeding, silently: the node stopped
 * announcing them, stopped advertising the pieces it still had on disk, and the companion app's
 * world list went empty and stayed empty until a Minecraft game happened to re-share the world.
 * "Always-on peer" is the entire premise of the worker, and it could not survive its own restart.
 *
 * <p>So the registry is written on every change and reloaded at startup, and the node resumes
 * announcing exactly what it was announcing before.
 *
 * <h2>What is deliberately NOT in here</h2>
 *
 * <p>Liveness. A world's Minecraft endpoint and its player count describe a <i>running game</i>, and
 * restoring them from a file would tell the network a world is joinable when the game that made it
 * joinable is gone. Those fields come back only from a live re-host, so a restored world reads as
 * "shared, game closed" — which is what it is.
 *
 * <p>Wire form: {@code [u16 WORLD_REGISTRY][u16 version][list Entry]}, each entry
 * {@code [Bytes worldId][String name][bool supporting][u64 addedAt][u64 updatedAt]
 * [Bytes ownershipRecord]}.
 *
 * @param entries one row per world, in no particular order.
 * @Thread-context immutable value; the worker's store owns the mutable copy.
 */
public record WorldRegistry(List<Entry> entries) implements Encodable {

    public WorldRegistry {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** @return an empty registry — a node that has never shared or supported anything. */
    public static WorldRegistry empty() {
        return new WorldRegistry(List.of());
    }

    /**
     * @param worldIdHex the world.
     * @return that world's row, if this registry has one.
     */
    public Optional<Entry> find(String worldIdHex) {
        for (Entry entry : entries) {
            if (entry.worldIdHex().equalsIgnoreCase(worldIdHex)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_REGISTRY).writeU16(ENCODING_VERSION);
        w.writeList(entries, (writer, entry) -> {
            writer.writeBytes(entry.worldId());
            writer.writeString(entry.name());
            writer.writeBoolean(entry.supporting());
            writer.writeU64(entry.addedAtEpochMillis());
            writer.writeU64(entry.updatedAtEpochMillis());
            writer.writeBytes(entry.ownershipRecord());
        });
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @return the decoded registry.
     * @throws IllegalStateException if the next tag is not {@code WORLD_REGISTRY}.
     */
    public static WorldRegistry decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_REGISTRY) {
            throw new IllegalStateException("expected WORLD_REGISTRY tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        List<Entry> entries = new ArrayList<>(r.readList(reader -> new Entry(
                reader.readBytesValue(),
                reader.readString(),
                reader.readBoolean(),
                reader.readU64(),
                reader.readU64(),
                reader.readBytesValue())));
        return new WorldRegistry(entries);
    }

    /**
     * One world this peer keeps on the network.
     *
     * @param worldId          the world identity.
     * @param name             its display name, as last known.
     * @param supporting       {@code true} when this node only keeps the world's bytes alive;
     *                         {@code false} when it is the world's host.
     * @param addedAtEpochMillis   when this world first entered the network from this node.
     * @param updatedAtEpochMillis when its content last changed here.
     * @param ownershipRecord  the canonical bytes of a {@link WorldOwnership}, or
     *                         {@link Bytes#empty()} when this node does not administer the world.
     *                         Stored encoded rather than decoded so a registry written by a newer
     *                         build is still readable — an ownership record this build cannot parse
     *                         costs the admin badge, not the world.
     */
    public record Entry(Bytes worldId, String name, boolean supporting, long addedAtEpochMillis,
                        long updatedAtEpochMillis, Bytes ownershipRecord) {

        public Entry {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(ownershipRecord, "ownershipRecord");
            name = name == null ? "" : name;
        }

        /** @return the world id, hex-encoded — the key every other surface uses. */
        public String worldIdHex() {
            return worldId.toHex();
        }

        /**
         * @return whether a claim is stored for this world at all.
         *         <p><b>Not</b> "this node administers it": the claim names its owner, and a peer
         *         stores claims for worlds it merely keeps available so it can say who runs them.
         *         Ask {@link #ownership()} and compare {@code isOwner(self)} for administration.
         */
        public boolean owned() {
            return !ownershipRecord.isEmpty();
        }

        /**
         * @return the decoded, <b>verified</b> ownership claim, or empty when this node does not
         *         administer the world or the stored record no longer verifies.
         */
        public Optional<WorldOwnership> ownership() {
            if (ownershipRecord.isEmpty()) {
                return Optional.empty();
            }
            try {
                WorldOwnership ownership = WorldOwnership.decode(new CanonicalReader(ownershipRecord));
                // A stored record that does not verify is not a weaker claim than one that does; it
                // is not a claim. Reporting it as ownership would put an admin badge on a world this
                // node cannot prove anything about.
                return ownership.verify() ? Optional.of(ownership) : Optional.empty();
            } catch (RuntimeException undecodable) {
                return Optional.empty();
            }
        }
    }
}
