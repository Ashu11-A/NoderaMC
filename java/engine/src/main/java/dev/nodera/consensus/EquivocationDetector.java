package dev.nodera.consensus;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Safety guard against a validator double-voting: flags any voter that signs two different
 * state or transition roots for the same {@link ProposalKey} (Plan §6 Phase 3 / Invariant machinery).
 *
 * <p>Every root claim the consensus layer sees — primary proposals and validator votes alike — is
 * passed to {@link #observe(ProposalKey, SignedVote)}. The detector keeps, per voter, a
 * {@code ProposalKey → last StateRoot} map. When a voter submits a root for a key it has already
 * voted on <em>with a different root</em>, the voter is marked equivoked and an
 * {@link EquivocationRecord} is captured for {@code ReliabilityLedger.slash} + committee removal.
 *
 * <p>Memory is bounded by a {@link Caffeine} cache keyed by voter (default maximum 4096 voters);
 * when a voter is evicted its per-key history is dropped. Voting the <em>same</em> root twice for
 * one key is idempotent and never flagged; voting different keys is fine. Only
 * {@code (voter, key)} pairs with a root conflict count.
 *
 * <p>Thread-context: thread-safe. The Caffeine cache is concurrent; per-voter histories are
 * {@link ConcurrentMap}s updated with atomic {@link ConcurrentMap#putIfAbsent}; the equivoked set
 * and record map are {@link ConcurrentHashMap}s. Safe to call from any thread.
 */
public final class EquivocationDetector {

    /** Default Caffeine maximum number of distinct voters tracked. */
    public static final long DEFAULT_MAX_VOTERS = 4096L;

    private final long maxSize;
    private final Cache<NodeId, ConcurrentMap<ProposalKey, VoteClaim>> history;
    private final java.util.Set<NodeId> equivoked = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<NodeId, EquivocationRecord> records = new ConcurrentHashMap<>();

    /** Construct with the default bound ({@value #DEFAULT_MAX_VOTERS} voters). */
    public EquivocationDetector() {
        this(DEFAULT_MAX_VOTERS);
    }

    /**
     * @param maxSize maximum number of distinct voters whose root-history is retained.
     * @throws IllegalArgumentException if {@code maxSize <= 0}.
     */
    public EquivocationDetector(long maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0: " + maxSize);
        }
        this.maxSize = maxSize;
        this.history = Caffeine.newBuilder().maximumSize(maxSize).build();
    }

    /**
     * Observe one root claim. If {@code vote}'s {@code resultingRoot} differs from the root this
     * voter previously claimed for {@code key}, the voter is marked equivoked and a record is
     * captured (overwriting any prior record for the voter). Same-root re-observation is a no-op.
     *
     * @param key  the proposal the vote pertains to; not null.
     * @param vote the vote carrying the voter and claimed root; not null.
     */
    public void observe(ProposalKey key, SignedVote vote) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (vote == null) {
            throw new IllegalArgumentException("vote must not be null");
        }
        NodeId voter = vote.voter();
        VoteClaim claim = new VoteClaim(vote.resultingRoot(), vote.transitionRoot());
        ConcurrentMap<ProposalKey, VoteClaim> perVoter =
                history.get(voter, k -> new ConcurrentHashMap<>());
        VoteClaim previous = perVoter.putIfAbsent(key, claim);
        if (previous != null && !previous.equals(claim)) {
            equivoked.add(voter);
            StateRoot firstEvidence = previous.resultingRoot().equals(claim.resultingRoot())
                    ? previous.transitionRoot() : previous.resultingRoot();
            StateRoot secondEvidence = previous.resultingRoot().equals(claim.resultingRoot())
                    ? claim.transitionRoot() : claim.resultingRoot();
            records.put(voter, new EquivocationRecord(
                    voter, key, firstEvidence, secondEvidence, System.currentTimeMillis()));
        }
    }

    /**
     * @param voter the voter to test; not null.
     * @return true if this voter has been seen equivocating at least once.
     */
    public boolean hasEquivoked(NodeId voter) {
        if (voter == null) {
            throw new IllegalArgumentException("voter must not be null");
        }
        return equivoked.contains(voter);
    }

    /**
     * @param voter the voter to look up; not null.
     * @return the most recent {@link EquivocationRecord} for {@code voter}, or empty if the voter
     *         has not been flagged. Note: if the voter was evicted from the bounded cache before
     *         being flagged, no record exists; once flagged the record is retained independent of
     *         cache eviction.
     */
    public Optional<EquivocationRecord> record(NodeId voter) {
        if (voter == null) {
            throw new IllegalArgumentException("voter must not be null");
        }
        return Optional.ofNullable(records.get(voter));
    }

    /** The configured maximum number of voters whose root-history is retained. */
    public long maxSize() {
        return maxSize;
    }

    private record VoteClaim(StateRoot resultingRoot, StateRoot transitionRoot) {
    }
}
