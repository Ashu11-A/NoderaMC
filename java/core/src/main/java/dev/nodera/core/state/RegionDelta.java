package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A delta between two {@link SnapshotVersion}s of one region (Task 2 state/). The
 * mutation/effect lists are canonical and stored unmodifiable, so two replicas that apply the same
 * transition encode identical bytes regardless of arrival order.
 *
 * <p>Body version 2 appended entity mutations and inventory credits after {@code resultingRoot};
 * body version 3 appends cross-region transfer intents; body version 4 appends the resulting
 * scheduled-tick queue and pending block events (replace semantics — Task 13 timing state is
 * part of the root, so the delta must reproduce it). Older bodies decode with empty appended
 * lists.
 *
 * <p>Wire form: {@code [u16 REGION_DELTA][u16 ENCODING_VERSION][RegionId][SnapshotVersion baseVersion]
 * [SnapshotVersion resultingVersion][list BlockMutation][StateRoot resultingRoot]
 * [list EntityMutation][list InventoryCredit][list EntityTransferIntent]}.
 *
 * @Thread-context immutable, any thread.
 */
public record RegionDelta(
        RegionId region,
        SnapshotVersion baseVersion,
        SnapshotVersion resultingVersion,
        List<BlockMutation> blockMutations,
        StateRoot resultingRoot,
        List<EntityMutation> entityMutations,
        List<InventoryCredit> inventoryCredits,
        List<EntityTransferIntent> transferIntents,
        List<ScheduledTickEntry> scheduledTicks,
        List<BlockEventEntry> blockEvents,
        int bodyVersion
) implements Encodable {

    /** Region-delta body version. Version 1 was block-only; version 2 added entity/effect lists. */
    public static final int STATE_ENCODING_VERSION = 3;

    /**
     * Body version 4 (Task 13 timing): appends the RESULTING scheduled-tick queue and pending
     * block events — REPLACE semantics, not a diff (the queue is small and its total order is
     * part of the root, so shipping the settled truth is the deterministic form). Emitted only
     * when scheduled state exists on either side of the transition; every pre-redstone delta
     * keeps its exact version-3 bytes.
     */
    public static final int SCHEDULED_ENCODING_VERSION = 4;

    private static final Comparator<BlockMutation> MUTATION_ORDER =
            Comparator.comparing(BlockMutation::pos);

    private static final Comparator<EntityMutation> ENTITY_MUTATION_ORDER =
            Comparator.comparing(EntityMutation::id);

    private static final Comparator<InventoryCredit> CREDIT_ORDER =
            Comparator.comparing(InventoryCredit::entityId)
                    .thenComparing(credit -> credit.actor().value());

    private static final Comparator<EntityTransferIntent> TRANSFER_ORDER =
            Comparator.comparing(EntityTransferIntent::entityId);

    /** Scheduled-state-aware delta constructor (body version 4 when the lists carry anything). */
    public RegionDelta(
            RegionId region,
            SnapshotVersion baseVersion,
            SnapshotVersion resultingVersion,
            List<BlockMutation> blockMutations,
            StateRoot resultingRoot,
            List<EntityMutation> entityMutations,
            List<InventoryCredit> inventoryCredits,
            List<EntityTransferIntent> transferIntents,
            List<ScheduledTickEntry> scheduledTicks,
            List<BlockEventEntry> blockEvents) {
        this(region, baseVersion, resultingVersion, blockMutations, resultingRoot,
                entityMutations, inventoryCredits, transferIntents, scheduledTicks, blockEvents,
                SCHEDULED_ENCODING_VERSION);
    }

    /** Source-compatible constructor for block-only callers. */
    public RegionDelta(RegionId region, SnapshotVersion baseVersion,
                       SnapshotVersion resultingVersion, List<BlockMutation> blockMutations,
                       StateRoot resultingRoot) {
        this(region, baseVersion, resultingVersion, blockMutations, resultingRoot,
                List.of(), List.of(), List.of(), List.of(), List.of(), STATE_ENCODING_VERSION);
    }

    /** Source-compatible constructor for callers without border-transfer intents. */
    public RegionDelta(
            RegionId region,
            SnapshotVersion baseVersion,
            SnapshotVersion resultingVersion,
            List<BlockMutation> blockMutations,
            StateRoot resultingRoot,
            List<EntityMutation> entityMutations,
            List<InventoryCredit> inventoryCredits) {
        this(region, baseVersion, resultingVersion, blockMutations, resultingRoot,
                entityMutations, inventoryCredits, List.of(), List.of(), List.of(),
                STATE_ENCODING_VERSION);
    }

    /** Current entity-transfer-aware delta constructor. */
    public RegionDelta(
            RegionId region,
            SnapshotVersion baseVersion,
            SnapshotVersion resultingVersion,
            List<BlockMutation> blockMutations,
            StateRoot resultingRoot,
            List<EntityMutation> entityMutations,
            List<InventoryCredit> inventoryCredits,
            List<EntityTransferIntent> transferIntents) {
        this(region, baseVersion, resultingVersion, blockMutations, resultingRoot,
                entityMutations, inventoryCredits, transferIntents, List.of(), List.of(),
                STATE_ENCODING_VERSION);
    }

    /**
     * Compact constructor. Defensive-copies {@code blockMutations} into an unmodifiable list sorted
     * by {@code (y, z, x)} (via {@link NBlockPos#compareTo}) so the encoded form is byte-stable.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public RegionDelta {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (baseVersion == null) {
            throw new IllegalArgumentException("baseVersion must not be null");
        }
        if (resultingVersion == null) {
            throw new IllegalArgumentException("resultingVersion must not be null");
        }
        if (blockMutations == null) {
            throw new IllegalArgumentException("blockMutations must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (entityMutations == null) {
            throw new IllegalArgumentException("entityMutations must not be null");
        }
        if (inventoryCredits == null) {
            throw new IllegalArgumentException("inventoryCredits must not be null");
        }
        if (transferIntents == null) {
            throw new IllegalArgumentException("transferIntents must not be null");
        }
        if (scheduledTicks == null) {
            throw new IllegalArgumentException("scheduledTicks must not be null");
        }
        if (blockEvents == null) {
            throw new IllegalArgumentException("blockEvents must not be null");
        }
        if (bodyVersion < 1 || bodyVersion > SCHEDULED_ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported region-delta body version " + bodyVersion);
        }
        if (bodyVersion == 1 && (!entityMutations.isEmpty() || !inventoryCredits.isEmpty())) {
            throw new IllegalArgumentException("version 1 delta cannot carry entity lists");
        }
        if (bodyVersion < 3 && !transferIntents.isEmpty()) {
            throw new IllegalArgumentException("legacy delta cannot carry transfer intents");
        }
        if (bodyVersion < SCHEDULED_ENCODING_VERSION
                && (!scheduledTicks.isEmpty() || !blockEvents.isEmpty())) {
            throw new IllegalArgumentException("legacy delta cannot carry scheduled state");
        }
        List<BlockMutation> sorted = new ArrayList<>(blockMutations);
        sorted.sort(MUTATION_ORDER);
        blockMutations = List.copyOf(sorted);
        List<EntityMutation> sortedEntities = new ArrayList<>(entityMutations);
        sortedEntities.sort(ENTITY_MUTATION_ORDER);
        rejectDuplicateEntityMutations(sortedEntities);
        entityMutations = List.copyOf(sortedEntities);
        List<InventoryCredit> sortedCredits = new ArrayList<>(inventoryCredits);
        sortedCredits.sort(CREDIT_ORDER);
        rejectDuplicateCredits(sortedCredits);
        inventoryCredits = List.copyOf(sortedCredits);
        List<EntityTransferIntent> sortedTransfers = new ArrayList<>(transferIntents);
        sortedTransfers.sort(TRANSFER_ORDER);
        rejectDuplicateTransfers(sortedTransfers);
        transferIntents = List.copyOf(sortedTransfers);
        List<ScheduledTickEntry> sortedTicks = new ArrayList<>(scheduledTicks);
        sortedTicks.sort(ScheduledTickEntry.EXECUTION_ORDER);
        scheduledTicks = List.copyOf(sortedTicks);
        blockEvents = List.copyOf(blockEvents); // FIFO order IS the canonical order
    }

    /** True when the delta carries no state mutations or one-way effects. */
    public boolean isEmpty() {
        return blockMutations.isEmpty() && entityMutations.isEmpty()
                && inventoryCredits.isEmpty() && transferIntents.isEmpty()
                && scheduledTicks.isEmpty() && blockEvents.isEmpty();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.REGION_DELTA).writeU16(bodyVersion);
        region.encode(w);
        baseVersion.encode(w);
        resultingVersion.encode(w);
        w.writeList(blockMutations, CanonicalWriter::writeEncodable);
        resultingRoot.encode(w);
        if (bodyVersion >= 2) {
            w.writeList(entityMutations, CanonicalWriter::writeEncodable);
            w.writeList(inventoryCredits, CanonicalWriter::writeEncodable);
        }
        if (bodyVersion >= 3) {
            w.writeList(transferIntents, CanonicalWriter::writeEncodable);
        }
        if (bodyVersion >= SCHEDULED_ENCODING_VERSION) {
            w.writeList(scheduledTicks, CanonicalWriter::writeEncodable);
            w.writeList(blockEvents, CanonicalWriter::writeEncodable);
        }
    }

    /**
     * Full-frame decode. The decoded mutations list is re-canonicalised by the compact constructor.
     *
     * @throws IllegalStateException if the next tag is not {@code REGION_DELTA}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static RegionDelta decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_DELTA) {
            throw new IllegalStateException("expected REGION_DELTA tag, got " + tag);
        }
        int bodyVersion = r.readU16();
        if (bodyVersion < 1 || bodyVersion > SCHEDULED_ENCODING_VERSION) {
            throw new IllegalStateException("unsupported REGION_DELTA encoding version " + bodyVersion);
        }
        RegionId region = RegionId.decode(r);
        SnapshotVersion baseVersion = SnapshotVersion.decode(r);
        SnapshotVersion resultingVersion = SnapshotVersion.decode(r);
        List<BlockMutation> mutations = r.readList(BlockMutation::decode);
        StateRoot resultingRoot = StateRoot.decode(r);
        List<EntityMutation> entityMutations = bodyVersion >= 2
                ? r.readList(EntityMutation::decode)
                : List.of();
        List<InventoryCredit> credits = bodyVersion >= 2
                ? r.readList(InventoryCredit::decode)
                : List.of();
        List<EntityTransferIntent> transfers = bodyVersion >= 3
                ? r.readList(EntityTransferIntent::decode)
                : List.of();
        List<ScheduledTickEntry> scheduledTicks = bodyVersion >= SCHEDULED_ENCODING_VERSION
                ? r.readList(ScheduledTickEntry::decode)
                : List.of();
        List<BlockEventEntry> blockEvents = bodyVersion >= SCHEDULED_ENCODING_VERSION
                ? r.readList(BlockEventEntry::decode)
                : List.of();
        return new RegionDelta(region, baseVersion, resultingVersion, mutations, resultingRoot,
                entityMutations, credits, transfers, scheduledTicks, blockEvents, bodyVersion);
    }

    private static void rejectDuplicateEntityMutations(List<EntityMutation> mutations) {
        for (int i = 1; i < mutations.size(); i++) {
            if (mutations.get(i - 1).id().equals(mutations.get(i).id())) {
                throw new IllegalArgumentException("duplicate entity mutation: " + mutations.get(i).id());
            }
        }
    }

    private static void rejectDuplicateCredits(List<InventoryCredit> credits) {
        for (int i = 1; i < credits.size(); i++) {
            InventoryCredit previous = credits.get(i - 1);
            InventoryCredit current = credits.get(i);
            if (previous.entityId().equals(current.entityId())
                    && previous.actor().equals(current.actor())) {
                throw new IllegalArgumentException(
                        "duplicate inventory credit: " + current.actor() + "/" + current.entityId());
            }
        }
    }

    private static void rejectDuplicateTransfers(List<EntityTransferIntent> transfers) {
        for (int i = 1; i < transfers.size(); i++) {
            if (transfers.get(i - 1).entityId().equals(transfers.get(i).entityId())) {
                throw new IllegalArgumentException(
                        "duplicate entity transfer: " + transfers.get(i).entityId());
            }
        }
    }
}
