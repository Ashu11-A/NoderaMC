package dev.nodera.storage;

import dev.nodera.core.region.RegionId;

import java.util.Comparator;

/**
 * The single deterministic {@link RegionId} ordering used by every store in this module
 * (dimension key, then regionX, then regionZ). Stores iterate regions in this order so that
 * encode-time and listing order are stable across backends (in-memory and RocksDB agree).
 *
 * @Thread-context any thread; stateless.
 */
public final class RegionOrder {

    /** Dimension → regionX → regionZ; total and consistent with equals. */
    public static final Comparator<RegionId> BY_DIMENSION_XZ =
            Comparator.comparing((RegionId r) -> r.dimension().toString())
                    .thenComparingInt(RegionId::regionX)
                    .thenComparingInt(RegionId::regionZ);

    private RegionOrder() {
    }
}
