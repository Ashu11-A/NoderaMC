package dev.nodera.coordinator;

import dev.nodera.consensus.VerificationOutcome;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

/**
 * Re-executes a client's proposed batch on the server and decides whether to commit (Task 6,
 * Phase 2). In the real deployment this runs on a dedicated executor (never the main thread) with a
 * timeout; here it is a synchronous call so tests stay deterministic. Epoch staleness is decided by
 * the caller against the {@link LeaseManager} before scheduling — the verifier only compares roots.
 *
 * @Thread-context stateless; safe from any thread (the engine is a pure function).
 */
public final class ServerVerifier {

    private final RegionEngine engine;

    public ServerVerifier(RegionEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
    }

    /**
     * Verify a proposal by re-executing its batch and comparing roots.
     *
     * @param request      the same execution inputs the primary used (server-side snapshot + batch).
     * @param proposedRoot the root the primary claimed.
     * @return the reference recompute plus {@link VerificationOutcome#MATCH}/{@link
     *         VerificationOutcome#MISMATCH}.
     */
    public Verification verify(RegionExecutionRequest request, StateRoot proposedRoot) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (proposedRoot == null) {
            throw new IllegalArgumentException("proposedRoot must not be null");
        }
        RegionExecutionResult reference = engine.execute(request);
        VerificationOutcome outcome = reference.resultingRoot().equals(proposedRoot)
                ? VerificationOutcome.MATCH
                : VerificationOutcome.MISMATCH;
        return new Verification(outcome, reference.resultingRoot(), reference.delta());
    }

    /**
     * Short-circuit outcome for a proposal whose epoch is older than the region's current epoch — the
     * caller checks this against the {@link LeaseManager} and never schedules the recompute.
     *
     * @param proposalEpoch the epoch the proposal carried.
     * @param currentEpoch  the region's current lease epoch.
     * @return {@code true} if the proposal is stale and must be dropped.
     */
    public static boolean isStale(RegionEpoch proposalEpoch, RegionEpoch currentEpoch) {
        return proposalEpoch.value() < currentEpoch.value();
    }

    /**
     * The verifier's decision.
     *
     * @param outcome       MATCH or MISMATCH.
     * @param referenceRoot the server's recomputed root.
     * @param referenceDelta the server's recomputed delta (used to resync on mismatch).
     */
    public record Verification(VerificationOutcome outcome, StateRoot referenceRoot, RegionDelta referenceDelta) {
        /** @return {@code true} on {@link VerificationOutcome#MATCH}. */
        public boolean matched() {
            return outcome == VerificationOutcome.MATCH;
        }
    }
}
