package dev.nodera.testkit;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;

/**
 * Deterministic binary decoder for a divergence replay fixture (the inverse of
 * {@link FixtureWriter}). Reads the exact frame documented on {@link FixtureWriter}: validates the
 * {@code "NODF"} magic and format version, then decodes each self-framed field.
 *
 * <p>Thread-context: pure function; safe from any thread (one {@link CanonicalReader} per call).
 *
 * @see FixtureWriter
 * @see Fixture
 */
public final class FixtureReader {

    private FixtureReader() {}

    /**
     * Decode a fixture produced by {@link FixtureWriter#write}.
     *
     * @param bytes the fixture bytes.
     * @return the decoded fixture.
     * @throws IllegalArgumentException if {@code bytes} is null.
     * @throws IllegalStateException    if the magic or format version is wrong, or any field is
     *                                  malformed.
     * @Thread-context any thread.
     */
    public static Fixture read(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        CanonicalReader r = new CanonicalReader(bytes);
        long magic = r.readU32();
        if (magic != FixtureWriter.MAGIC) {
            throw new IllegalStateException(
                    "bad fixture magic: expected 0x" + Long.toHexString(FixtureWriter.MAGIC)
                            + " got 0x" + Long.toHexString(magic));
        }
        int formatVersion = r.readU16();
        if (formatVersion != FixtureWriter.FORMAT_VERSION) {
            throw new IllegalStateException(
                    "unsupported fixture format version: " + formatVersion);
        }
        RegionSnapshot snapshot = RegionSnapshot.decode(r);
        ActionBatch batch = ActionBatch.decode(r);
        StateRoot expectedRoot = StateRoot.decode(r);
        StateRoot gotRoot = StateRoot.decode(r);
        NodeId client = NodeId.decode(r);
        int trailing = r.available();
        if (trailing != 0) {
            throw new IllegalStateException(
                    "fixture has " + trailing + " unconsumed trailing byte(s) — corrupt or partial");
        }
        return new Fixture(snapshot, batch, expectedRoot, gotRoot, client);
    }

    /**
     * Decoded divergence fixture.
     *
     * <p>Thread-context: immutable record, safe for any thread.
     *
     * @param snapshot     the snapshot the batch was applied to.
     * @param batch        the action batch that was applied.
     * @param expectedRoot the canonical expected state root.
     * @param gotRoot      the divergent state root the reporter observed.
     * @param client       the node id of the reporting replica.
     */
    public record Fixture(
            RegionSnapshot snapshot,
            ActionBatch batch,
            StateRoot expectedRoot,
            StateRoot gotRoot,
            NodeId client) {}
}
