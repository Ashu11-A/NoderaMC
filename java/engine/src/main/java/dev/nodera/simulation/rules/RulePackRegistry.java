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
     * The combined registry fingerprint: {@code base} (the frozen built-in palette) mixed with
     * every pack's identity in canonical order. Installation ORDER does not matter; CONTENT does
     * — any id, name, or semantic difference changes the number and the committee refuses.
     */
    public long combinedFingerprint(long base) {
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
