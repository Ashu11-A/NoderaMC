package dev.nodera.simulation.rules;

import java.util.List;

/**
 * One third-party rule pack (Task 16 / L-21): a mod's deterministic contribution to the validated
 * lane — extra palette entries and (later increments) rule hooks. A pack is IDENTITY first: its
 * {@link #semanticFingerprint()} must change whenever its semantics change, because the pack set
 * is folded into the registry fingerprint every committee member pins — two members whose packs
 * differ in any id, name, or semantic refuse to validate each other instead of silently
 * diverging.
 *
 * @Thread-context implementations must be immutable; any thread.
 */
public interface RulePack {

    /** One palette entry contributed by a pack. */
    record PackPaletteEntry(int id, String name) {
        public PackPaletteEntry {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("palette entry name must not be blank");
            }
            if (id < RulePackRegistry.PACK_ID_FLOOR) {
                throw new IllegalArgumentException(
                        "pack palette ids start at " + RulePackRegistry.PACK_ID_FLOOR
                                + " (below is the frozen base palette): " + id);
            }
        }
    }

    /** @return the pack's stable namespace (e.g. {@code "examplemod"}); part of the identity. */
    String namespace();

    /** @return the palette entries this pack contributes (ids ≥ {@code PACK_ID_FLOOR}). */
    List<PackPaletteEntry> paletteEntries();

    /** @return a stable hash of the pack's SEMANTICS — bump on any behavioral change. */
    long semanticFingerprint();

    /**
     * The pack's executable rules, or empty for a palette-only pack.
     *
     * <p>Default-empty on purpose: a pack that only contributes ids stays valid and keeps its
     * identity folded into the registry fingerprint exactly as before. Behaviour is opt-in, and
     * opting in does not change how the pack is identified.
     *
     * @return this pack's {@link PackRules}, or {@link java.util.Optional#empty()}.
     */
    default java.util.Optional<PackRules> rules() {
        return java.util.Optional.empty();
    }
}
