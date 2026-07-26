package dev.nodera.simulation.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binding between the vanilla registry and the consensus palette. The load-bearing test is
 * {@link #everyPaletteEntryRoundTripsThroughItsVanillaState()}: it is what stops the palette from
 * growing past the live capture lane, because a new id with no binding fails it immediately.
 */
class VanillaPaletteTest {

    @Test
    @DisplayName("every palette id has a vanilla state that maps back to exactly that id")
    void everyPaletteEntryRoundTripsThroughItsVanillaState() {
        List<Integer> unbound = new ArrayList<>();
        List<String> wrongWayBack = new ArrayList<>();
        for (int id = 0; id <= FlatWorldRules.STICKY_PISTON_MAX; id++) {
            if (!FlatWorldRules.isKnown(id)) {
                continue;
            }
            VanillaPalette.VanillaBlock vanilla = VanillaPalette.vanillaOf(id);
            if (vanilla == null) {
                unbound.add(id);
                continue;
            }
            int back = VanillaPalette.idFor(vanilla.key(), vanilla.properties());
            if (back != id) {
                wrongWayBack.add(id + " → " + vanilla.key() + vanilla.properties() + " → " + back);
            }
        }
        assertEquals(List.of(), unbound, "palette ids with no vanilla binding");
        assertEquals(List.of(), wrongWayBack, "palette ids that do not survive the round trip");
    }

    @Test
    @DisplayName("the namespaced key is accepted and a modded namespace never validates")
    void namespaceDecidesWhetherABlockCanBeConsensusState() {
        assertEquals(FlatWorldRules.STONE, VanillaPalette.idFor("minecraft:stone", Map.of()));
        assertEquals(FlatWorldRules.STONE, VanillaPalette.idFor("stone", Map.of()));
        assertEquals(VanillaPalette.UNSUPPORTED,
                VanillaPalette.idFor("create:cogwheel", Map.of()));
        assertEquals(VanillaPalette.UNSUPPORTED, VanillaPalette.idFor("diamond_block", Map.of()));
        assertEquals(VanillaPalette.UNSUPPORTED, VanillaPalette.idFor("", Map.of()));
        assertEquals(VanillaPalette.UNSUPPORTED, VanillaPalette.idFor(null, Map.of()));
    }

    @Test
    @DisplayName("a state the palette cannot express is UNSUPPORTED, not a wrong id")
    void inexpressibleVanillaStatesAreRefused() {
        // The palette has four horizontal facings and no vertical ones: a piston pointing up is
        // genuinely outside consensus, and answering with the north id would be a silent divergence.
        assertEquals(VanillaPalette.UNSUPPORTED,
                VanillaPalette.idFor("piston", Map.of("facing", "up", "extended", "false")));
        assertEquals(VanillaPalette.UNSUPPORTED,
                VanillaPalette.idFor("observer", Map.of("facing", "down", "powered", "false")));
        assertEquals(VanillaPalette.UNSUPPORTED, VanillaPalette.idFor("moving_piston",
                Map.of("facing", "north", "type", "normal")));
        assertEquals(VanillaPalette.UNSUPPORTED,
                VanillaPalette.idFor("redstone_wire", Map.of("power", "16")));
        assertEquals(VanillaPalette.UNSUPPORTED,
                VanillaPalette.idFor("redstone_wire", Map.of("power", "east")));
    }

    @Test
    @DisplayName("redstone state rides the properties, not the block name")
    void poweredStatesMapToTheirOwnPaletteIds() {
        assertEquals(FlatWorldRules.LEVER_ON,
                VanillaPalette.idFor("lever", Map.of("powered", "true", "face", "wall")));
        assertEquals(FlatWorldRules.LEVER_OFF,
                VanillaPalette.idFor("lever", Map.of("powered", "false")));
        // A vanilla redstone torch is lit by default, so a state that omits `lit` is ON.
        assertEquals(FlatWorldRules.TORCH_ON, VanillaPalette.idFor("redstone_torch", Map.of()));
        assertEquals(FlatWorldRules.TORCH_OFF,
                VanillaPalette.idFor("redstone_wall_torch", Map.of("lit", "false")));
        assertEquals(FlatWorldRules.REPEATER_WEST_ON,
                VanillaPalette.idFor("repeater",
                        Map.of("facing", "west", "powered", "true", "delay", "3")));
        assertEquals(FlatWorldRules.COMPARATOR_EAST,
                VanillaPalette.idFor("comparator",
                        Map.of("facing", "east", "mode", "subtract")));
        for (int power = 0; power <= 15; power++) {
            assertEquals(FlatWorldRules.WIRE_0 + power, VanillaPalette.idFor("redstone_wire",
                    Map.of("power", Integer.toString(power))));
        }
    }

    @Test
    @DisplayName("a falling fluid column folds onto a level-1 flow, the way the engine models it")
    void fluidLevelsMapOntoTheFiniteLadder() {
        assertEquals(FlatWorldRules.WATER_SOURCE, VanillaPalette.idFor("water", Map.of("level", "0")));
        assertEquals(FlatWorldRules.WATER_FLOW_BASE,
                VanillaPalette.idFor("water", Map.of("level", "1")));
        assertEquals(FlatWorldRules.WATER_FLOW_BASE + 6,
                VanillaPalette.idFor("water", Map.of("level", "7")));
        // Vanilla levels 8..15 are the falling column; the engine has no separate falling state.
        assertEquals(FlatWorldRules.WATER_FLOW_BASE,
                VanillaPalette.idFor("water", Map.of("level", "8")));
        assertEquals(FlatWorldRules.LAVA_SOURCE, VanillaPalette.idFor("lava", Map.of("level", "0")));
        // Overworld lava steps by two over the engine's three flow levels.
        assertEquals(FlatWorldRules.LAVA_FLOW_BASE, VanillaPalette.idFor("lava", Map.of("level", "2")));
        assertEquals(FlatWorldRules.LAVA_FLOW_BASE + 1,
                VanillaPalette.idFor("lava", Map.of("level", "4")));
        assertEquals(FlatWorldRules.LAVA_FLOW_BASE + 2,
                VanillaPalette.idFor("lava", Map.of("level", "6")));
        // Nether lava steps by one and runs further than the engine ladder: it clamps, never wraps.
        assertEquals(FlatWorldRules.LAVA_FLOW_BASE + 2,
                VanillaPalette.idFor("lava", Map.of("level", "7")));
    }

    @Test
    @DisplayName("air in every shape is air, and unknown properties do not disturb a simple block")
    void airAndPlainBlocksAreStable() {
        assertEquals(FlatWorldRules.AIR, VanillaPalette.idFor("air", Map.of()));
        assertEquals(FlatWorldRules.AIR, VanillaPalette.idFor("cave_air", Map.of()));
        assertEquals(FlatWorldRules.AIR, VanillaPalette.idFor("void_air", Map.of()));
        assertEquals(FlatWorldRules.OAK_LOG, VanillaPalette.idFor("oak_log", Map.of("axis", "x")));
        assertEquals(FlatWorldRules.POWERED_RAIL,
                VanillaPalette.idFor("powered_rail", Map.of("powered", "true", "shape", "ascending_east")));
    }

    @Test
    @DisplayName("the binding has its own fingerprint, and it moves when a row would")
    void bindingFingerprintIsStableAndNonTrivial() {
        long fingerprint = VanillaPalette.bindingFingerprint();
        assertEquals(fingerprint, VanillaPalette.bindingFingerprint());
        assertTrue(fingerprint != 0L, "fingerprint must mix the table, not stay at zero");
        assertTrue(fingerprint != FlatWorldRules.registryFingerprint(),
                "the binding is a second, independent fact from the palette itself");
    }

    @Test
    @DisplayName("every placeable palette id is reachable from a state a player can actually place")
    void placeableIdsHaveAPlayerReachableVanillaState() {
        for (int id = 0; id <= FlatWorldRules.STICKY_PISTON_MAX; id++) {
            if (!FlatWorldRules.isPlaceable(id)) {
                continue;
            }
            assertNotNull(VanillaPalette.vanillaOf(id),
                    "placeable id " + id + " has no vanilla state to capture from");
        }
    }
}
