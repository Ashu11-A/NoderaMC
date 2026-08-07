package dev.nodera.testkit.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.headless.WorkerControlHandler;
import dev.nodera.headless.WorldArchiveService;
import dev.nodera.headless.WorldDeletionService;
import dev.nodera.headless.WorldHostingService;
import dev.nodera.headless.WorldKeyStore;
import dev.nodera.headless.WorldRegistryStore;
import dev.nodera.headless.WorldReplicationService;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.harness.ControlClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * An always-on worker behind its real control endpoint — the surface the companion app and the mod
 * speak to.
 *
 * <p>{@code docs/peer/REFACTORING.md} names the missing piece here a {@code ControlSocketHarness}:
 * eight verb ITs each carried their own twelve-line "open a socket to 127.0.0.1:port, write the
 * verb, read one line". That harness already existed — {@link ControlClient}, over the product's
 * own {@code CompanionClient} — so this reuses it rather than minting a fourth implementation of
 * the same wire. No assertion here can pass on a state the product's own client could not read.
 *
 * <p>Which services a worker is composed of is genuinely per-test — a node with an archive and no
 * key store is a different node from one with keys and no archive, and both are real deployments —
 * so every service is a named option on {@link PeerTestHarness.WorkerNodeBuilder} and the accessors
 * below return {@code null} for the ones this worker was not built with.
 *
 * <p>Thread-context: {@link #request} opens and closes one socket per call, exactly like the app.
 */
public final class WorkerNode {

    private final NodeIdentity identity;
    private final NodeCapabilities capabilities;
    private final LoopbackTransport transport;
    private final PeerRuntime runtime;
    private final TrackerClient tracker;
    private final WorldArchiveService archive;
    private final WorldHostingService hosting;
    private final WorldReplicationService replication;
    private final WorldDeletionService deletions;
    private final WorldKeyStore keys;
    private final WorldRegistryStore registry;
    private final WorkerControlHandler handler;
    private final ControlServer control;
    private final ControlClient client;

    WorkerNode(NodeIdentity identity, NodeCapabilities capabilities, LoopbackTransport transport,
               PeerRuntime runtime, TrackerClient tracker, WorldArchiveService archive,
               WorldHostingService hosting, WorldReplicationService replication,
               WorldDeletionService deletions, WorldKeyStore keys, WorldRegistryStore registry,
               WorkerControlHandler handler, ControlServer control,
               java.time.Duration controlTimeout) {
        this.identity = identity;
        this.capabilities = capabilities;
        this.transport = transport;
        this.runtime = runtime;
        this.tracker = tracker;
        this.archive = archive;
        this.hosting = hosting;
        this.replication = replication;
        this.deletions = deletions;
        this.keys = keys;
        this.registry = registry;
        this.handler = handler;
        this.control = control;
        this.client = new ControlClient(control.boundPort(), controlTimeout);
    }

    /** @return the worker's identity. */
    public NodeIdentity identity() {
        return identity;
    }

    /** @return the worker's id. */
    public NodeId nodeId() {
        return identity.nodeId();
    }

    /** @return the capabilities the worker announces. */
    public NodeCapabilities capabilities() {
        return capabilities;
    }

    /** @return the mesh transport. */
    public LoopbackTransport transport() {
        return transport;
    }

    /** @return the peer runtime. */
    public PeerRuntime runtime() {
        return runtime;
    }

    /** @return the shared tracker client, or {@code null} if this worker has none. */
    public TrackerClient tracker() {
        return tracker;
    }

    /** @return the archive lane, or {@code null} if this worker has none. */
    public WorldArchiveService archive() {
        return archive;
    }

    /** @return the hosting registry — every worker has one. */
    public WorldHostingService hosting() {
        return hosting;
    }

    /** @return the replication lane, or {@code null} if this worker has none. */
    public WorldReplicationService replication() {
        return replication;
    }

    /** @return the deletion lane, or {@code null} if this worker has none. */
    public WorldDeletionService deletions() {
        return deletions;
    }

    /** @return the world-key store, or {@code null} if this worker administers nothing. */
    public WorldKeyStore keys() {
        return keys;
    }

    /** @return the durable world registry, or {@code null} if this worker keeps none. */
    public WorldRegistryStore registry() {
        return registry;
    }

    /** @return the control handler behind the endpoint. */
    public WorkerControlHandler handler() {
        return handler;
    }

    /** @return the port the control endpoint bound. */
    public int controlPort() {
        return control.boundPort();
    }

    /** @return the control-socket client, for the waits {@link ControlClient} already knows. */
    public ControlClient control() {
        return client;
    }

    /**
     * Send one control line and return the single reply line.
     *
     * <p>Silence is a failure rather than a {@code null}: an assertion made against a worker that
     * did not answer is the failure mode that turns "the feature is broken" into "the process was
     * busy".
     *
     * @param line e.g. {@code "NODERA-STATE 2"}.
     * @return the reply line, ERR replies included.
     */
    public String request(String line) {
        return client.require(line);
    }

    /** @return the worker's whole {@code NODERA-STATE} document. */
    public String state() {
        return request(ControlProtocol.STATE + " 2");
    }

    /**
     * Strip the {@code NODERA-OK } prefix from a reply, failing if it is not one.
     *
     * @param reply the reply line.
     * @return the payload after the prefix.
     * @throws AssertionError if the reply is absent or is not an OK.
     */
    public static String okPayload(String reply) {
        if (reply == null || !reply.startsWith(ControlProtocol.OK + " ")) {
            throw new AssertionError("expected an OK reply, got: " + reply);
        }
        return reply.substring(ControlProtocol.OK.length() + 1).trim();
    }

    /** @return {@code text} base64-encoded, the way every control argument is carried. */
    public static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** @return {@code bytes} base64-encoded. */
    public static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** @return {@code bytes} base64-encoded. */
    public static String b64(dev.nodera.core.Bytes bytes) {
        return b64(bytes.toArray());
    }
}
