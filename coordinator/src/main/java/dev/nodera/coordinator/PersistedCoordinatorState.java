package dev.nodera.coordinator;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The durable slice of coordinator state (Task 6): each region's monotonic epoch plus the
 * {@link ReliabilityLedger}. Persisted verbatim in {@code NoderaSavedData} so epochs (the
 * stale-proposal defence) and reputations survive a server restart. Canonical encoding — reuse the
 * Task 2 infrastructure, never hand-roll NBT for consensus state; the mod wraps these bytes in an
 * NBT blob. The round-trip is covered by a unit test (criterion 5).
 *
 * @param epochs      region → current epoch value (sorted canonically on encode).
 * @param reliability the reliability ledger.
 * @Thread-context immutable, any thread.
 */
public record PersistedCoordinatorState(
        Map<RegionId, Long> epochs,
        ReliabilityLedger reliability
) implements Encodable {

    private static final Comparator<RegionId> REGION_ORDER =
            Comparator.comparing((RegionId r) -> r.dimension().toString())
                    .thenComparingInt(RegionId::regionX)
                    .thenComparingInt(RegionId::regionZ);

    public PersistedCoordinatorState {
        if (epochs == null) {
            throw new IllegalArgumentException("epochs must not be null");
        }
        if (reliability == null) {
            throw new IllegalArgumentException("reliability must not be null");
        }
        TreeMap<RegionId, Long> sorted = new TreeMap<>(REGION_ORDER);
        sorted.putAll(epochs);
        epochs = sorted;
    }

    /** Capture the durable state from a live {@link LeaseManager} + {@link ReliabilityLedger}. */
    public static PersistedCoordinatorState capture(LeaseManager leases, ReliabilityLedger reliability) {
        return new PersistedCoordinatorState(leases.epochsView(), reliability);
    }

    /** Restore the epochs into a fresh {@link LeaseManager} (reliability is used directly). */
    public LeaseManager toLeaseManager() {
        LeaseManager lm = new LeaseManager();
        for (Map.Entry<RegionId, Long> e : epochs.entrySet()) {
            lm.restoreEpoch(e.getKey(), e.getValue());
        }
        return lm;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.COORDINATOR_STATE).writeU16(ENCODING_VERSION);
        List<Map.Entry<RegionId, Long>> entries = new ArrayList<>(epochs.entrySet());
        // epochs is a TreeMap in REGION_ORDER, so iteration is canonical.
        w.writeList(entries, (ww, e) -> {
            e.getKey().encode(ww);
            ww.writeU64(e.getValue());
        });
        reliability.encode(w);
    }

    public static PersistedCoordinatorState decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.COORDINATOR_STATE) {
            throw new IllegalStateException("expected COORDINATOR_STATE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Map<RegionId, Long> epochs = new TreeMap<>(REGION_ORDER);
        List<Map.Entry<RegionId, Long>> entries = r.readList(rr -> {
            RegionId region = RegionId.decode(rr);
            long epoch = rr.readU64();
            return Map.entry(region, epoch);
        });
        for (Map.Entry<RegionId, Long> e : entries) {
            epochs.put(e.getKey(), e.getValue());
        }
        ReliabilityLedger reliability = ReliabilityLedger.decode(r);
        return new PersistedCoordinatorState(epochs, reliability);
    }
}
