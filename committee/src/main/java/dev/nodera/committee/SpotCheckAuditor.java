package dev.nodera.committee;

import dev.nodera.consensus.SpotCheckPolicy;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;

/**
 * The server's spot-check safety net (Task 7, ledger L-22). Once a committee is trusted the server
 * stops re-executing every batch and instead audits a deterministic sample — one in
 * {@link SpotCheckPolicy#divisorFor(double) N} batches, where {@code N} widens with committee
 * reliability. The sample is chosen by {@link SpotCheckPolicy#shouldCheck} so every honest replica
 * agrees which batches were audited and a byzantine committee cannot predict them (the
 * {@code serverSecret} is private). When a sampled batch is re-executed and the server's root
 * disagrees with the committed root, the audit disputes — the last line of defence against a
 * fully-colluding committee.
 *
 * @Thread-context stateless apart from the server secret; safe from any thread.
 */
public final class SpotCheckAuditor {

    private final RegionEngine engine;
    private final long serverSecret;

    public SpotCheckAuditor(RegionEngine engine, long serverSecret) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
        this.serverSecret = serverSecret;
    }

    /** @return {@code true} if this batch falls in the deterministic audit sample. */
    public boolean shouldAudit(RegionId region, long version, double committeeReliability) {
        return SpotCheckPolicy.shouldCheck(region, version, serverSecret, committeeReliability);
    }

    /**
     * Re-execute a committed batch and compare the server's root against the committed one.
     *
     * @param request       the same inputs the committee used.
     * @param committedRoot the root the committee committed.
     * @return the audit result; {@link AuditResult#disputed()} is {@code true} on disagreement.
     */
    public AuditResult audit(RegionExecutionRequest request, StateRoot committedRoot) {
        StateRoot serverRoot = engine.execute(request).resultingRoot();
        return new AuditResult(serverRoot.equals(committedRoot), serverRoot, committedRoot);
    }

    /**
     * The outcome of one audit.
     *
     * @param agrees        {@code true} if the server root matched the committed root.
     * @param serverRoot    the server's recomputed root.
     * @param committedRoot the root the committee committed.
     */
    public record AuditResult(boolean agrees, StateRoot serverRoot, StateRoot committedRoot) {
        /** @return {@code true} if the audit found a disagreement (dispute the commit). */
        public boolean disputed() {
            return !agrees;
        }
    }
}
