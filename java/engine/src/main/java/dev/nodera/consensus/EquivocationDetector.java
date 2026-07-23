package dev.nodera.consensus;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

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
 * <p>Memory is bounded by a {@link Caffeine} cache keyed by voter (default maximum 4096 voters).
 * Eviction is NOT an evasion route: when a voter's live history is evicted, its claims are folded
 * into a second, larger bounded cache keyed by {@code (voter, key)} pair, so a Byzantine voter
 * that spaces its two conflicting votes past eviction (or floods the voter cache) is still
 * convicted while the pair remains within the {@link #EVICTED_CLAIM_RETENTION_FACTOR}× retention
 * budget. Voting the <em>same</em> root twice for one key is idempotent and never flagged; voting
 * different keys is fine. Only {@code (voter, key)} pairs with a root conflict count.
 *
 * <p>The {@code detectedAtMillis} stamped into captured records comes from a caller-injected
 * {@link LongSupplier} clock (default {@link System#currentTimeMillis()}). The field remains
 * logging/audit-only and non-canonical; injecting the clock makes record construction
 * deterministic for replay and for the Task 9 committee-change lane.
 *
 * <p>Thread-context: thread-safe. The Caffeine caches are concurrent; per-voter histories are
 * {@link ConcurrentMap}s updated with atomic {@link ConcurrentMap#putIfAbsent}; the equivoked set
 * and record map are {@link ConcurrentHashMap}s. Safe to call from any thread.
 */
public final class EquivocationDetector {

    /** Default Caffeine maximum number of distinct voters tracked. */
    public static final long DEFAULT_MAX_VOTERS = 4096L;

    /**
     * Sizing multiplier for the evicted-claim cache relative to {@code maxSize}. Evicted claims
     * are one {@code (voter, key) → claim} entry each (no per-voter map), so a much larger count
     * fits in a comparable memory budget; the factor widens the conviction window well past the
     * live-history eviction horizon.
     */
    public static final long EVICTED_CLAIM_RETENTION_FACTOR = 16L;

    /**
     * Hard floor on the evicted-claim pair cache regardless of {@code maxSize}, so a small voter
     * budget cannot be leveraged into a small conviction window: an attacker must burn this many
     * distinct {@code (voter, key)} folds between its two conflicting votes to push its own first
     * vote out of retention. ~65k entries is a few MB worst case.
     */
    public static final long EVICTED_CLAIM_FLOOR = 65_536L;

    private final long maxSize;
    private final LongSupplier clock;
    private final Cache<NodeId, ConcurrentMap<ProposalKey, VoteClaim>> history;
    private final Cache<VoterProposal, VoteClaim> evictedClaims;
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
        this(maxSize, System::currentTimeMillis);
    }

    /**
     * @param maxSize maximum number of distinct voters whose root-history is retained.
     * @param clock   supplies the {@code detectedAtMillis} stamped into captured records
     *                (logging/audit-only, non-canonical). Inject a deterministic source for
     *                replay-stable records.
     * @throws IllegalArgumentException if {@code maxSize <= 0} or {@code clock} is null.
     */
    public EquivocationDetector(long maxSize, LongSupplier clock) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0: " + maxSize);
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.maxSize = maxSize;
        this.clock = clock;
        this.evictedClaims = Caffeine.newBuilder()
                .maximumSize(Math.max(maxSize * EVICTED_CLAIM_RETENTION_FACTOR, EVICTED_CLAIM_FLOOR))
                .build();
        this.history = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .evictionListener((NodeId voter, ConcurrentMap<ProposalKey, VoteClaim> perVoter,
                        com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (voter == null || perVoter == null) {
                        return;
                    }
                    // Fold the evicted live history into the flat pair cache so the first half of
                    // a spaced-out equivocation survives voter-cache pressure.
                    perVoter.forEach((key, claim) ->
                            evictedClaims.put(new VoterProposal(voter, key), claim));
                })
                .build();
    }

    /**
     * Observe one root claim. If {@code vote}'s {@code resultingRoot} differs from the root this
     * voter previously claimed for {@code key} — whether that previous claim is still in the live
     * history or was evicted into the retention cache — the voter is marked equivoked and a record
     * is captured once (the first captured record for a voter is retained; later conflicts never
     * overwrite it). Same-root re-observation is a
     * no-op.
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
        if (previous == null) {
            // No live previous claim — consult the eviction-survivor cache before trusting the
            // fresh map: a re-created empty history must not launder a conflicting second vote.
            previous = evictedClaims.getIfPresent(new VoterProposal(voter, key));
        }
        if (previous != null && !previous.equals(claim)) {
            equivoked.add(voter);
            StateRoot firstEvidence = previous.resultingRoot().equals(claim.resultingRoot())
                    ? previous.transitionRoot() : previous.resultingRoot();
            StateRoot secondEvidence = previous.resultingRoot().equals(claim.resultingRoot())
                    ? claim.transitionRoot() : claim.resultingRoot();
            // Capture-once: the first conflicting pair this replica convicts on is retained;
            // later conflicts (or concurrent observes on other keys) must not overwrite the
            // evidence a slash/removal certificate may already reference.
            records.putIfAbsent(voter, new EquivocationRecord(
                    voter, key, firstEvidence, secondEvidence, clock.getAsLong()));
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
     * @return the first-captured {@link EquivocationRecord} for {@code voter}, or empty if the
     *         voter has not been flagged. Once flagged, the record is retained independent of cache
     *         eviction. Pre-flag first votes survive live-history eviction inside the
     *         {@link #EVICTED_CLAIM_RETENTION_FACTOR}× pair-cache budget.
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

    /**
     * Force pending Caffeine size-eviction maintenance to run, firing the eviction listener.
     * Test hook: production callers never need this — eviction folding happens during normal
     * cache maintenance.
     */
    void flushEvictions() {
        history.cleanUp();
    }

    private record VoteClaim(StateRoot resultingRoot, StateRoot transitionRoot) {
    }

    private record VoterProposal(NodeId voter, ProposalKey key) {
    }
}
