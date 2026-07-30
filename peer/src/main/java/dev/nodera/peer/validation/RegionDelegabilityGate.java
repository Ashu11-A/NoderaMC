package dev.nodera.peer.validation;

import dev.nodera.coordinator.DelegabilityMonitor;
import dev.nodera.coordinator.DelegabilityPolicy;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.simulationmsg.RegionRefusal;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Drives the full delegability rule set into real refusals (engine task 7 / #11, step 4).
 *
 * <p><b>The gap this closes.</b> `DelegabilityPolicy` enumerates the whole rule set and has been
 * unit-tested since Task 6, while the live lane refused a region for exactly one of its reasons —
 * a non-delegable entity. A region with an unsupported palette, or with chunks not loaded, was
 * never refused at all: it simply stayed unvalidated and silent, which is indistinguishable from
 * working. The policy was not legacy, as a first read of the dead-code sweep concluded; it was
 * unimplemented.
 *
 * <p><b>Why the hysteresis is not optional.</b> Delegability inputs flap — a mob paces across a
 * border, a chunk unloads and reloads, an interference measurement crosses its threshold and comes
 * back. Announcing a refusal on every dirty tick would thrash a region between lanes and fill every
 * peer's log with the same region, so {@link DelegabilityMonitor} does what it was written to do:
 * <b>revoke immediately</b> (correctness first — one dirty evaluation demotes), and
 * <b>restore only after a full cooldown of consecutively clean evaluations</b> (stability second).
 *
 * <p><b>What is announced, and what is not.</b> Only the verdicts a recipient can re-check for
 * itself, per {@link DelegabilityRefusals}. A revocation whose reasons are all owner-only is still
 * a revocation locally — this node stops validating — it is simply not announced, because a
 * refusal the recipient cannot verify is a claim rather than an observation.
 *
 * <p>Minecraft-free on purpose: the live world supplies {@link DelegabilityPolicy.Inputs} and this
 * decides, so the whole decision is testable without a game.
 *
 * @Thread-context confined to whichever thread evaluates regions (the server thread in the mod);
 *                 {@link DelegabilityMonitor} is not thread-safe.
 */
public final class RegionDelegabilityGate {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaDelegability");

    private final DelegabilityMonitor monitor;
    private final BiPredicate<RegionId, RegionRefusal.Reason> refuser;

    /**
     * @param monitor the hysteresis loop around the policy.
     * @param refuser how a refusal is announced; returns whether it was newly announced. Normally
     *                {@code ObserverRefusals::refuse} or the lane's own refusal path.
     */
    public RegionDelegabilityGate(DelegabilityMonitor monitor,
                                  BiPredicate<RegionId, RegionRefusal.Reason> refuser) {
        if (monitor == null || refuser == null) {
            throw new IllegalArgumentException("monitor and refuser must not be null");
        }
        this.monitor = monitor;
        this.refuser = refuser;
    }

    /** The outcome of one evaluation. */
    public record Outcome(DelegabilityMonitor.Transition transition,
                          Set<DelegabilityPolicy.Reason> reasons,
                          Optional<RegionRefusal.Reason> announced) {

        /** @return whether this evaluation took the region out of the validated lane. */
        public boolean revoked() {
            return transition == DelegabilityMonitor.Transition.REVOKE;
        }
    }

    /**
     * Evaluate one region and announce a refusal if this evaluation revoked it.
     *
     * <p>Only the {@code REVOKE} edge announces. {@code REVOKED} is the steady state of a region
     * that is already out, and re-announcing it every tick would be the log spam the refusal path
     * was built to avoid; {@code RESTORE} and {@code DELEGATED} are not refusals at all.
     *
     * @param region the region.
     * @param inputs the live world's answers to the rule set.
     * @param nowTick the server tick; must be monotonic per region.
     * @return what happened, including which refusal was announced, if any.
     */
    public Outcome evaluate(RegionId region, DelegabilityPolicy.Inputs inputs, long nowTick) {
        if (region == null || inputs == null) {
            throw new IllegalArgumentException("region and inputs must not be null");
        }
        DelegabilityMonitor.Decision decision = monitor.evaluate(region, inputs, nowTick);
        if (decision.transition() != DelegabilityMonitor.Transition.REVOKE) {
            return new Outcome(decision.transition(), decision.reasons(), Optional.empty());
        }
        Optional<RegionRefusal.Reason> announceable = firstAnnounceable(decision.reasons());
        if (announceable.isEmpty()) {
            // Revoked locally, announced to nobody: every blocking reason is one only an owner can
            // evaluate, so telling a peer would hand it a claim it could never re-check.
            LOG.info("{} revoked locally — {} (nothing announceable to the mesh)",
                    region, decision.reasons());
            return new Outcome(decision.transition(), decision.reasons(), Optional.empty());
        }
        RegionRefusal.Reason reason = announceable.get();
        refuser.test(region, reason);
        return new Outcome(decision.transition(), decision.reasons(), Optional.of(reason));
    }

    /**
     * @return the announceable refusal for this verdict, chosen in the rule set's own declaration
     *         order so two nodes seeing the same dirty region announce the same reason. A refusal
     *         carries one reason and a verdict may have several; picking by iteration order of a
     *         hash set would make the announced reason depend on nothing meaningful.
     */
    private static Optional<RegionRefusal.Reason> firstAnnounceable(
            Set<DelegabilityPolicy.Reason> reasons) {
        for (DelegabilityPolicy.Reason candidate : DelegabilityPolicy.Reason.values()) {
            if (reasons.contains(candidate)) {
                Optional<RegionRefusal.Reason> mapped = DelegabilityRefusals.announceable(candidate);
                if (mapped.isPresent()) {
                    return mapped;
                }
            }
        }
        return Optional.empty();
    }

    /** @return whether the region is currently in the validated lane, per the monitor. */
    public boolean isDelegated(RegionId region) {
        return monitor.isDelegated(region);
    }
}
