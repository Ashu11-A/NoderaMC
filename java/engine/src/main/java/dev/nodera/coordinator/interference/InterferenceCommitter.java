package dev.nodera.coordinator.interference;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.ServerAuthorityCertificate;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.PipelineState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Tick-end conversion of buffered foreign writes into certified external deltas (Task 11). For each
 * region with a non-empty {@link InterferenceBuffer}:
 *
 * <ul>
 *   <li>pipeline idle → build a {@code RegionDelta} whose CAS guards are the recorded pre-write
 *       states, re-extract the live root, sign a
 *       {@link ServerAuthorityCertificate.Reason#EXTERNAL_MUTATION} certificate, bump the region's
 *       committed version, and hand both to the {@link ExternalDeltaSink} (broadcast → replicas
 *       advance without voting);</li>
 *   <li>pipeline busy (a batch is between dispatch and decision) → hold the buffer; the coordinator
 *       calls {@link #onPipelineDecision(RegionId)} after the decision, BEFORE the next batch is
 *       assembled. The ordering rule — batch N+1's base version always includes prior
 *       interference — makes a {@code STALE_BASE} rejection from interference impossible by
 *       construction.</li>
 * </ul>
 *
 * @Thread-context server main thread only (same single-writer discipline as the applier).
 */
public final class InterferenceCommitter {

    /** Purely computes the live root as at least the requested snapshot body version. */
    @FunctionalInterface
    public interface RootExtractor {
        StateRoot extract(
                RegionId region, SnapshotVersion version, int minimumSnapshotBodyVersion);
    }

    /** Commits canonical encoding metadata only after the external delta sink succeeds. */
    @FunctionalInterface
    public interface SnapshotEncodingCommitter {
        void commit(RegionId region, int bodyVersion);
    }

    /** Receives each certified external delta for broadcast + local replica bookkeeping. */
    @FunctionalInterface
    public interface ExternalDeltaSink {
        void accept(RegionDelta delta, ServerAuthorityCertificate certificate);
    }

    private final InterferenceBuffer buffer;
    private final RootExtractor roots;
    private final SnapshotEncodingCommitter snapshotEncoding;
    private final ExternalDeltaSink sink;
    private final NodeIdentity server;
    private final Map<RegionId, SnapshotVersion> committedVersion = new HashMap<>();
    private final Set<RegionId> held = new HashSet<>();

    public InterferenceCommitter(
            InterferenceBuffer buffer, RootExtractor roots,
            SnapshotEncodingCommitter snapshotEncoding,
            ExternalDeltaSink sink, NodeIdentity server) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (roots == null) {
            throw new IllegalArgumentException("roots must not be null");
        }
        if (snapshotEncoding == null) {
            throw new IllegalArgumentException("snapshotEncoding must not be null");
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        if (server == null) {
            throw new IllegalArgumentException("server must not be null");
        }
        this.buffer = buffer;
        this.roots = roots;
        this.snapshotEncoding = snapshotEncoding;
        this.sink = sink;
        this.server = server;
    }

    /**
     * The coordinator reports every committed version advance (delegation snapshot, batch commit)
     * so interference deltas base on the true version chain.
     */
    public void onCommittedVersion(RegionId region, SnapshotVersion version) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        committedVersion.put(region, version);
    }

    /**
     * Server tick end: commit every pending region whose pipeline is not mid-batch; hold the rest.
     *
     * @param pipelineStates the live pipeline state per region.
     * @return the certified deltas emitted this tick (also delivered to the sink).
     */
    public List<RegionDelta> onTickEnd(Function<RegionId, PipelineState> pipelineStates) {
        List<RegionDelta> emitted = new ArrayList<>();
        for (RegionId region : buffer.pendingRegions()) {
            if (busy(pipelineStates.apply(region))) {
                held.add(region);
                continue;
            }
            commitRegion(region).ifPresent(emitted::add);
        }
        return emitted;
    }

    /**
     * Called after a pipeline decision (commit/reject/timeout) for {@code region}, before the next
     * batch is assembled. Flushes a buffer held by {@link #onTickEnd}.
     */
    public Optional<RegionDelta> onPipelineDecision(RegionId region) {
        if (!held.remove(region)) {
            return Optional.empty();
        }
        return commitRegion(region);
    }

    /** True while a batch is between dispatch and decision (interference must not race it). */
    private static boolean busy(PipelineState state) {
        return state == PipelineState.AWAITING_PROPOSAL
                || state == PipelineState.AWAITING_VERIFICATION
                || state == PipelineState.COMMIT
                || state == PipelineState.PAUSED_FOR_XR;
    }

    private Optional<RegionDelta> commitRegion(RegionId region) {
        List<RecordedMutation> recorded = buffer.drain(region);
        List<EntityMutation> entityMutations = buffer.drainEntities(region);
        if (recorded.isEmpty() && entityMutations.isEmpty()) {
            return Optional.empty(); // everything coalesced back to the committed state
        }
        try {
            SnapshotVersion base = committedVersion.get(region);
            if (base == null) {
                throw new IllegalStateException(
                        "no committed version tracked for " + region + " — onCommittedVersion missing");
            }
            SnapshotVersion next = base.next();
            StateRoot root = roots.extract(
                    region, next, dev.nodera.core.state.RegionSnapshot.STATE_ENCODING_VERSION);
            List<BlockMutation> mutations = new ArrayList<>(recorded.size());
            for (RecordedMutation m : recorded) {
                mutations.add(new BlockMutation(m.pos(), m.prevStateId(), m.newStateId(), 0));
            }
            RegionDelta delta = new RegionDelta(
                    region, base, next, mutations, root, entityMutations, List.of(), List.of(), 2);
            StateRoot transitionRoot = StateRoot.of(new dev.nodera.core.crypto.HashService().hash(delta));
            ServerAuthorityCertificate unsigned = new ServerAuthorityCertificate(
                    region, base, next, root, transitionRoot,
                    ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION, Bytes.empty());
            ServerAuthorityCertificate certificate = new ServerAuthorityCertificate(
                    region, base, next, root, transitionRoot,
                    ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION,
                    server.sign(unsigned.signedPortion()));
            sink.accept(delta, certificate);
            snapshotEncoding.commit(
                    region, dev.nodera.core.state.RegionSnapshot.STATE_ENCODING_VERSION);
            committedVersion.put(region, next);
            return Optional.of(delta);
        } catch (RuntimeException failure) {
            buffer.restore(region, recorded);
            buffer.restoreEntities(region, entityMutations);
            throw failure;
        }
    }

    /** The committed version the committer currently tracks for {@code region} (test/diagnostic). */
    public Optional<SnapshotVersion> trackedVersion(RegionId region) {
        return Optional.ofNullable(committedVersion.get(region));
    }
}
