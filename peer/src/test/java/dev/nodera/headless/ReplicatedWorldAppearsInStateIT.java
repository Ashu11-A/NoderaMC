package dev.nodera.headless;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A world this node only <b>replicates</b> reaches the companion app.
 *
 * <p>{@code NODERA-STATE}'s world array was built purely from the hosting registry — what somebody
 * on this machine shared. A node that adopted a world off the tracker directory and holds pieces of
 * it appears in that registry only once the fetch <i>completes</i> ({@code WorldReplicationService}
 * calls {@code hosting.seed} at the end), so for the whole of a download it contributed two
 * aggregate numbers and no world at all.
 *
 * <p>Live evidence, from the phone: 91 of 465 pieces verified, 23.8 MB on disk, Worlds tab reading
 * "No worlds". This asserts the state a replication peer is actually in — pieces held, nothing
 * hosted — and that the row it produces says seeding, not owned and not connected, so nothing
 * downstream can mistake it for a world that can be joined here.
 */
final class ReplicatedWorldAppearsInStateIT {

    private final HashService hashes = new HashService();
    private ControlServer control;
    private WorldArchiveService archive;
    private WorldHostingService hosting;
    private PeerRuntime runtime;
    private LoopbackTransport transport;

    @AfterEach
    void tearDown() {
        if (control != null) {
            control.close();
        }
        if (archive != null) {
            archive.close();
        }
        if (hosting != null) {
            try {
                hosting.close();
            } catch (RuntimeException ignored) {
                // teardown
            }
        }
        if (runtime != null) {
            try {
                runtime.stop();
            } catch (RuntimeException ignored) {
                // teardown
            }
        }
        if (transport != null) {
            transport.stop();
        }
    }

    private void startWorker() throws java.io.IOException {
        NodeIdentity identity = NodeIdentity.generate();
        NodeCapabilities caps = NodeCapabilities.initial()
                .withRoles(EnumSet.of(PeerRole.FULL_ARCHIVE));
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        runtime = PeerRuntime.bootstrap(identity, caps, transport,
                () -> "loopback", PeerRuntimeConfig.defaults(), null);
        archive = new WorldArchiveService(
                identity, transport, new InMemoryContentStore(hashes), List.of());
        hosting = new WorldHostingService(identity, caps, runtime::selfRoute,
                List.of(), List.of(), archive::holdingsFor);
        control = new ControlServer("127.0.0.1", 0, new WorkerControlHandler(
                "replicated-world-test", identity, caps, runtime,
                new dev.nodera.diagnostics.metric.TrafficMeter(), hosting, null, archive));
        control.start();
    }

    private String request(String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", control.boundPort()), 2000);
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    @Test
    @DisplayName("pieces held for somebody else's world are reported as a world, not just as bytes")
    void aReplicatedWorldIsReportedEvenThoughNothingHostsIt() throws Exception {
        startWorker();
        String world = hashes.sha256("replicated-only".getBytes()).toHex();
        byte[] blob = new byte[512 * 1024];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 31);
        }
        // What a completed piece fetch leaves behind: manifest known, pieces verified and held —
        // and not one word about it in the hosting registry, because nobody here shared it.
        archive.seedArchive(world, blob);
        assertThat(hosting.hostedWorlds())
                .as("the registry is the wrong place to ask; this node hosts nothing")
                .isEmpty();

        String state = request(ControlProtocol.STATE + " 2");

        assertThat(state).contains("\"world_id\":\"" + world + "\"");
        assertThat(state)
                .as("held for the network, not run here and not played here")
                .contains("\"seeding\":true")
                .contains("\"owned\":false")
                .contains("\"connected\":false");
        assertThat(state)
                .as("nothing can offer a join to a world this node has no game in")
                .contains("\"mc_route\":\"\"");
        assertThat(state)
                .as("a replication peer cannot count anybody's players; -1 is the unknown")
                .contains("\"players\":-1");
        // The piece picture is the point of the row: it is what turns "23.8 MB somewhere" into
        // "this much of that world".
        int pieces = archive.pieceReport(world).pieceCount();
        assertThat(pieces).isGreaterThan(1);
        assertThat(state).contains("\"piece_count\":" + pieces)
                .contains("\"pieces_held\":" + pieces);
    }

    @Test
    @DisplayName("a hosted world is listed once, from the registry, not twice")
    void aHostedWorldIsNotDuplicatedByTheReplicationRows() throws Exception {
        startWorker();
        String world = hashes.sha256("hosted-and-held".getBytes()).toHex();
        hosting.host(world, "A World", "{}");
        archive.seedArchive(world, "hello world archive".getBytes(StandardCharsets.UTF_8));

        String state = request(ControlProtocol.STATE + " 2");

        int first = state.indexOf("\"world_id\":\"" + world + "\"");
        assertThat(first).as("the hosted world is reported").isGreaterThan(0);
        assertThat(state.indexOf("\"world_id\":\"" + world + "\"", first + 1))
                .as("and exactly once — the registry row wins over the replication row")
                .isEqualTo(-1);
        assertThat(state).contains("\"name\":\"A World\"");
    }
}
