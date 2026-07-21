package dev.nodera.coordinator;

import dev.nodera.consensus.ProposalKey;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds in-flight {@link ClientProposal}s until their verification resolves (Task 6). One proposal
 * per {@link ProposalKey}; a duplicate submission for the same key replaces the prior (idempotent
 * re-send). {@link #take(ProposalKey)} removes and returns a proposal when the verifier is ready to
 * pair it with a result.
 *
 * @Thread-context confined to the coordinator thread; not thread-safe.
 */
public final class ProposalManager {

    private final Map<ProposalKey, ClientProposal> pending = new HashMap<>();

    /** Record a proposal (replacing any prior for the same key). */
    public void submit(ClientProposal proposal) {
        if (proposal == null) {
            throw new IllegalArgumentException("proposal must not be null");
        }
        pending.put(proposal.key(), proposal);
    }

    /** @return {@code true} if a proposal is pending for {@code key}. */
    public boolean has(ProposalKey key) {
        return pending.containsKey(key);
    }

    /** @return and remove the pending proposal for {@code key}, or {@code null} if none. */
    public ClientProposal take(ProposalKey key) {
        return pending.remove(key);
    }

    /** Drop any pending proposal for {@code key} (e.g. after a lease revoke). */
    public void discard(ProposalKey key) {
        pending.remove(key);
    }

    /** @return the number of pending proposals. */
    public int size() {
        return pending.size();
    }
}
