package dev.nodera.diagnostics.model;

import java.util.Map;

/**
 * Aggregate traffic statistics for one sample (Task 18).
 *
 * @param bytesTx        cumulative TX bytes.
 * @param bytesRx        cumulative RX bytes.
 * @param framesTx       cumulative TX frame count.
 * @param framesRx       cumulative RX frame count.
 * @param bytesPerSecTx  rolling TX bytes/sec.
 * @param bytesPerSecRx  rolling RX bytes/sec.
 * @param msgsPerSecTx   rolling TX frames/sec.
 * @param msgsPerSecRx   rolling RX frames/sec.
 * @param byType         per message-type-name frame counts: {@code {tx,rx}} (frame counts).
 * @Thread-context immutable record, any thread.
 */
public record NetStats(
        long bytesTx,
        long bytesRx,
        long framesTx,
        long framesRx,
        double bytesPerSecTx,
        double bytesPerSecRx,
        double msgsPerSecTx,
        double msgsPerSecRx,
        Map<String, long[]> byType) {

    /** Compact constructor copies {@code byType} into an immutable map. */
    public NetStats {
        byType = byType == null ? Map.of() : Map.copyOf(byType);
    }
}
