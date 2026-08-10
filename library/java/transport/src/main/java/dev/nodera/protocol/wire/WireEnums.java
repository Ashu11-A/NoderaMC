package dev.nodera.protocol.wire;

import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.RegistrationEvent;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceLifecycle;
import dev.nodera.protocol.simulationmsg.RegionRefusal;

/**
 * The explicit wire codes for every enum that crosses the network (Task 14 phase 6, retiring
 * {@code Plan.7} R5).
 *
 * <p>Each table below replaces an {@code ordinal()} call. The numbers happen to start at 1 and run
 * in declaration order, because that is what the old ordinals encoded and the wire is frozen — but
 * they are now <b>written down</b>, which is the entire point. Reordering a Java enum for
 * readability, or inserting a constant in the middle, used to silently renumber every constant after
 * it in a contract that is hashed and signed. Now it changes nothing, and forgetting to assign a
 * code to a new constant fails at class initialisation.
 *
 * <p><b>Assigning a code is permanent.</b> Append; never renumber, never reuse.
 *
 * <p>Thread-context: immutable static tables; any thread.
 */
public final class WireEnums {

    private WireEnums() {}

    /** {@code PeerRole} — what a peer offers the network. Frozen. */
    public static final WireEnum<PeerRole> PEER_ROLE = WireEnum.builder(PeerRole.class)
            .code(1, PeerRole.BOOTSTRAP)
            .code(2, PeerRole.RELAY)
            .code(3, PeerRole.SESSION_GATEWAY)
            .code(4, PeerRole.REGION_EXECUTOR)
            .code(5, PeerRole.REGION_VALIDATOR)
            .code(6, PeerRole.PARTIAL_ARCHIVE)
            .code(7, PeerRole.FULL_ARCHIVE)
            .code(8, PeerRole.WORLD_SEEDER)
            .build();

    /** {@code WorldHealth} — a listed world's serving state. Frozen. */
    public static final WireEnum<WorldHealth> WORLD_HEALTH = WireEnum.builder(WorldHealth.class)
            .code(1, WorldHealth.HEALTHY)
            .code(2, WorldHealth.DEGRADED)
            .code(3, WorldHealth.DEAD)
            .build();

    /** {@code AnnounceEvent} — why a peer is talking to a tracker. */
    public static final WireEnum<AnnounceEvent> ANNOUNCE_EVENT = WireEnum.builder(AnnounceEvent.class)
            .code(1, AnnounceEvent.STARTED)
            .code(2, AnnounceEvent.HEARTBEAT)
            .code(3, AnnounceEvent.STOPPED)
            .build();

    /** {@code CandidateKind} — how a reachability candidate was obtained. */
    public static final WireEnum<CandidateKind> CANDIDATE_KIND = WireEnum.builder(CandidateKind.class)
            .code(1, CandidateKind.HOST)
            .code(2, CandidateKind.PUBLIC)
            .code(3, CandidateKind.SERVER_REFLEXIVE)
            .code(4, CandidateKind.MAPPED)
            .code(5, CandidateKind.RELAY)
            .build();

    /** {@code RegistrationEvent} — a rendezvous registration's intent. */
    public static final WireEnum<RegistrationEvent> REGISTRATION_EVENT =
            WireEnum.builder(RegistrationEvent.class)
                    .code(1, RegistrationEvent.REGISTER)
                    .code(2, RegistrationEvent.REFRESH)
                    .code(3, RegistrationEvent.UNREGISTER)
                    .build();

    /** {@code ServiceKind} — which kind of service a directory row describes. */
    public static final WireEnum<ServiceKind> SERVICE_KIND = WireEnum.builder(ServiceKind.class)
            .code(1, ServiceKind.RENDEZVOUS)
            .code(2, ServiceKind.TRACKER)
            .build();

    /** {@code ServiceLifecycle} — where a service is in its own life. */
    public static final WireEnum<ServiceLifecycle> SERVICE_LIFECYCLE =
            WireEnum.builder(ServiceLifecycle.class)
                    .code(1, ServiceLifecycle.STARTING)
                    .code(2, ServiceLifecycle.SERVING)
                    .code(3, ServiceLifecycle.DRAINING)
                    .code(4, ServiceLifecycle.STOPPED)
                    .build();

    /** {@code RegionReplicaRole} — primary or validator on a committee. Consensus plane. */
    public static final WireEnum<RegionReplicaRole> REGION_REPLICA_ROLE =
            WireEnum.builder(RegionReplicaRole.class)
                    .code(1, RegionReplicaRole.PRIMARY)
                    .code(2, RegionReplicaRole.VALIDATOR)
                    .build();

    /**
     * {@code RegionRefusal.Reason} — why a node believes a region cannot be validated.
     *
     * <p>{@link RegionRefusal.Reason#UNKNOWN} is not a reason — it is the absence of one — so it is
     * parked on the reserved sentinel {@code 0xFFFF} and is <b>never emitted</b>. A refusal whose
     * code this build does not recognise re-encodes from the code it arrived as, not from the
     * stand-in, which is what removes the one canonicality exception the conformance fuzz had to
     * allow: {@code UNKNOWN} used to re-encode as its own ordinal, so a decode-then-encode produced
     * different bytes from the ones it was handed.
     *
     * <p><b>Code 1 is reserved and has no sender in this build.</b> {@code NON_DELEGABLE_ENTITY}
     * was the reason a node with no seat announced when it met an entity the engine does not
     * model; that refusal was retired on 2026-07-29 (issue #236) because it deleted every region
     * from the validated lane within seconds of a real world loading — an unmodelled entity is
     * left to vanilla now, and there is nothing to announce. <b>The code stays assigned to this
     * constant for ever.</b> Builds shipped before that date do send it, this build still decodes
     * and re-checks it on the receive path ({@code WorkerValidationService.onRegionRefusal}), and a
     * renumbering or a reuse would make an old peer's refusal read as a different condition
     * entirely — which is a network split, not a cleanup. Nothing about "unused" applies here: the
     * assignment is a promise to every peer that ever sent it.
     */
    public static final WireEnum<RegionRefusal.Reason> REGION_REFUSAL_REASON =
            WireEnum.builder(RegionRefusal.Reason.class)
                    // Reserved, no sender since 2026-07-29 — see the javadoc above. Never reuse.
                    .code(1, RegionRefusal.Reason.NON_DELEGABLE_ENTITY)
                    .code(2, RegionRefusal.Reason.UNSUPPORTED_PALETTE)
                    .code(3, RegionRefusal.Reason.CHUNKS_NOT_LOADED)
                    .code(4, RegionRefusal.Reason.FAKE_PLAYER_ACTIVE)
                    .code(5, RegionRefusal.Reason.INTERFERENCE_RATE_HIGH)
                    .code(6, RegionRefusal.Reason.NO_PLAYER_PRESENT)
                    .code(0xFFFF, RegionRefusal.Reason.UNKNOWN)
                    .build();
}
