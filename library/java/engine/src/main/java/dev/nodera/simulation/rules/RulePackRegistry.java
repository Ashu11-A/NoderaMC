package dev.nodera.simulation.rules;

import dev.nodera.core.crypto.StableHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The deterministic rule-pack registry (Task 16 / L-21): the SDK's trust anchor. Packs register
 * in a canonical order (sorted by namespace — installation order must not matter), collisions
 * fail loudly at registration (a colliding id can never reach consensus state), and
 * {@link #combinedFingerprint} folds every pack's identity into the base registry fingerprint —
 * the number every committee member pins and the engine refuses on. A member missing a pack, or
 * carrying a different version of one, computes a different fingerprint and is REFUSED at
 * validation time instead of silently diverging mid-simulation.
 *
 * @Thread-context confine registration to startup; reads after {@link #freeze()} are safe anywhere.
 */
public final class RulePackRegistry {

    /** The first palette id available to packs; everything below is the frozen base palette. */
    public static final int PACK_ID_FLOOR = 1000;

    private final List<RulePack> packs = new ArrayList<>();
    private boolean frozen;

    /**
     * Register one pack.
     *
     * @throws IllegalStateException    if the registry is frozen.
     * @throws IllegalArgumentException on a namespace or palette-id collision.
     */
    public void register(RulePack pack) {
        if (frozen) {
            throw new IllegalStateException("rule-pack registry is frozen (register at startup)");
        }
        if (pack == null || pack.namespace() == null || pack.namespace().isBlank()) {
            throw new IllegalArgumentException("pack and its namespace must not be null/blank");
        }
        Set<Integer> ids = new HashSet<>();
        for (RulePack existing : packs) {
            if (existing.namespace().equals(pack.namespace())) {
                throw new IllegalArgumentException(
                        "duplicate rule-pack namespace: " + pack.namespace());
            }
            for (RulePack.PackPaletteEntry e : existing.paletteEntries()) {
                ids.add(e.id());
            }
        }
        for (RulePack.PackPaletteEntry e : pack.paletteEntries()) {
            if (!ids.add(e.id())) {
                throw new IllegalArgumentException(
                        "palette id collision across packs: " + e.id() + " (" + e.name() + ")");
            }
        }
        packs.add(pack);
        packs.sort(java.util.Comparator.comparing(RulePack::namespace));
    }

    /** Freeze the registry (no further registration); idempotent. */
    public void freeze() {
        frozen = true;
    }

    /** @return the registered packs in canonical (namespace) order. */
    public List<RulePack> packs() {
        return List.copyOf(packs);
    }

    /**
     * The pack that declared {@code blockStateId}, if any — the dispatch rule behind
     * {@link PackRules}.
     *
     * <p>Ownership is the ONLY thing that routes an action to pack code, which is what keeps the
     * SDK safe by construction: the base palette is frozen below {@link #PACK_ID_FLOOR}, so no pack
     * can intercept a vanilla block, and registration already refuses a duplicate id, so no two
     * packs can claim the same one.
     *
     * @param blockStateId the palette id.
     * @return the owning pack, or empty when the id belongs to the base palette (or to nobody).
     */
    public java.util.Optional<RulePack> ownerOf(int blockStateId) {
        if (blockStateId < PACK_ID_FLOOR) {
            return java.util.Optional.empty();
        }
        for (RulePack pack : packs) {
            for (RulePack.PackPaletteEntry e : pack.paletteEntries()) {
                if (e.id() == blockStateId) {
                    return java.util.Optional.of(pack);
                }
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * The combined registry fingerprint: {@code base} (the frozen built-in palette) mixed with
     * every pack's identity in canonical order. Installation ORDER does not matter; CONTENT does
     * — any id, name, or semantic difference changes the number and the committee refuses.
     */
    public long combinedFingerprint(long base) {
        if (packs.isEmpty()) {
            // No packs must be indistinguishable from no SDK at all. Folding an empty pack list
            // through the hash would give a modded-but-packless node a different fingerprint from
            // an unmodded one, and the two could never validate each other — the SDK's mere
            // presence would fork the network.
            return base;
        }
        long[] parts = new long[1 + packs.size()];
        parts[0] = base;
        for (int i = 0; i < packs.size(); i++) {
            RulePack pack = packs.get(i);
            long entries = StableHash.of("entries");
            for (RulePack.PackPaletteEntry e : pack.paletteEntries()) {
                entries = StableHash.of(entries, e.id(), StableHash.of(e.name()));
            }
            parts[1 + i] = StableHash.of(
                    StableHash.of(pack.namespace()), entries, pack.semanticFingerprint());
        }
        return StableHash.of(parts);
    }
}
