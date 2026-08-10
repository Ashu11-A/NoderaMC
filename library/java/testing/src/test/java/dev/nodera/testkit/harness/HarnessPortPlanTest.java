package dev.nodera.testkit.harness;

import dev.nodera.core.services.DefaultServices;
import dev.nodera.headless.PeerNode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The harness's port block and the product's port block must never be the same block again.
 *
 * <h2>What this is guarding</h2>
 *
 * <p>{@link Topology} used to hand every scenario the product's own defaults — tracker 25600,
 * rendezvous 25601, worker control 25610+i, P2P 25620+i. Those are the numbers a shipped Nodera
 * binds: {@code DefaultServices.DEVELOPMENT_TRACKER}, {@link PeerNode#DEFAULT_CONTROL_PORT},
 * {@link PeerNode#DEFAULT_P2P_PORT}. The consequence was not subtle and it was not rare — on any
 * machine with the companion app running, the preflight refused every live scenario, so the more
 * complete somebody's install the less of the suite they could execute. The live report of
 * 2026-08-07 (0 passed, 17 failed, every failure "port 25600 is still held after 60s") is that
 * collision, and it was filed as a stale test stack.
 *
 * <p>Moving the numbers fixes it once. This test is what keeps it fixed: a refactor that re-derives
 * the plan, or a well-meant "align the harness with the documented ports" change, fails here rather
 * than three months later on somebody's laptop. The assertions read the <b>product's own</b>
 * constants rather than copies, so the two tables cannot converge by one side moving either.
 *
 * <p>Thread-context: ordinary JUnit; nothing here binds a socket.
 */
class HarnessPortPlanTest {

    /**
     * The span of the product's block: RCON 25575 through the extra-rendezvous range at 25659.
     *
     * <p>Stated as a range rather than a list because the product's numbers are a block too, and a
     * harness that merely dodged the four ports somebody remembered would collide with the fifth.
     */
    private static final int PRODUCT_BLOCK_FIRST = Topology.PRODUCTION_PORT_BASE + 75;
    private static final int PRODUCT_BLOCK_LAST = Topology.PRODUCTION_PORT_BASE + 159;

    @Test
    void theHarnessBlockDoesNotOverlapTheProductBlock() {
        assertThat(Topology.DEFAULT_PORT_BASE)
                .as("the harness's base must clear the product's whole block, not just its ports")
                .isGreaterThan(PRODUCT_BLOCK_LAST);

        assertThat(Topology.blockPorts(Topology.DEFAULT_PORT_BASE))
                .as("every port the harness can bind, against the product's block")
                .noneMatch(port -> port >= PRODUCT_BLOCK_FIRST && port <= PRODUCT_BLOCK_LAST);
    }

    @Test
    void noDefaultPortEqualsAProductDefault() {
        Topology topology = Topology.standard().withPortBase(Topology.DEFAULT_PORT_BASE);

        assertThat(topology.trackerPort()).isNotEqualTo(portOf(DefaultServices.DEVELOPMENT_TRACKER));
        assertThat(topology.rendezvousPort())
                .isNotEqualTo(portOf(DefaultServices.DEVELOPMENT_RENDEZVOUS));
        // Ten workers is well past any topology in the tree; the whole run of control and P2P ports
        // is checked because the collision that caused this was on worker 0 and would have been on
        // worker 1 next.
        for (int index = 0; index < 10; index++) {
            assertThat(topology.workerControlPort(index))
                    .isNotEqualTo(PeerNode.DEFAULT_CONTROL_PORT);
            assertThat(topology.workerP2pPort(index)).isNotEqualTo(PeerNode.DEFAULT_P2P_PORT);
        }
    }

    @Test
    void everyPortAnyTopologyBindsIsInsideTheAnnouncedBlock() {
        Topology topology = Topology.standard().withPortBase(Topology.DEFAULT_PORT_BASE)
                .withPlayers(3).withSparePeers(3).withServices(3, 3);
        List<Integer> block = Topology.blockPorts(Topology.DEFAULT_PORT_BASE);

        // If a port escaped the block, the startup probe would have declared a block free while
        // something else held a port the run is about to need — the exact failure mode this class
        // is here to prevent, one level down.
        assertThat(topology.allPorts()).isSubsetOf(block);
        assertThat(topology.rconPort()).isIn(block);
    }

    @Test
    void aScenariosOwnServicesAlsoLandInsideTheBlockAndNotOnTheStacksPorts() {
        // TelemetryScenario used to bind 25630/25640/25641 as literals: inside the PRODUCT's block
        // and outside anything the probe checked, so a "free" block could be declared while a
        // developer's dev stack already held one of them. Every port a run binds has to be in the
        // block or the probe is decorative.
        Topology topology = Topology.standard().withPortBase(Topology.DEFAULT_PORT_BASE)
                .withPlayers(3).withSparePeers(3).withServices(3, 3);
        List<Integer> block = Topology.blockPorts(Topology.DEFAULT_PORT_BASE);

        for (int index = 0; index < 10; index++) {
            assertThat(topology.scenarioPort(index)).isIn(block);
            assertThat(topology.allPorts()).doesNotContain(topology.scenarioPort(index));
            // ServerEndpointSupport slots its endpoint's control/p2p straight after the workers.
            assertThat(topology.scenarioPort(index))
                    .isNotEqualTo(topology.workerControlPort(topology.workers()))
                    .isNotEqualTo(topology.workerP2pPort(topology.workers()));
        }
        assertThatThrownBy(() -> topology.scenarioPort(10))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("reserves ten");
    }

    @Test
    void aBaseOfTheProductBlockReproducesTheHistoricalNumbers() {
        // The offsets are the contract with every doc, script and log line that ever named these
        // ports; only the base moved. Pinning that here means the fix is a shift and not a rewrite,
        // and a reader comparing an old run's log to a new one can do the arithmetic in their head.
        Topology historical = Topology.standard().withPortBase(Topology.PRODUCTION_PORT_BASE);

        assertThat(historical.rconPort()).isEqualTo(25575);
        assertThat(historical.gamePort()).isEqualTo(25599);
        assertThat(historical.trackerPort()).isEqualTo(25600);
        assertThat(historical.rendezvousPort()).isEqualTo(25601);
        assertThat(historical.workerControlPort(0)).isEqualTo(25610);
        assertThat(historical.workerP2pPort(0)).isEqualTo(25620);
        assertThat(historical.withServices(2, 2).trackerPortAt(1)).isEqualTo(25641);
        assertThat(historical.withServices(2, 2).rendezvousPortAt(1)).isEqualTo(25651);
    }

    @Test
    void theChosenBaseIsNeverBelowTheHarnessDefault() {
        // chosenBase() probes the machine, so its exact value is not fixed — but the walk only ever
        // steps upwards, and a base below the default would be back in the product's territory.
        // An operator who pinned NODERA_E2E_PORT_BASE has overridden the walk on purpose and this
        // assertion is not about them; the identity below still is.
        if (System.getenv("NODERA_E2E_PORT_BASE") == null) {
            assertThat(Topology.chosenBase())
                    .isGreaterThanOrEqualTo(Topology.DEFAULT_PORT_BASE);
        }
        assertThat(Topology.standard().portBase())
                .isEqualTo(Topology.chosenBase());
    }

    private static int portOf(String endpoint) {
        return Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1));
    }
}
