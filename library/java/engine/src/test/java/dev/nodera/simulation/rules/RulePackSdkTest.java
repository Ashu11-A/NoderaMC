package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 16 / L-21 (SDK core): rule packs are IDENTITY folded into the registry fingerprint.
 * Installation order never matters, any content difference always does, collisions die at
 * registration, and a committee member whose pack set differs is REFUSED by the engine's
 * existing fingerprint gate instead of silently diverging.
 */
final class RulePackSdkTest {

    private record TestPack(String namespace, List<RulePack.PackPaletteEntry> paletteEntries,
                            long semanticFingerprint) implements RulePack {
    }

    private static TestPack pack(String ns, int id, String name, long semantics) {
        return new TestPack(ns, List.of(new RulePack.PackPaletteEntry(id, name)), semantics);
    }

    @Test
    void contentDecidesTheFingerprintInstallationOrderNever() {
        RulePackRegistry a = new RulePackRegistry();
        a.register(pack("alpha", 1000, "alpha_block", 1L));
        a.register(pack("beta", 1100, "beta_block", 2L));

        RulePackRegistry b = new RulePackRegistry();
        b.register(pack("beta", 1100, "beta_block", 2L));
        b.register(pack("alpha", 1000, "alpha_block", 1L));

        long base = FlatWorldRules.registryFingerprint();
        assertThat(b.combinedFingerprint(base))
                .as("order-independent: both members compute the identical number")
                .isEqualTo(a.combinedFingerprint(base));
        assertThat(a.combinedFingerprint(base))
                .as("packs change the fingerprint — a packless member differs")
                .isNotEqualTo(base);

        RulePackRegistry c = new RulePackRegistry();
        c.register(pack("alpha", 1000, "alpha_block", 1L));
        c.register(pack("beta", 1100, "beta_block", 99L)); // same ids, DIFFERENT semantics
        assertThat(c.combinedFingerprint(base))
                .as("a semantic bump alone forces refusal")
                .isNotEqualTo(a.combinedFingerprint(base));
    }

    @Test
    void collisionsAndBasePaletteIntrusionsDieAtRegistration() {
        RulePackRegistry registry = new RulePackRegistry();
        registry.register(pack("alpha", 1000, "alpha_block", 1L));
        assertThatThrownBy(() -> registry.register(pack("alpha", 1200, "other", 2L)))
                .as("duplicate namespace")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(pack("gamma", 1000, "clash", 3L)))
                .as("palette id collision across packs")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pack("delta", 5, "base_intrusion", 4L))
                .as("ids below the pack floor are the frozen base palette")
                .isInstanceOf(IllegalArgumentException.class);
        registry.freeze();
        assertThatThrownBy(() -> registry.register(pack("late", 1300, "late_block", 5L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPackDivergentMemberIsRefusedByTheEngineFingerprintGate() {
        RulePackRegistry withPack = new RulePackRegistry();
        withPack.register(pack("alpha", 1000, "alpha_block", 1L));
        long divergent = withPack.combinedFingerprint(FlatWorldRules.registryFingerprint());

        FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(),
                new HashService());
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, 1L,
                FlatWorldRules.RULES_VERSION, divergent);
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, List.of());
        assertThatThrownBy(() -> engine.execute(new RegionExecutionRequest(ctx, base, batch)))
                .as("mixed pack sets refuse at the existing gate — no silent divergence")
                .isInstanceOf(IllegalStateException.class);
    }
}
