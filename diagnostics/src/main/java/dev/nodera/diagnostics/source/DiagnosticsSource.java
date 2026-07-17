package dev.nodera.diagnostics.source;

/**
 * A capture seam: something that contributes a slice of a
 * {@link dev.nodera.diagnostics.model.TelemetrySnapshot} (Task 18).
 *
 * <p>Implementations live in other modules — {@code PeerRuntime} (peer-runtime) contributes session
 * + peer links; {@link RegionOwnershipProvider} and {@link EntityControlProvider} are no-op stubs
 * here that Tasks 6 and 12 replace with real providers. {@link #contribute} runs on the collector's
 * single sample thread.
 *
 * <p>Thread-context: the collector sample thread.
 */
public interface DiagnosticsSource {

    /**
     * Write this source's slice into the builder.
     *
     * @param b the accumulating snapshot builder.
     */
    void contribute(SnapshotBuilder b);
}
