package dev.nodera.endpoint.paper.compat;

import dev.nodera.coordinator.interference.MutationGuard;
import dev.nodera.coordinator.interference.MutationSource;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.simulation.rules.VanillaPalette;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PC-3 on the plugin path: a plugin's write into a delegated region becomes a <b>certified</b>
 * external delta rather than being suppressed or silently reverted (server task 8 deliverable 4,
 * L-65).
 *
 * <blockquote><b>Plugins keep authority over the world; Nodera certifies what they did.</b>
 * </blockquote>
 *
 * <p>The headless half of that sentence has been green since engine task 11 — {@link MutationGuard}
 * classifies the write and {@code InterferenceCommitter} folds it into the version chain as a signed
 * {@code EXTERNAL_MUTATION} certificate. What did not exist was anything that fed it from a real
 * server: no Bukkit event, no WorldEdit operation, no plugin of any kind had ever reached the
 * guard. This class is that feed, and it is deliberately <b>Minecraft-free</b> so the whole
 * decision is unit-testable on the ordinary gate. The Paper and WorldEdit types live in the two
 * thin adapters beside it ({@link BukkitForeignWrites}, {@link WorldEditBulkWrites}).
 *
 * <h2>Two questions, at two priorities — this is PC-2, not a style choice</h2>
 *
 * <p>The contract splits listeners into <i>gates</i> ({@code LOWEST}/{@code HIGH}: may cancel, must
 * say why) and <i>observers</i> ({@code MONITOR}: must not cancel, must not mutate). So the bridge
 * has exactly two entry points and each belongs to one of them:
 *
 * <ul>
 *   <li>{@link #allows} is the gate. It runs <b>before</b> the write lands, after every protection
 *       plugin has had its say, and refuses only a write that raced a delta the committee has
 *       already committed (PC-3 consequence 2) or that {@code STRICT} mode forbids. A refusal
 *       always fires {@link Denials#deny} — <b>never silently</b>. A WorldEdit operation that
 *       vanishes with no explanation is the single worst outcome available here, and deliverable 3
 *       exists to prevent it.</li>
 *   <li>{@link #record} is the observer. It runs after the write has landed and is what actually
 *       certifies: the guard records the mutation, and the region's buffer is folded into the
 *       certified chain by {@link Certification}.</li>
 * </ul>
 *
 * <p><b>Why refuse-before rather than revert-after.</b> Undoing a landed write from a
 * {@code MONITOR} listener would break PC-2's own rule (a monitor that mutates), and it would make
 * every logging plugin record a change that Nodera then silently un-recorded — CoreProtect would
 * hold a history the world does not have. Refusing at {@code HIGH} means the loggers at
 * {@code MONITOR} see exactly what happened, which is the whole point of running them there.
 *
 * <h2>A large write is a large delta, not a stall</h2>
 *
 * <p>A {@code //set} over a million blocks arrives here a million times. The interference buffer
 * coalesces by position, and the bridge folds a region once {@code flushThreshold} writes are
 * pending, so one bulk operation becomes a stream of bounded deltas instead of one delta the size
 * of the operation. That is PC-3 consequence 1 made operational rather than aspirational.
 *
 * <h2>What this cannot see</h2>
 *
 * <p>Stated here because a compatibility bridge that quietly covers half its subject is worse than
 * one that covers none:
 *
 * <ol>
 *   <li><b>A direct {@code World#setBlockData} / {@code Block#setType} call fires no Bukkit event
 *       at all.</b> A plugin that writes that way is invisible to {@link BukkitForeignWrites}, and
 *       nothing short of an NMS hook could see it — which the compatibility contract forbids from a
 *       plugin (LIMITATIONS.md §C). This is a real hole in PC-3's coverage and it is why the live
 *       corpus stage, not this class, is L-65's exit.</li>
 *   <li><b>WorldEdit and FAWE bypass Bukkit events on their bulk path.</b> Covered by
 *       {@link WorldEditBulkWrites}, which hooks WorldEdit's own extent pipeline instead; read that
 *       class for what it does and does not reach.</li>
 * </ol>
 *
 * @Thread-context one instance per endpoint. Every method must be called from the thread that owns
 *                 the region being written — the main thread on Paper, that region's thread on
 *                 Folia. The guard's single-writer discipline is what this inherits; there is no
 *                 lock here, and adding one would hide a thread-context violation that Folia needs
 *                 to make loud.
 */
public final class ForeignWriteBridge {

    /** What the bridge did with one landed foreign write. */
    public enum Outcome {
        /** Not delegated, or a legal applier write: the vanilla lane is untouched. */
        PASS,
        /** Recorded for certification as an {@code ExternalDelta}. */
        CERTIFIED,
        /** Outside the consensus palette: nothing to certify. */
        UNSUPPORTED
    }

    /** Why a write was refused. Carried to every plugin on the server, never only to our log. */
    public enum DenialReason {
        /**
         * The plugin was writing over a state the committee had already replaced. The committed
         * delta wins and the write does not land (PC-3 consequence 2).
         */
        RACED_CERTIFIED_DELTA,
        /**
         * The guard is in {@code STRICT} mode — CI determinism runs and debugging only. An
         * operator never sees this on a normal endpoint.
         */
        INTERFERENCE_STRICT_MODE
    }

    /**
     * One refused write, in Minecraft-free terms.
     *
     * @param region    the region whose certified state won.
     * @param pos       the block position.
     * @param reason    why.
     * @param certified the state the position holds and keeps.
     */
    public record Denial(RegionId region, NBlockPos pos, DenialReason reason,
                         VanillaPalette.VanillaBlock certified) {

        public Denial {
            if (region == null || pos == null || reason == null || certified == null) {
                throw new IllegalArgumentException("a denial must name region, pos, reason, state");
            }
        }
    }

    /** Publishes a denial to the rest of the server. */
    @FunctionalInterface
    public interface Denials {
        void deny(Denial denial);
    }

    /**
     * What the committee has certified for a position — and, by the same answer, whether this
     * endpoint validates the region at all. A region with no certified head is a region this
     * endpoint is not validating, and both questions have the same answer.
     */
    @FunctionalInterface
    public interface CertifiedHead {

        /** Nothing is certified here, so nothing can race. */
        int UNKNOWN = Integer.MIN_VALUE;

        /**
         * @return the certified palette id at {@code pos}, or {@link #UNKNOWN} when this endpoint
         *         holds no certified state for it. Unknown is a complete answer, not a failure.
         */
        int stateAt(RegionId region, NBlockPos pos);
    }

    /**
     * Folds a region's buffered foreign writes into the certified version chain.
     *
     * <p>In its finished form this is {@code InterferenceCommitter::flushNow}. On this tree the
     * plugin cannot install that, and the reason is worth stating rather than discovering: an
     * {@code EXTERNAL_MUTATION} certificate is <b>signed by the node</b>, and under
     * {@code peer.mode: external} the node — and its key — is a separate process. The endpoint can
     * classify, refuse, announce and record today; it cannot sign. Closing that is server task 2
     * (an in-JVM node) or a control-socket verb that hands the recorded mutations to the worker to
     * certify there.
     */
    @FunctionalInterface
    public interface Certification {

        /**
         * @return the certified delta, or empty when nothing was pending — or when this endpoint
         *         cannot certify at all.
         */
        Optional<RegionDelta> flush(RegionId region);
    }

    private final MutationGuard guard;
    private final Certification certification;
    private final CertifiedHead head;
    private final Denials denials;
    private final int flushThreshold;

    /** Regions with writes recorded but not yet folded into the chain. */
    private final Set<RegionId> pending = new LinkedHashSet<>();
    private int pendingWrites;

    private long passed;
    private long certified;
    private long refused;
    private long unsupported;
    private long uncertifiable;

    /**
     * @param guard          the engine's single write choke point, already configured with this
     *                       endpoint's delegation view and mode.
     * @param certification  where a recorded write goes to become an {@code ExternalDelta}.
     * @param head           the certified state, and therefore the race detector.
     * @param denials        how the rest of the server is told about a refusal.
     * @param flushThreshold pending writes before a region is folded; keeps a bulk operation from
     *                       becoming one unbounded delta. Must be positive.
     */
    public ForeignWriteBridge(MutationGuard guard, Certification certification, CertifiedHead head,
                              Denials denials, int flushThreshold) {
        if (guard == null) {
            throw new IllegalArgumentException("guard must not be null");
        }
        if (certification == null) {
            throw new IllegalArgumentException("certification must not be null");
        }
        if (head == null) {
            throw new IllegalArgumentException("head must not be null");
        }
        if (denials == null) {
            throw new IllegalArgumentException("denials must not be null");
        }
        if (flushThreshold <= 0) {
            throw new IllegalArgumentException("flushThreshold must be positive");
        }
        this.guard = guard;
        this.certification = certification;
        this.head = head;
        this.denials = denials;
        this.flushThreshold = flushThreshold;
    }

    /**
     * The gate. May this write land?
     *
     * <p>Called before the write happens, at a priority that has already let every protection
     * plugin decide. A {@code false} answer has <b>already</b> been announced through
     * {@link Denials}; the caller's only job is to stop the write.
     *
     * @param region the Nodera region the position falls in.
     * @param pos    the block position.
     * @param before the state the world holds right now.
     * @param after  the state the write wants to leave behind.
     * @return whether the write may proceed. A block outside the consensus palette always may:
     *         Nodera never claimed to model it, so it has no certified state to lose to.
     */
    public boolean allows(RegionId region, NBlockPos pos, VanillaPalette.VanillaBlock before,
                          VanillaPalette.VanillaBlock after) {
        requireWrite(region, pos, before, after);
        int previousId = VanillaPalette.idFor(before.key(), before.properties());
        int newId = VanillaPalette.idFor(after.key(), after.properties());
        if (previousId == VanillaPalette.UNSUPPORTED || newId == VanillaPalette.UNSUPPORTED) {
            return true;
        }

        int certifiedId = head.stateAt(region, pos);
        if (certifiedId == CertifiedHead.UNKNOWN) {
            return true; // not delegated: not ours to gate
        }
        if (certifiedId != previousId) {
            // The plugin is writing over a state the committee has already replaced: it was working
            // from a world one delta behind. PC-3 consequence 2 — the committed delta wins.
            refused++;
            denials.deny(new Denial(region, pos, DenialReason.RACED_CERTIFIED_DELTA,
                    VanillaPalette.vanillaOf(certifiedId)));
            return false;
        }
        if (guard.mode() == MutationGuard.Mode.STRICT) {
            refused++;
            denials.deny(new Denial(region, pos, DenialReason.INTERFERENCE_STRICT_MODE, before));
            return false;
        }
        return true;
    }

    /**
     * The observer. Record one foreign write that has <b>already landed</b>.
     *
     * <p>{@code before} is what the block held; the block now holds {@code after}. That ordering is
     * the contract, and it is why the recorded {@code prevStateId} is a CAS guard the replicas can
     * check rather than something re-read from a world that has already moved.
     *
     * @param source the vanilla phase, where the caller knows it; {@link MutationSource#UNKNOWN} is
     *               correct for a plugin, which is not a vanilla phase at all.
     * @return what the bridge did.
     */
    public Outcome record(RegionId region, NBlockPos pos, VanillaPalette.VanillaBlock before,
                          VanillaPalette.VanillaBlock after, MutationSource source) {
        requireWrite(region, pos, before, after);
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        int previousId = VanillaPalette.idFor(before.key(), before.properties());
        int newId = VanillaPalette.idFor(after.key(), after.properties());
        if (previousId == VanillaPalette.UNSUPPORTED || newId == VanillaPalette.UNSUPPORTED) {
            // Outside the palette is outside consensus. Certifying it would put a state id in a
            // delta no replica can render.
            unsupported++;
            return Outcome.UNSUPPORTED;
        }

        MutationGuard.Verdict verdict = guard.verdict(region, pos, previousId, newId);
        if (verdict != MutationGuard.Verdict.CONVERT) {
            // PASS: not delegated, or the applier's own write. BLOCK cannot appear here — a STRICT
            // endpoint refused this write at the gate, so it never landed and is never recorded.
            passed++;
            return Outcome.PASS;
        }
        certified++;
        pending.add(region);
        pendingWrites++;
        if (pendingWrites >= flushThreshold) {
            flushAll();
        }
        return Outcome.CERTIFIED;
    }

    /**
     * Fold every pending region into the certified chain.
     *
     * <p>Called when a bulk operation crosses the flush threshold, and again when the endpoint
     * disables — a buffer that outlives the server is a foreign write nobody ever certified.
     *
     * @return the deltas this flush certified, in the order the regions were first written.
     */
    public List<RegionDelta> flushAll() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<RegionId> regions = new ArrayList<>(pending);
        pending.clear();
        pendingWrites = 0;
        List<RegionDelta> deltas = new ArrayList<>(regions.size());
        for (RegionId region : regions) {
            Optional<RegionDelta> delta = certification.flush(region);
            if (delta.isPresent()) {
                deltas.add(delta.get());
            } else {
                uncertifiable++;
            }
        }
        return deltas;
    }

    /**
     * One line an operator can read, and the only thing the plugin logs on a quiet server.
     *
     * <p>{@code not certifiable} is the number that matters on this tree: regions whose foreign
     * writes were recorded and then could not be signed, which is the honest shape of the note on
     * {@link Certification}.
     */
    public String summary() {
        return "foreign writes: " + passed + " passed · " + certified + " certified · "
                + refused + " refused · " + unsupported + " outside the palette · "
                + uncertifiable + " recorded but not certifiable";
    }

    private static void requireWrite(RegionId region, NBlockPos pos,
                                     VanillaPalette.VanillaBlock before,
                                     VanillaPalette.VanillaBlock after) {
        if (region == null || pos == null || before == null || after == null) {
            throw new IllegalArgumentException(
                    "a foreign write must name region, pos, before and after");
        }
    }
}
