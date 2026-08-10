package dev.nodera.testkit.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.testkit.engine.EngineFixtures;

/**
 * The <b>signed</b> action values every committee test starts from.
 *
 * <p>Everything unsigned this class used to hold — the world seed, the vertical extent,
 * {@code uniformColumn} and {@code fullUniformSnapshot} — was a second copy of
 * {@link EngineFixtures}' in the same source set, one package away. The two with a caller here are
 * kept as delegations; the vertical extent and {@code uniformColumn} had none once the committee
 * suites moved onto this fixture, so they are gone rather than left as an alias of
 * {@code EngineFixtures} that reads as this module's own. What is left is the half that is genuinely
 * this module's: an envelope carrying a real Ed25519 signature, which an engine fixture must not
 * build because the engine never verifies signatures.
 *
 * <p>The world seed is a default, not a constant of the system — {@link PeerTestHarness} takes it
 * as a named parameter because at least one suite deliberately runs on a different one.
 *
 * <p>Thread-context: pure functions; any thread.
 */
public final class RegionFixtures {

    /** The seed nearly every committee test runs on. Overridable per node; never assumed. */
    public static final long WORLD_SEED = EngineFixtures.WORLD_SEED;

    private RegionFixtures() {}

    /**
     * A complete 8×8-column region at {@link SnapshotVersion#INITIAL} and tick 0, filled uniformly.
     *
     * <p>Complete matters: a committee re-executes what it is given, so a partial snapshot would
     * make every member agree on a world that has holes in it.
     *
     * @param region  the region to build.
     * @param stateId the block state every column holds.
     */
    public static RegionSnapshot fullUniformSnapshot(RegionId region, int stateId) {
        return EngineFixtures.fullUniformSnapshot(region, stateId);
    }

    /**
     * An action envelope carrying {@code actor}'s real Ed25519 signature over its signed portion.
     *
     * <p>The player and server sequence numbers are both {@code seq}; the target tick is separate
     * because the skew cases turn on the two disagreeing.
     *
     * @param actor      the identity that signs, and is named as the actor.
     * @param region     the region the action claims to belong to.
     * @param seq        both sequence numbers.
     * @param targetTick the tick the action was signed against.
     * @param action     the action itself.
     */
    public static ActionEnvelope signed(NodeIdentity actor, RegionId region, long seq,
                                        long targetTick, GameAction action) {
        ActionEnvelope unsigned = new ActionEnvelope(
                actor.nodeId(), seq, seq, targetTick, region, action, Bytes.empty());
        return new ActionEnvelope(actor.nodeId(), seq, seq, targetTick, region, action,
                actor.sign(unsigned.signedPortion()));
    }

    /**
     * A signed block placement — the action nearly every committee round is driven by.
     *
     * @param stateId the block state to place.
     */
    public static ActionEnvelope place(NodeIdentity actor, RegionId region, long seq,
                                       long targetTick, int x, int y, int z, int stateId) {
        return signed(actor, region, seq, targetTick,
                new PlaceBlockAction(new NBlockPos(x, y, z), stateId, 1));
    }
}
