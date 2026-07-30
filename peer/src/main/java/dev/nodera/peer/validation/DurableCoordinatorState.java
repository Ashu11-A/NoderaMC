package dev.nodera.peer.validation;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.RegionId;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.coordinator.PersistedCoordinatorState;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The durable home of {@link PersistedCoordinatorState} — region epochs and this node's reliability
 * view — in the same state directory as the action, credit and vote journals.
 *
 * <p>The record has existed since Task 6 with nowhere to live, and the consequence was quiet: every
 * restart reset the epoch counter that defends against stale proposals, and every restart made the
 * node reputation-blind, so a peer that had spent an evening disagreeing with committed roots came
 * back with a spotless score. Reputation that resets on restart is not reputation; it is a session
 * counter with a long name.
 *
 * <p><b>Local, never consensus.</b> Nothing in this file may enter a state root — a node's opinion
 * of its peers is a view, and two honest nodes are allowed to hold different ones. That is exactly
 * why it lives beside the journals rather than in the world store's certified chain.
 *
 * <p><b>A corrupt file is not a fatal error.</b> Losing this state costs the node its memory of who
 * behaved, which is recoverable by observing the network again; refusing to start would cost it the
 * world. A damaged file is therefore reported and replaced, not thrown.
 *
 * @Thread-context synchronised; safe from the lane thread and a shutdown hook at once.
 */
public final class DurableCoordinatorState {

    private static final long MAGIC = 0x4E434F52L; // NCOR
    private static final int VERSION = 1;

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaCoordinatorState");

    private final Path file;
    private final ReliabilityLedger reliability;
    private final LeaseManager leases;

    /**
     * Load (or start) the durable coordinator state at {@code file}.
     *
     * @param file the state file; a missing file is an empty, valid state.
     */
    public DurableCoordinatorState(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
        PersistedCoordinatorState loaded = load(file);
        this.reliability = loaded.reliability();
        this.leases = loaded.toLeaseManager();
    }

    /** @return the restored reliability ledger — the live one; write to it directly. */
    public ReliabilityLedger reliability() {
        return reliability;
    }

    /** @return a lease manager carrying the restored epochs. */
    public LeaseManager leases() {
        return leases;
    }

    /** @return the restored epoch for {@code region}, or 0 when the node has never seen it. */
    public synchronized long epochOf(RegionId region) {
        return leases.epochsView().getOrDefault(region, 0L);
    }

    /**
     * Write the current epochs and reliability to disk. Cheap enough to call at a session boundary
     * or on a timer; the file is a few hundred bytes for a normal committee.
     */
    public synchronized void flush() {
        PersistedCoordinatorState state = PersistedCoordinatorState.capture(leases, reliability);
        CanonicalWriter writer = new CanonicalWriter();
        writer.writeU32(MAGIC).writeU16(VERSION);
        state.encode(writer);
        try {
            AtomicFileWriter.write(file, writer.toByteArray());
        } catch (IOException e) {
            // Durability of a local opinion is worth a warning, never a crashed session.
            LOG.warn("Nodera: could not persist coordinator state to {}: {}", file, e.toString());
        }
    }

    private static PersistedCoordinatorState load(Path file) {
        PersistedCoordinatorState empty =
                new PersistedCoordinatorState(Map.of(), new ReliabilityLedger());
        if (!Files.exists(file)) {
            return empty;
        }
        try {
            CanonicalReader reader = new CanonicalReader(Files.readAllBytes(file));
            if (reader.readU32() != MAGIC || reader.readU16() != VERSION) {
                throw new StorageException("unsupported coordinator-state header: " + file);
            }
            PersistedCoordinatorState state = PersistedCoordinatorState.decode(reader);
            if (reader.available() != 0) {
                throw new StorageException("trailing bytes in coordinator state " + file);
            }
            return state;
        } catch (IOException | RuntimeException damaged) {
            LOG.warn("Nodera: coordinator state at {} is unreadable and will be rebuilt from "
                    + "observation: {}", file, damaged.toString());
            return empty;
        }
    }
}
