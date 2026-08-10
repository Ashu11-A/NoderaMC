package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.endpoint.share.HostJoinGate;
import dev.nodera.endpoint.share.ShareOptions;
import dev.nodera.endpoint.telemetry.ModTelemetry;
import dev.nodera.endpoint.world.NoderaWorldStore;
import dev.nodera.peer.control.CompanionLink;
import dev.nodera.storage.WorldIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The Minecraft-free half of putting a world on the Nodera network (MC-JOIN-3).
 *
 * <p>{@link NoderaHost#activate} used to do all of this inline on its caller, and its caller is the
 * server thread: a companion mint with its own timeouts, a P2P bind, a relay reservation that
 * iterates <i>every</i> configured rendezvous endpoint at five seconds to connect and ten to read, a
 * tracker announce, a memory-hard KDF for the join gate and a {@code Files.walk} of the entire save.
 * On an integrated server that thread is the one behind the singleplayer loading screen, so a relay
 * set pointed at a black hole was a world that took a minute and a half to open with nothing on
 * screen saying why.
 *
 * <p>This class is the part that must <b>not</b> run there, and it is a separate class rather than a
 * few private methods for two reasons. It cannot name {@link net.minecraft.server.MinecraftServer}
 * at all — everything it needs is handed to it as a {@link Request} snapshot, so there is no way for
 * it to read the level name, the save path or the player list from the wrong thread. And being
 * Minecraft-free is what makes the claim testable: the register's exit is a statement about a
 * thread, a statement about a thread is proven by watching one, and a class that touches Minecraft
 * cannot even be loaded in an ordinary unit test.
 *
 * <p>What stays on the server thread is in {@link NoderaHost}: the certified genesis (it digests
 * live chunk sections), the author's operator grant (it touches the player list), publishing the
 * game port ({@code publishServer}), the archive seed (it flushes the save) and the entity-lane
 * bootstrap (it reads player positions). Those come back through the executor {@link #begin} is
 * handed, never by anybody blocking.
 *
 * @Thread-context {@link #begin} is called on the server thread and returns immediately; everything
 *     else runs on {@code nodera-host-activate}.
 */
final class HostActivation {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaHost");

    private HostActivation() {
    }

    /**
     * Everything the off-thread half of a share needs, read once on the server thread.
     *
     * <p>Deliberately Minecraft-free and deliberately a <b>snapshot</b>. The bring-up thread must
     * never reach back into the {@code MinecraftServer} for a value it could have been handed: a read of
     * the level name, the save path, the player list or a NeoForge config value from another thread
     * is a data race whose symptom would be a rare, unreproducible wrong answer rather than an
     * exception. Being Minecraft-free is also what makes {@link #begin} assertable
     * headlessly — the register's exit is a claim about a thread, and a claim about a thread has to
     * be tested by watching one.
     *
     * @param saveRoot     the world's save directory.
     * @param world        the world's display name.
     * @param options      the share options chosen for it.
     * @param hostIdentity the host's persistent node identity (the genesis signer).
     * @param genesisSeed  the certified genesis root, or the interim name hash if certification
     *                     failed — the seed a first-time mint derives the world id from.
     * @param bindHost     {@code p2p.bindHost}, read on the server thread.
     * @param p2pPort      {@code p2p.port}, read on the server thread.
     * @param advertiseHost {@code p2p.advertiseHost}, read on the server thread.
     * @param already      whether this world was already on the network (a re-share).
     */
    record Request(Path saveRoot, String world, ShareOptions options,
                             dev.nodera.core.identity.NodeIdentity hostIdentity, Bytes genesisSeed,
                             String bindHost, int p2pPort, String advertiseHost, boolean already) {
    }

    /**
     * What the bring-up produced, handed back to the server thread to finish the share.
     *
     * @param request the snapshot the bring-up ran from.
     * @param worldId the world's network id.
     * @param route   the host peer's route, or {@code null} when the P2P mesh did not come up.
     */
    record Outcome(Request request, Bytes worldId, String route) {
    }

    /**
     * Which share this process is building. Bumped on every activation <b>and</b> on every
     * {@link NoderaHost#deactivate}/{@link NoderaHost#onServerStopping}, so an in-flight bring-up can ask one question —
     * "is the share I am building still the one this process wants?" — and abandon itself without
     * taking a lock the shutdown path also wants.
     */
    private static final java.util.concurrent.atomic.AtomicLong ACTIVATION_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Whether a bring-up is between its start and its hand-back.
     *
     * <p>{@code isHosting()} used to be the whole re-entrancy story, and it stops being one the
     * moment the host runtime is built off-thread: two shares in the same second would otherwise
     * start two bring-ups, and the loser would leak a bound P2P socket and a relay registration
     * nothing ever stops.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ACTIVATION_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Test seam: run at the top of the off-thread bring-up, before anything it does. {@code null} in
     * production, and left {@code null} by every production path.
     *
     * <p>MC-JOIN-3's exit is a claim about a <i>thread</i> — "world load time is unaffected by an
     * unreachable relay set" — and the only honest way to assert that headlessly is to hold the slow
     * half open and observe that the caller has already returned. A deliberately-slow hook is what
     * holds it open; a timing assertion would be a flake generator on a loaded box.
     *
     * @Thread-context written before a share begins, read on the bring-up thread.
     */
    static volatile Runnable activationStall;

    /**
     * Abandon whatever bring-up is in flight, without waiting for it.
     *
     * <p>The counterpart of the generation check inside {@link #bringUp}: unsharing or closing a
     * world must not queue behind a relay reservation that has another eighty seconds to run, and it
     * must not let that reservation publish a game server for a world nobody is hosting any more.
     *
     * @return the new generation.
     */
    static long abandon() {
        return ACTIVATION_GENERATION.incrementAndGet();
    }

    /** @return whether the share this bring-up is building has been superseded or stopped. */
    private static boolean abandoned(long generation) {
        return ACTIVATION_GENERATION.get() != generation;
    }
    /**
     * Start the off-thread half of a share and return.
     *
     * <p>Minecraft-free by construction: it is handed a snapshot, a way to get back onto the server
     * thread, and what to run when it is there. That is what lets the thread claim this row makes be
     * asserted in an ordinary unit test rather than only in a live client.
     *
     * @param request       the server-thread snapshot.
     * @param onServerThread how to post work back to the server thread ({@code server::execute}).
     * @param finish        the server-thread half, run through {@code onServerThread}.
     * @return whether a bring-up was started. {@code false} means one was already running, which is
     *         the whole of the re-entrancy answer and the only observable this class has: the
     *         in-flight flag itself is deliberately not exposed, because a getter no production
     *         caller reads is dead code with a test holding it up.
     */
    static boolean begin(Request request,
                         java.util.function.Consumer<Runnable> onServerThread,
                         java.util.function.Consumer<Outcome> finish) {
        if (!ACTIVATION_IN_FLIGHT.compareAndSet(false, true)) {
            // Not an error: a player who clicks Share twice, or a create-share racing the
            // auto-re-share, would otherwise get two host peers for one world.
            LOG.info("Nodera: a share of '{}' is already being brought up; ignoring the second",
                    request.world());
            return false;
        }
        long generation = ACTIVATION_GENERATION.incrementAndGet();
        Thread.ofPlatform().name("nodera-host-activate").daemon().start(
                () -> bringUp(generation, request, onServerThread, finish));
        return true;
    }

    /**
     * The slow half of {@link NoderaHost#activate}, on its own thread and off the tick loop.
     *
     * <p>Self-catching, and it has to be: this runs on a bare {@link Thread}, so nothing above it
     * can contain a failure. {@code ServerBootstrap.safeActivate} used to be that container, and it
     * no longer sees anything thrown here — NeoForge's EventBus does not isolate listener
     * exceptions, so a share that threw where the bus could see it took the integrated server down
     * with it. {@code LinkageError} is caught alongside {@code RuntimeException} for the reason the
     * rest of this mod does: a class missing at runtime is a broken install, not a broken world.
     *
     * <p>The hand-back is in a {@code finally} on purpose. "Keep the server, drop the feature" is
     * this file's oldest rule (issue #39): a world whose mesh refused to start is still a world, so
     * the game port is published and the archive seeded whatever happened above.
     */
    private static void bringUp(long generation, Request request,
                                    java.util.function.Consumer<Runnable> onServerThread,
                                    java.util.function.Consumer<Outcome> finish) {
        ShareOptions opts = request.options();
        String world = request.world();
        Path saveRoot = request.saveRoot();
        Bytes worldId = request.genesisSeed();
        String route = null;
        try {
            Runnable stall = activationStall;
            if (stall != null) {
                stall.run();
            }
            if (abandoned(generation)) {
                return;
            }

            // Establish (or reuse) the world's signed identity + unique id (Task 33). The worker is
            // the author (it holds the signing key); the record is persisted into the save folder so
            // the world keeps its id + author + shared status across restarts. A fresh identity
            // derives its worldId from the certified genesis root (30c); an existing record keeps
            // its id.
            WorldIdentity identity = ensureIdentity(saveRoot, world, opts, request.genesisSeed());
            // The persisted record is consulted even when the mint failed. Falling straight through
            // to `genesisSeed` announced a world that already had a name under a second, different
            // one — and since the genesis root is re-derived, that second name changed again on the
            // next launch. A save that has ever been named keeps that name whatever the worker is
            // doing.
            worldId = identity != null
                    ? identity.worldId()
                    : NoderaWorldStore.read(saveRoot).map(WorldIdentity::worldId)
                            .orElse(request.genesisSeed());
            if (abandoned(generation)) {
                return;
            }

            try {
                route = NoderaPeerService.get().startHost(
                        request.bindHost(), request.p2pPort(), request.advertiseHost(),
                        opts, worldId, world, request.hostIdentity());
            } catch (RuntimeException e) {
                // Issue #39 defense-in-depth: startHost degrades internally (returns null on a bind
                // failure), but never let a transport failure stop the rest of the share. The game
                // server + worker lane still run so the world stays playable.
                LOG.warn("Nodera: host peer start threw for '{}' ({}); continuing in vanilla-only "
                        + "mode", world, e.getMessage());
                route = null;
            }

            // L-52: the password stops being a property of the archive alone. While a
            // password-protected world is hosted, every joining connection must prove it knows the
            // password in the configuration phase, before a player is created.
            //
            // Armed HERE, and armed synchronously, because the game port is opened by the hand-back
            // below: this is the first arrangement in which the gate is closed before the world can
            // be dialled at all. Arming derives a memory-hard key, which is exactly why it never
            // belonged on the server thread — and this thread is not the server thread.
            armGateNow(worldId, opts, world);

            NoderaHost.applyHostPermissions(saveRoot, worldId, identity);

            // Telemetry: a share happened, whether it was password-protected, and roughly how big
            // the world is. No name, no id, no seed — the event type cannot carry them. The size is
            // a `Files.walk` of the save tree, which is the other thing this row names.
            ModTelemetry.worldShared(opts.password() != null && !opts.password().isBlank(),
                    saveSizeBytes(saveRoot), request.already() ? "rehost" : "existing_world");

            if (route == null) {
                // Issue #39: the P2P mesh did not come up, but the hand-back below still publishes
                // the game server, so the world is playable over direct/LAN — just not on Nodera.
                LOG.warn("Nodera: '{}' running in vanilla-only mode — the P2P mesh did not start; "
                        + "direct/LAN joins still work. Retry Share once the port is free.", world);
            } else if (request.already()) {
                LOG.info("Nodera: '{}' share options updated ({}) — route {}", world, opts, route);
            } else {
                LOG.info("Nodera: sharing world '{}' to the network at {} ({})", world, route, opts);
                if (!opts.listedOnTracker()) {
                    LOG.info("Nodera: '{}' is invite-only (not announced to the tracker)", world);
                }
            }
            // Live lane (Task 9/19/23): genesis-from-current-world + self-cert, PieceManifest
            // emission, per-piece encryption iff opts.encryptionEnabled(), and content seeding.
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Nodera: sharing '{}' failed ({}) — the world stays playable, but it is not "
                    + "on the Nodera network. Retry Share once the cause is fixed.", world,
                    e.toString());
        } finally {
            ACTIVATION_IN_FLIGHT.set(false);
            if (!abandoned(generation)) {
                Outcome outcome = new Outcome(request, worldId, route);
                onServerThread.accept(() -> finish.accept(outcome));
            }
        }
    }    /**
     * Rough on-disk size of a save, for the telemetry size bucket.
     *
     * <p>Best-effort and bounded: it walks the save tree, gives up on any I/O error, and the result
     * is bucketed to whole megabytes before it leaves the machine. A precise byte count is a
     * fingerprint; an order of magnitude is the fact worth having.
     */
    private static long saveSizeBytes(Path saveRoot) {
        try (java.util.stream.Stream<Path> tree = java.nio.file.Files.walk(saveRoot, 4)) {
            return tree.filter(java.nio.file.Files::isRegularFile).mapToLong(path -> {
                try {
                    return java.nio.file.Files.size(path);
                } catch (java.io.IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (java.io.IOException | RuntimeException e) {
            return 0L;
        }
    }


    /**
     * Load or mint + persist the world's {@link WorldIdentity}. If a record already exists it is
     * re-signed to reflect the current share state; otherwise the worker mints a fresh signed record
     * (the worker is the author). A minimal fallback identity is used if no worker is reachable so the
     * flow still functions offline.
     */
    private static WorldIdentity ensureIdentity(Path saveRoot, String world, ShareOptions opts,
                                                Bytes seed) {
        Optional<WorldIdentity> existing = NoderaWorldStore.read(saveRoot);
        // Once a world has an id, that id is the world's name for life — it is pinned and re-signed,
        // never re-derived. Derivation binds the genesis root, and the root is only stable while
        // `nodera-genesis.dat` survives: lose it and it is re-certified from whichever chunks are
        // loaded at the time, which mints a *different* id for the same save. Both then stay
        // announced, each with its own administrator key, and the world is on the network twice.
        // This is the machine that produced ten registry rows for four saves on the author's node.
        //
        // The seed is still sent, because a world being shared for the first time has no id yet and
        // the worker derives from it.
        Bytes pinnedWorldId = existing.map(WorldIdentity::worldId).orElse(null);
        try {
            // A rehosting peer is not the author: re-minting with the local worker's key would
            // derive a DIFFERENT worldId (author is part of the derivation) and silently fork the
            // world's identity on the tracker. Keep the author's record; only the author re-signs.
            if (existing.isPresent() && CompanionLink.isPresent()) {
                String workerNodeId = CompanionLink.client().identity()
                        .map(id -> id.split("\\s+")[0]).orElse("");
                if (!existing.get().authorNodeId().value().toString().equals(workerNodeId)) {
                    return existing.get();
                }
            }
            if (CompanionLink.isPresent()) {
                // Reuse the existing record's manifest ref; the worker re-signs with the current state.
                Bytes manifestRef = existing.map(WorldIdentity::manifestRef).orElse(Bytes.empty());
                long createdAt = existing.map(WorldIdentity::createdAtEpoch)
                        .orElse(System.currentTimeMillis());
                Optional<Bytes> minted = CompanionLink.client().mintWorldIdentity(
                        seed, createdAt, true, opts.listedOnTracker(), opts.encryptionEnabled(),
                        manifestRef, pinnedWorldId);
                if (minted.isPresent()) {
                    WorldIdentity id = WorldIdentity.decode(
                            new dev.nodera.core.crypto.CanonicalReader(minted.get()));
                    NoderaWorldStore.write(saveRoot, id);
                    return id;
                }
            }
            // Offline fallback: keep any existing record (updating its flags is author-only, so we
            // leave it as-is); nothing to persist without the worker's key.
            return existing.orElse(null);
        } catch (IOException | RuntimeException e) {
            // Loud, because of what it costs when it repeats: with no `nodera-world.dat` on disk
            // there is nothing to pin against, so the next share mints another id and the network
            // gains another copy of this world. A world that cannot record its own identity is one
            // launch away from being a duplicate.
            LOG.error("Nodera: could not persist the world identity for '{}': {}. The save cannot "
                    + "remember its network id, so re-sharing it will publish it as a NEW world. "
                    + "Fix the save directory's permissions before sharing again.", world,
                    e.getMessage());
            return existing.orElse(null);
        }
    }

    /**
     * Arm (or disarm) the live-join password gate for the world being shared (L-52).
     *
     * <p>Off the server thread: arming derives the gate key with the same memory-hard KDF the
     * content plane uses, which is deliberately expensive and has no business inside a tick. Until
     * it completes the gate stays disarmed, so the only risk of the asynchrony is a join in the
     * first fraction of a second after a share — and that joiner is the sharer's own client.
     *
     * @param worldId the world's id.
     * @param opts    the share options carrying the password (blank ⇒ disarm).
     * @param world   the world's display name, for the log line.
     */
    static void armGateAsync(Bytes worldId, ShareOptions opts, String world) {
        if (!opts.encryptionEnabled()) {
            HostJoinGate.get().disarm();
            return;
        }
        char[] password = opts.password().toCharArray();
        Thread.ofPlatform().name("nodera-join-gate-arm").daemon()
                .start(() -> armGate(worldId, password, world));
    }

    /**
     * As {@link #armGateAsync}, for a caller that is <b>already</b> off the server thread — the
     * activation bring-up. Synchronous on purpose: arming before the game port is published is what
     * closes the window in which a password-protected world answers a dial with no gate at all, and
     * a window is only closed by finishing the derivation before opening the door.
     *
     * @Thread-context never the server thread; costs one memory-hard KDF.
     */
    private static void armGateNow(Bytes worldId, ShareOptions opts, String world) {
        if (!opts.encryptionEnabled()) {
            HostJoinGate.get().disarm();
            return;
        }
        armGate(worldId, opts.password().toCharArray(), world);
    }

    /** The arming itself, shared by both callers. Wipes {@code password} before returning. */
    private static void armGate(Bytes worldId, char[] password, String world) {
        try {
            HostJoinGate.get().arm(worldId, password);
            LOG.info("Nodera: '{}' is password-gated — joiners must supply the world password",
                    world);
        } catch (RuntimeException e) {
            // Fail CLOSED: a gate that could not be armed must not leave a password-protected
            // world open to anyone who resolves its route, so the world seals instead.
            HostJoinGate.get().seal();
            LOG.error("Nodera: could not arm the join password gate for '{}' ({}) — the world "
                    + "is NOT joinable until you re-share it", world, e.toString());
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}
