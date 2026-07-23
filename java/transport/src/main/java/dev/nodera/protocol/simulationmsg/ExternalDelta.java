package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/** Server-authority version advance for a delegated region, including its canonical snapshot tick. */
public record ExternalDelta(
        RegionId region,
        SnapshotVersion baseVersion,
        Bytes encodedDelta,
        Bytes certificateBytes,
        long tick,
        int bodyVersion
) implements NoderaMessage {

    public static final int EXTERNAL_DELTA_ENCODING_VERSION = 2;

    /** Legacy v1 constructor; v1 frames did not carry the resulting snapshot tick. */
    public ExternalDelta(
            RegionId region, SnapshotVersion baseVersion,
            Bytes encodedDelta, Bytes certificateBytes) {
        this(region, baseVersion, encodedDelta, certificateBytes, 0L, 1);
    }

    /** Current transition with explicit resulting snapshot tick. */
    public ExternalDelta(
            RegionId region, SnapshotVersion baseVersion,
            Bytes encodedDelta, Bytes certificateBytes, long tick) {
        this(region, baseVersion, encodedDelta, certificateBytes,
                tick, EXTERNAL_DELTA_ENCODING_VERSION);
    }

    public ExternalDelta {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(baseVersion, "baseVersion");
        Objects.requireNonNull(encodedDelta, "encodedDelta");
        Objects.requireNonNull(certificateBytes, "certificateBytes");
        if (bodyVersion < 1 || bodyVersion > EXTERNAL_DELTA_ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported ExternalDelta body version " + bodyVersion);
        }
        if (bodyVersion == 1 && tick != 0L) {
            throw new IllegalArgumentException("legacy ExternalDelta cannot carry tick");
        }
    }
}
