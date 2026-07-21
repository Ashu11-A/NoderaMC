package dev.nodera.testkit;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;

import java.util.Objects;

/**
 * Deterministic binary encoder for a divergence replay fixture (Task 5: written to
 * {@code nodera/divergences/<ts>-<region>.bin} whenever two replicas disagree on a region's state
 * root, so the failing case can be replayed byte-for-byte offline).
 *
 * <p>A fixture bundles the {@link RegionSnapshot} the batch was computed against, the
 * {@link ActionBatch} that was applied, the two divergent {@link StateRoot}s (the {@code expected}
 * canonical root and the {@code got} root that the reporter disagrees with), and the {@link NodeId}
 * of the client/replica that reported the divergence — everything {@code ReplayFixtureTest} needs
 * to deterministically reproduce the failure.
 *
 * <p>The encoding is a thin length-prefixed frame around the canonical self-describing
 * {@code encode()} of each {@code Encodable} field, so a fixture's bytes are byte-stable across
 * runs (asserted by {@code FixtureIOTest}).
 *
 * <p>Frame layout (big-endian, fixed-width — produced by {@link CanonicalWriter}):
 * <pre>
 *   u32   magic            ("NODF" = 0x4E4F4446)
 *   u16   formatVersion    ( = 1 )
 *   ----  RegionSnapshot   (self-framed via its own encode)
 *         ActionBatch      (self-framed)
 *         StateRoot        (expectedRoot, self-framed)
 *         StateRoot        (gotRoot, self-framed)
 *         NodeId           (client, self-framed)
 * </pre>
 *
 * <p>Thread-context: pure function; safe from any thread.
 *
 * @see FixtureReader
 */
public final class FixtureWriter {

    /** Fixture file magic: the ASCII bytes of {@code "NODF"} as a big-endian u32. */
    public static final long MAGIC = 0x4E4F4446L;

    /** Current fixture format version. */
    public static final int FORMAT_VERSION = 1;

    private FixtureWriter() {}

    /**
     * Encode a divergence fixture into a fresh, caller-owned {@code byte[]}.
     *
     * @param snapshot     the snapshot the batch was applied to.
     * @param batch        the action batch that was applied.
     * @param expectedRoot the canonical expected state root.
     * @param gotRoot      the divergent state root the reporter observed.
     * @param client       the node id of the reporting replica.
     * @return the canonical fixture bytes.
     * @throws IllegalArgumentException if any argument is null.
     * @Thread-context any thread; allocates a fresh {@link CanonicalWriter}.
     */
    public static byte[] write(
            RegionSnapshot snapshot, ActionBatch batch,
            StateRoot expectedRoot, StateRoot gotRoot, NodeId client) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(expectedRoot, "expectedRoot");
        Objects.requireNonNull(gotRoot, "gotRoot");
        Objects.requireNonNull(client, "client");
        CanonicalWriter w = new CanonicalWriter();
        w.writeU32(MAGIC);
        w.writeU16(FORMAT_VERSION);
        snapshot.encode(w);
        batch.encode(w);
        expectedRoot.encode(w);
        gotRoot.encode(w);
        client.encode(w);
        return w.toByteArray();
    }
}
