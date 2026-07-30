package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * A seeder's reply to a {@link WorldManifestQuery}: the piece manifests it holds for the world.
 *
 * <p>Each entry is one <b>canonically-encoded {@code PieceManifest}</b> frame, carried opaquely:
 * {@code transport} cannot depend on the {@code peer} module that owns the manifest type
 * (Task 0 §7 layering), and opacity costs nothing because the receiver must re-decode and
 * re-verify the manifest root anyway before trusting a single byte of it. An empty list is a
 * valid answer ("I hold nothing for that world").
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param worldId   the world the query asked about.
 * @param manifests canonically-encoded {@code PieceManifest} frames, newest first.
 */
public record WorldManifestAnswer(Bytes worldId, List<Bytes> manifests) implements NoderaMessage {

    public WorldManifestAnswer {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(manifests, "manifests");
        for (Bytes m : manifests) {
            Objects.requireNonNull(m, "manifest");
        }
        manifests = List.copyOf(manifests);
    }

    @Override
    public String toString() {
        return "WorldManifestAnswer[" + worldId.toShortHex(6) + " x" + manifests.size() + "]";
    }
}
