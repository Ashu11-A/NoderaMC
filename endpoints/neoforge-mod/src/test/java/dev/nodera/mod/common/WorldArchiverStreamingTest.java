package dev.nodera.mod.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #43: the continuous-streaming cadence + the freshness-guard version marker. Both pure
 * (no Minecraft, no NeoForge config): {@code streamDue} is the tick-cadence decision the
 * server-tick hook uses, and {@code seededVersion} reads the marker the freshness guard compares
 * against the network archive's version so a stale copy can never overwrite a newer local save.
 */
final class WorldArchiverStreamingTest {

    @Test
    void firstStreamFiresImmediatelyThenHonoursTheInterval() {
        // Never seeded: due immediately (bounds loss from session start).
        assertTrue(WorldArchiver.streamDue(100, Long.MIN_VALUE, 2400));
        // Just seeded: not due again until a full interval elapsed.
        assertFalse(WorldArchiver.streamDue(100, 100, 2400));
        assertFalse(WorldArchiver.streamDue(2499, 100, 2400));
        assertTrue(WorldArchiver.streamDue(2500, 100, 2400));
    }

    @Test
    void zeroOrNegativeIntervalDisablesStreaming() {
        assertFalse(WorldArchiver.streamDue(1_000_000, Long.MIN_VALUE, 0));
        assertFalse(WorldArchiver.streamDue(1_000_000, Long.MIN_VALUE, -5));
    }

    @Test
    void seededVersionReadsTheMarkerAndDefaultsToMinusOne(@TempDir Path save) throws Exception {
        // No marker → unknown.
        assertEquals(-1, WorldArchiver.seededVersion(save));
        // Marker written (the seed path records "<manifestRootHex> <version> <pieceCount>"'s
        // version field here) → read back.
        Path marker = save.resolve("nodera").resolve("seeded-version");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "7\n");
        assertEquals(7, WorldArchiver.seededVersion(save));
        // Corrupt marker → unknown, never a throw.
        Files.writeString(marker, "not-a-number");
        assertEquals(-1, WorldArchiver.seededVersion(save));
    }
}
