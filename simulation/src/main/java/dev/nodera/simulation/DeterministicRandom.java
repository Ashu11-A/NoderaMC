package dev.nodera.simulation;

import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The ONE source of randomness the engine is allowed to touch (Task 3). Wraps the JDK-named
 * {@code "L64X128MixRandom"} algorithm so that, given an identical seed, every JVM, every OS, every
 * run draws an identical stream — the LXM family is a JDK-specified algorithm with a documented
 * state-transition function, not an implementation detail.
 *
 * <p><b>One instance per action.</b> The engine constructs a fresh {@code DeterministicRandom} for
 * each {@link dev.nodera.core.action.ActionEnvelope}, seeded from
 * {@link #seedFor(RegionExecutionContext, long, long)}. Reseeding per action (instead of drawing
 * one stream across the whole batch) makes action-level replay possible and kills cross-action
 * RNG-order sensitivity: an action's draws depend only on its own identity, not on how many draws
 * earlier actions happened to make. The {@code actionSeq} input is the action's server sequence
 * number, which is monotonic and stable across replicas.
 *
 * <p><b>Seed derivation.</b> Replaces the design draft's single {@code deterministicSeed} field.
 * Every replica computes the same seed from the raw, hash-anchored sources via
 * {@link StableHash} — there is no place for a mis-mixed seed to enter the system:
 * <pre>
 *   dimNs   = StableHash.of(ctx.region().dimension().namespace())
 *   dimPath = StableHash.of(ctx.region().dimension().path())
 *   seed    = StableHash.of(worldSeed, dimNs, dimPath,
 *                           regionX, regionZ, tickFrom, actionSeq)
 * </pre>
 *
 * <p>The factory is held in a {@code private static final} field: it is an immutable JVM-provided
 * constant (no mutable shared state, no clock) and caching it avoids a factory lookup per action.
 *
 * @Thread-context thread-confined per call. Each instance is used by exactly one action replay on
 *                 one thread; the wrapped {@link RandomGenerator} is not thread-safe and is not
 *                 shared.
 */
public final class DeterministicRandom {

    /** The named LXM algorithm; specified and stable across compliant JDKs. */
    private static final RandomGeneratorFactory<RandomGenerator> FACTORY =
            RandomGeneratorFactory.of("L64X128MixRandom");

    private final RandomGenerator rng;

    /**
     * Create a generator seeded from {@code seed}. Identical seeds produce identical streams.
     *
     * @param seed the per-action seed (see {@link #seedFor}).
     * @Thread-context thread-confined; the resulting generator must not escape the constructing thread.
     */
    public DeterministicRandom(long seed) {
        this.rng = FACTORY.create(seed);
    }

    /**
     * Derive the deterministic per-action seed from the execution context, the world seed, and the
     * action sequence number.
     *
     * <p>Formula (canonical — every replica, every port computes this identically):
     * <pre>
     *   StableHash.of(worldSeed,
     *                 StableHash.of(ctx.region().dimension().namespace()),
     *                 StableHash.of(ctx.region().dimension().path()),
     *                 ctx.region().regionX(),
     *                 ctx.region().regionZ(),
     *                 ctx.tickFrom(),
     *                 actionSeq)
     * </pre>
     *
     * @param ctx        the execution context (supplies region + tick range).
     * @param worldSeed  the world seed; MUST equal {@code ctx.worldSeed()} — passed explicitly to
     *                   make the seed's inputs self-documenting at the call site.
     * @param actionSeq  the action's server sequence number (stable, monotonic across replicas).
     * @return the 64-bit seed for the action's {@code DeterministicRandom}.
     * @Thread-context pure function; safe from any thread.
     */
    public static long seedFor(RegionExecutionContext ctx, long worldSeed, long actionSeq) {
        RegionId region = ctx.region();
        DimensionKey dim = region.dimension();
        long dimNs = StableHash.of(dim.namespace());
        long dimPath = StableHash.of(dim.path());
        return StableHash.of(
                worldSeed,
                dimNs,
                dimPath,
                region.regionX(),
                region.regionZ(),
                ctx.tickFrom(),
                actionSeq);
    }

    /**
     * @param bound exclusive upper bound; must be &gt; 0.
     * @return a uniformly distributed value in {@code [0, bound)}.
     * @Thread-context thread-confined.
     */
    public int nextInt(int bound) {
        return rng.nextInt(bound);
    }

    /**
     * @return the next uniformly distributed {@code long} value.
     * @Thread-context thread-confined.
     */
    public long nextLong() {
        return rng.nextLong();
    }
}
