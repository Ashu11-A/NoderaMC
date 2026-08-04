package dev.nodera.mod.client.multiplayer;

import dev.nodera.endpoint.client.ClientJoinPasswords;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.control.CompanionLink;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaPeerService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The joiner half of the world-continuity lane (the "host closed the game" answer): when the
 * connection to a Nodera-joined world dies, the world does not — its archive already lives on the
 * peer network. This class watches for the vanilla {@link DisconnectedScreen} after a Nodera join,
 * fetches the world's newest archive through the local worker ({@code NODERA-ARCHIVE}), unpacks it
 * into {@code saves/}, re-opens it as a local world, and lets the Task 33 auto-re-share path put it
 * straight back on the network — the joiner becomes the world's next host. The brief hop through a
 * reconnect is the interim the register already budgets for gateway migration (L-17); the zero-
 * reconnect local-replica view remains Task 16's exit.
 *
 * <p>The trigger is deliberately narrow: only a session that was started by {@link NoderaJoinFlow}
 * arms it, and only an abnormal disconnect (the {@link DisconnectedScreen}) fires it — a voluntary
 * "Disconnect" from the pause menu never shows that screen, so quitting on purpose never rehosts.
 *
 * <p>Thread-context: event handlers + screen on the render thread; the fetch/unpack runs on a
 * dedicated background thread and marshals back via {@link Minecraft#execute}.
 */
public final class NoderaContinuity {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaContinuity");

    /** The Nodera world the current vanilla session was joined into, or {@code null}. */
    private static volatile JoinedWorld joined;

    /** One rehost attempt per disconnect; re-armed by the next Nodera join. */
    private static volatile boolean rehosting;

    private NoderaContinuity() {
    }

    /** A Nodera join in flight/active: worldId + display name (the rehost fetch key). */
    record JoinedWorld(String worldIdHex, String name) {
    }

    /**
     * Arm continuity for this session — called by {@link NoderaJoinFlow} when connecting, and by
     * the session payload on login (covers direct-IP / quick-play joins into a Nodera world). The
     * hosting player also receives the payload, but a host closing its own integrated server
     * never sees a {@link DisconnectedScreen}, so a host can never rehost its own world here.
     */
    public static void onJoining(String worldIdHex, String worldName) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return;
        }
        // The hosting JVM must NEVER arm recovery for its own world: a local-connection hiccup
        // would otherwise "recover" a world this process still hosts — reopening it, kicking every
        // joiner, and cascading (observed live as a reopen/kick feedback loop).
        if (NoderaPeerService.get().isHosting()) {
            return;
        }
        joined = new JoinedWorld(worldIdHex, worldName);
        rehosting = false;
        // Tell THIS machine's worker what its player is doing. Two things follow, and neither used
        // to happen: the world enters the worker's sweep set — so a joiner supports the world it is
        // playing in — and the companion app finally has a row for it. The verb existed on both
        // sides for months and nothing ever sent it, which is why a joiner's app showed an empty
        // "Worlds" screen to somebody standing in a world.
        renewConnected();
        // Hot standby: pull the world archive NOW, while the session is healthy, so a host loss
        // needs no download phase — the local worker already holds every piece and recovery
        // collapses to the world-open. Re-fetches ride new seeded versions; failures are silent
        // (the on-loss fetch remains the fallback).
        if (CompanionLink.isPresent()) {
            Thread.ofPlatform().name("nodera-standby-prefetch").daemon().start(() -> {
                try {
                    java.nio.file.Path fetchDir =
                            Minecraft.getInstance().gameDirectory.toPath().resolve("nodera/fetch");
                    java.nio.file.Files.createDirectories(fetchDir);
                    CompanionLink.client().fetchArchive(worldIdHex,
                            fetchDir.resolve(worldIdHex.substring(0, 12) + ".nar"), 300);
                    LOG.info("standby prefetch complete for '{}' — recovery needs no download",
                            worldName);
                } catch (Exception ignored) {
                    // standby is best-effort; the on-loss fetch is the fallback
                }
            });
        }
    }

    /** Disarm (deliberate leave, rehost done, or the player declined). */
    static void disarm() {
        JoinedWorld leaving = joined;
        joined = null;
        // Cleared with it: `rehosting` is the "one recovery at a time" latch, and leaving it set
        // after a recovery ended meant the next disconnect in the same session was ignored — the
        // flag was only ever reset by arming a new join.
        rehosting = false;
        // A clean goodbye, so the app stops saying "playing" the moment the player stops rather
        // than when the lease runs out. Best-effort by construction — the lease is what makes this
        // an optimisation instead of a correctness requirement.
        if (leaving != null && CompanionLink.isPresent()) {
            Thread.ofPlatform().name("nodera-leave").daemon().start(
                    () -> CompanionLink.client().leaveWorld(leaving.worldIdHex()));
        }
    }

    /**
     * Renew the worker's "a player here is in this world" lease, off the render thread.
     *
     * <p>Scheduled rather than sent once: the worker's claim expires on its own, which is what
     * makes a crashed or force-quit game stop being reported as connected. The cost of that is
     * having to say so repeatedly, and this is where.
     */
    private static void renewConnected() {
        JoinedWorld world = joined;
        if (world == null || !CompanionLink.isPresent()) {
            return;
        }
        // Read on the render thread, sent off it: the client's connection is not safe to touch from
        // a background thread, and the value is a plain int by the time it leaves here.
        int players = playersVisibleHere();
        RENEWALS.execute(() -> {
            JoinedWorld current = joined;
            if (current != null) {
                CompanionLink.client().joinWorld(current.worldIdHex(), current.name(), players);
            }
        });
    }

    /**
     * How many players this client can see in the world it is connected to.
     *
     * <p>The client's own online-player set — the same live roster the server's entity and region
     * lanes plan over, delivered to every client that is in the world. It is the only first-hand
     * answer a joiner has, and reporting it is what stops the app showing "0 players" for a world
     * the player is standing in with other people: the count used to be published solely by the
     * hosting node, so every other peer answered with the zero it had never been told otherwise.
     *
     * @return the count, or {@code -1} when this client is not connected to anything — which is
     *         "I cannot tell", not "nobody is there".
     */
    private static int playersVisibleHere() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) {
            return -1;
        }
        try {
            return mc.getConnection().getOnlinePlayers().size();
        } catch (RuntimeException e) {
            // A roster read that throws mid-teardown must not stop the lease renewal — losing the
            // lease would take the world out of the app entirely, which is far worse than a count
            // that is briefly unknown.
            return -1;
        }
    }

    /**
     * The renewal cadence. A third of the worker's lease, so one missed round trip — a busy control
     * socket, a stalled tick — cannot make a live session flicker out of the companion app.
     */
    private static final int RENEW_SECONDS = 30;

    /**
     * One daemon thread for both the periodic renewal and the one-shot sends.
     *
     * <p>Single-threaded on purpose: renewals for one session must not overtake each other, and a
     * goodbye must land after the last renewal rather than race it.
     */
    private static final java.util.concurrent.ScheduledExecutorService RENEWALS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nodera-join-lease");
                t.setDaemon(true);
                return t;
            });

    static {
        RENEWALS.scheduleWithFixedDelay(() -> {
            try {
                renewConnected();
            } catch (RuntimeException e) {
                // A renewal that throws must not cancel the schedule — scheduleWithFixedDelay
                // silently stops on an escaping exception, and a lease that stops renewing looks
                // exactly like a player who left.
                LOG.debug("join-lease renewal failed: {}", e.toString());
            }
        }, RENEW_SECONDS, RENEW_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Whether this client may take over from a lost host without the player leaving the world.
     *
     * <p>The four conditions are the whole safety argument for cancelling vanilla's disconnect, and
     * they are written out in {@link SeamlessTakeover}. Kept here because this is the class that
     * knows the answers.
     *
     * @return whether {@link #takeOverLocally} can be called.
     * @Thread-context any thread.
     */
    static boolean canTakeOver() {
        JoinedWorld world = joined;
        if (world == null || rehosting || !NoderaConfig.CONTINUITY_AUTO_REHOST.get()) {
            return false;
        }
        if (!CompanionLink.isPresent() || NoderaPeerService.get().isHosting()) {
            return false;
        }
        // A refused join is not a dead host. The password gate seals the connection during
        // configuration, which produces exactly the same disconnect a host crash produces — and
        // swallowing it means the player is never asked for the password they are missing. What
        // that looked like was a two-minute unescapable wait ending in "no seeder online?", while
        // the seeder was online, seeding, and waiting to be asked.
        if (ClientJoinPasswords.pendingGateWorldId() != null) {
            LOG.info("Nodera continuity: the join to '{}' was refused at the password gate, not "
                    + "lost — leaving the disconnect alone so the password can be entered",
                    world.name());
            return false;
        }
        return true;
    }

    /**
     * The game clients in the session, from the last broadcast lane plan.
     *
     * <p>Only these are candidates to take a world over. The mesh's membership additionally
     * contains every always-on worker, and a worker cannot open a Minecraft world — electing one is
     * how a live run ended with nobody hosting and both players waiting.
     */
    private static volatile java.util.List<dev.nodera.protocol.membership.PeerEntry> planMembers =
            java.util.List.of();

    /**
     * Remember which peers are players.
     *
     * @param plan the plan the session just broadcast.
     * @Thread-context client thread.
     */
    public static void onLanePlan(dev.nodera.endpoint.lane.LanePlan plan) {
        if (plan == null) {
            return;
        }
        java.util.List<dev.nodera.protocol.membership.PeerEntry> members = new java.util.ArrayList<>();
        for (dev.nodera.endpoint.lane.LanePlan.Member member : plan.members()) {
            try {
                members.add(new dev.nodera.protocol.membership.PeerEntry(
                        new dev.nodera.core.identity.NodeId(
                                java.util.UUID.fromString(member.nodeIdUuid())),
                        member.route() == null ? "" : member.route(),
                        dev.nodera.core.identity.NodeCapabilities.initial(), false));
            } catch (RuntimeException malformed) {
                // A member this client cannot even name is a member it cannot elect.
                LOG.debug("lane plan member ignored for succession: {}", malformed.toString());
            }
        }
        planMembers = java.util.List.copyOf(members);
    }

    /**
     * Whether THIS node is the one peer that should re-open the world.
     *
     * <h2>Why this check has to exist</h2>
     *
     * <p>Everything in {@link #canTakeOver} is local, and local conditions are true on every
     * surviving player at once. So every one of them fetched the archive, unpacked it into their own
     * save directory, opened it and re-shared it — and one world became <i>N</i> divergent worlds all
     * announcing the same id. That is not a handover, and it is what a live session saw as the world
     * being cloned into everybody's singleplayer list.
     *
     * <p>{@link HostSuccession} is a pure function of the membership set, so every peer reaches the
     * same answer at the same moment with no round trip. A node that cannot see the membership
     * answers <b>false</b> and waits: two winners cost two worlds, and no winner costs a wait.
     *
     * @return whether to open the world here.
     * @Thread-context any thread.
     */
    static boolean isElectedSuccessor() {
        JoinedWorld world = joined;
        if (world == null) {
            return false;
        }
        var runtime = NoderaPeerService.get().clientRuntime();
        var identity = NoderaPeerService.get().clientIdentity();
        if (runtime == null || identity == null) {
            return false;
        }
        try {
            // Elected among the PLAYERS, not among the mesh.
            //
            // The first version of this elected over the raw membership set, and a live run picked
            // a headless worker — which cannot open a Minecraft world at all, so nobody took over
            // and every player sat waiting. The session's always-on peers are members in every
            // sense that matters to the mesh and in no sense that matters here.
            //
            // The broadcast lane plan already draws exactly this line: `members` are the game
            // clients, each with a player position, and `residents` are the workers. Every peer
            // receives the same plan, so electing over it is at least as deterministic as electing
            // over membership and is the only version that can produce a host.
            java.util.List<dev.nodera.protocol.membership.PeerEntry> candidates = planMembers;
            if (candidates.isEmpty()) {
                LOG.info("Nodera continuity: no lane plan has been received, so there is no way to "
                        + "tell which peers are players — not taking over");
                return false;
            }
            // The departing host is identified by the route every joiner dialled to reach it: the
            // one fact about "who is hosting" that every peer holds identically.
            String hostRoute = NoderaPeerService.get().clientBootstrapRoute();
            dev.nodera.core.identity.NodeId departing = null;
            for (dev.nodera.protocol.membership.PeerEntry entry : candidates) {
                if (!hostRoute.isBlank() && hostRoute.equals(entry.route())) {
                    departing = entry.nodeId();
                    break;
                }
            }
            return dev.nodera.endpoint.lane.HostSuccession.isSuccessor(
                    identity.nodeId(), candidates, departing,
                    dev.nodera.endpoint.lane.HostSuccession.epochFor(world.worldIdHex()));
        } catch (RuntimeException unreadable) {
            LOG.info("Nodera continuity: could not read the session membership ({}) — not taking "
                    + "over, because a node that cannot tell whether it won must not act as though "
                    + "it did", unreadable.toString());
            return false;
        }
    }


    /**
     * How long a non-successor waits for the elected peer's endpoint before giving up.
     *
     * <p>Generous, because the successor has to fetch, unpack and open a world before it can
     * publish anything, and a player who waits ninety seconds and reconnects has lost nothing —
     * whereas a player who gives up early lands on a title screen with their world still alive.
     */
    private static final java.time.Duration SUCCESSOR_WAIT = java.time.Duration.ofSeconds(180);

    /** How often the worker is asked whether the world has a game endpoint again. */
    private static final long SUCCESSOR_POLL_MILLIS = 3_000L;

    /**
     * Hold this player where they are and reconnect when the elected successor opens the world.
     *
     * <p>This is the other half of the election: the winner opens the world, and everyone else does
     * <b>this</b> instead of opening their own copy. The player keeps their ghost chunks, is told in
     * chat what is happening, and is reconnected to the one surviving world rather than being handed
     * a private fork of it.
     *
     * <p>The endpoint is read from this machine's own worker rather than from a tracker: the worker
     * is already following the world and already knows when its {@code mc_route} comes back, and
     * asking it costs a loopback line instead of a network round trip per poll.
     *
     * @Thread-context any thread; polls off-thread and reconnects on the client thread.
     */
    static void awaitSuccessor() {
        JoinedWorld world = joined;
        if (world == null) {
            SeamlessTakeover.finish();
            return;
        }
        rehosting = true;
        Minecraft mc = Minecraft.getInstance();
        SeamlessTakeover.say(Component.translatable("nodera.continuity.waiting", world.name()));
        Thread.ofPlatform().name("nodera-await-successor").daemon().start(() -> {
            long deadline = System.nanoTime() + SUCCESSOR_WAIT.toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    String route = liveRouteOf(world.worldIdHex());
                    if (route != null && !route.isBlank()) {
                        LOG.info("Nodera continuity: '{}' is being hosted again at {} — "
                                + "reconnecting", world.name(), route);
                        disarm();
                        mc.execute(() -> {
                            try {
                                NoderaJoinFlow.reconnect(world.name(), route, world.worldIdHex());
                            } finally {
                                SeamlessTakeover.finish();
                            }
                        });
                        return;
                    }
                    Thread.sleep(SUCCESSOR_POLL_MILLIS);
                }
                LOG.info("Nodera continuity: nobody re-opened '{}' within {}s", world.name(),
                        SUCCESSOR_WAIT.toSeconds());
                SeamlessTakeover.say(Component.translatable("nodera.continuity.waiting.failed",
                        world.name()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.warn("Nodera continuity: waiting for a successor failed: {}", e.toString());
            } finally {
                SeamlessTakeover.finish();
            }
        });
    }

    /**
     * The world's current game endpoint according to this machine's worker, or {@code null}.
     *
     * @param worldIdHex the world to ask about.
     * @Thread-context any thread; blocks on a loopback exchange.
     */
    private static String liveRouteOf(String worldIdHex) {
        if (!CompanionLink.isPresent()) {
            return null;
        }
        try {
            String state = CompanionLink.client().state().orElse("");
            if (state.isBlank()) {
                return null;
            }
            for (dev.nodera.endpoint.state.WorkerStateParser.HostedWorldInfo info
                    : dev.nodera.endpoint.state.WorkerStateParser.connectedWorlds(state)) {
                if (worldIdHex.equalsIgnoreCase(info.worldId()) && !info.mcRoute().isBlank()) {
                    return info.mcRoute();
                }
            }
            return null;
        } catch (RuntimeException unreachable) {
            return null;
        }
    }

    /**
     * Re-open the world locally while the player stays where they are.
     *
     * <p>The client is already holding the chunks around the player and, thanks to the standby
     * prefetch armed at join time, its worker is usually already holding the world's archive. So
     * this is a fetch that mostly does not fetch, an unpack, and a world-open — with the player
     * looking at their own ghost chunks the entire time and told what is happening in chat rather
     * than on a screen they cannot leave.
     *
     * @Thread-context any thread; all heavy work is moved off the caller's.
     */
    static void takeOverLocally() {
        JoinedWorld world = joined;
        if (world == null) {
            SeamlessTakeover.finish();
            return;
        }
        rehosting = true;
        Minecraft mc = Minecraft.getInstance();
        SeamlessTakeover.say(Component.translatable("nodera.continuity.holding", world.name()));
        Thread.ofPlatform().name("nodera-takeover").daemon().start(() -> {
            try {
                String dirName = materialize(mc, world);
                if (dirName == null) {
                    // Nothing to open. Releasing the flag matters as much as the chat line: a
                    // stranded takeover used to leave every later screen suppressed, so the player
                    // had no route to the menu either.
                    SeamlessTakeover.finish();
                    return;
                }
                disarm();
                // The flag is cleared INSIDE this lambda, not in a finally on this thread.
                // `mc.execute` from a background thread only QUEUES the work, so a finally here
                // ran nanoseconds later and set the flag false before the open flow had shown its
                // first screen — which is why the suppression never suppressed anything and the
                // player watched the full world-load sequence.
                mc.execute(() -> {
                    try {
                        mc.createWorldOpenFlows().openWorld(dirName, () -> {
                            // The open failed after the level was already being replaced, so there
                            // is no ghost left to hold and nothing to do but say so honestly.
                            LOG.warn("Nodera continuity: could not open the restored world");
                            mc.setScreen(new TitleScreen());
                        });
                    } finally {
                        SeamlessTakeover.finish();
                    }
                });
            } catch (Exception e) {
                LOG.warn("Nodera continuity: local takeover failed: {}", e.toString());
                SeamlessTakeover.say(Component.translatable("nodera.continuity.holding.failed",
                        e.toString()));
                SeamlessTakeover.finish();
            }
        });
    }

    /**
     * Get the world's files onto this disk under a stable name.
     *
     * @return the save directory name, or {@code null} if the world could not be materialised.
     */
    private static String materialize(Minecraft mc, JoinedWorld world) throws java.io.IOException {
        Path fetchDir = mc.gameDirectory.toPath().resolve("nodera/fetch");
        Files.createDirectories(fetchDir);
        Path archiveFile = fetchDir.resolve(world.worldIdHex().substring(0, 12) + ".nar");
        StringBuilder reason = new StringBuilder();
        Optional<String> fetched = CompanionLink.client().fetchArchive(
                world.worldIdHex(), archiveFile,
                NoderaConfig.CONTINUITY_FETCH_TIMEOUT_SECONDS.get(), reason,
                (verified, total) -> SeamlessTakeover.say(Component.translatable(
                        "nodera.continuity.holding.progress", verified, total)));
        if (fetched.isEmpty()) {
            // The worker's own words, verbatim. Two guesses have been printed here and both were
            // wrong in front of a user: "no seeder online?" while a seeder was seeding forty
            // pieces, then "this world is password protected" for a world shared with encryption
            // off. A cause this code cannot observe is a cause it must not name.
            SeamlessTakeover.say(Component.translatable("nodera.continuity.holding.failed",
                    reason.isEmpty() ? "the peer worker could not fetch it" : reason.toString()));
            return null;
        }
        byte[] blob = Files.readAllBytes(archiveFile);
        String dirName = rehostDirName(world);
        Path saveDir = mc.gameDirectory.toPath().resolve("saves").resolve(dirName);
        // Issue #43 freshness guard: never let a STALE network archive overwrite a newer local save
        // of the same world. The fetch reply is "<bytes> <version>"; the local save records the
        // version it last seeded. Older-or-equal network copy + existing local save ⇒ open the
        // local save untouched (it is at least as fresh).
        long fetchedVersion = parseFetchedVersion(fetched.get());
        long localVersion = dev.nodera.mod.common.WorldArchiver.seededVersion(saveDir);
        // A blob the worker could not date is a blob nothing should be overwritten with. The reply
        // now carries the version of the BYTES rather than the newest version heard of, so -1 means
        // "these bytes match no manifest I hold" — which is exactly the case where trusting them
        // would replace a good save with an unknown one.
        if (fetchedVersion < 0 && Files.isDirectory(saveDir)) {
            LOG.warn("Nodera continuity: the fetched archive for '{}' could not be dated — keeping "
                    + "the local save rather than overwriting it with bytes of unknown age",
                    world.name());
            return dirName;
        }
        if (Files.isDirectory(saveDir) && localVersion >= 0 && fetchedVersion >= 0
                && fetchedVersion <= localVersion) {
            LOG.info("Nodera continuity: network archive v{} is not newer than local save v{} — "
                    + "opening the local save unchanged", fetchedVersion, localVersion);
        } else {
            WorldArchive.unpackInto(blob, saveDir);
        }
        LOG.info("Nodera continuity: '{}' restored to saves/{} ({} bytes, {})",
                world.name(), dirName, blob.length, fetched.get());
        return dirName;
    }

    /**
     * The network-first entry (no host-and-client assumption): materialize a world <i>from the peer
     * network</i> and open it locally, becoming one of its hosts. Used by the join flow for a world
     * whose author/host is offline — the world's files live on the Nodera network, so "no live game
     * endpoint" is a fetch, not a dead end.
     *
     * <p>Unlike the disconnect path there is no world to stand in yet, so this one does show
     * vanilla's ordinary world-loading progress: there are no ghost chunks to render, and a player
     * who pressed "join" is expecting something to happen.
     *
     * @param worldIdHex the world to materialize.
     * @param worldName  its display name.
     * @return whether the flow started (worker present + id known).
     * @Thread-context render thread.
     */
    public static boolean openFromNetwork(String worldIdHex, String worldName) {
        if (worldIdHex == null || worldIdHex.isBlank() || !CompanionLink.isPresent()) {
            return false;
        }
        rehosting = true;
        JoinedWorld world = new JoinedWorld(worldIdHex, worldName);
        joined = world;
        LOG.info("Nodera: materializing world '{}' from the peer network", worldName);
        Minecraft mc = Minecraft.getInstance();
        Thread.ofPlatform().name("nodera-materialize").daemon().start(() -> {
            try {
                String dirName = materialize(mc, world);
                if (dirName == null) {
                    mc.execute(() -> mc.setScreen(new TitleScreen()));
                    return;
                }
                disarm();
                mc.execute(() -> mc.createWorldOpenFlows().openWorld(dirName, () -> {
                    LOG.warn("Nodera: could not open the materialized world");
                    mc.setScreen(new TitleScreen());
                }));
            } catch (Exception e) {
                LOG.warn("Nodera: could not materialize '{}': {}", worldName, e.toString());
                mc.execute(() -> mc.setScreen(new TitleScreen()));
            }
        });
        return true;
    }

    /** Parse the archive version out of a fetch reply ({@code "<bytes> <version>"}); -1 unknown. */
    static long parseFetchedVersion(String fetchReply) {
        try {
            String[] parts = fetchReply.trim().split("\\s+");
            return parts.length >= 2 ? Long.parseLong(parts[1]) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The stable local save-folder name for a rehosted world (per-world, collision-free). */
    static String rehostDirName(JoinedWorld world) {
        String suffix = world.worldIdHex().substring(0, Math.min(8, world.worldIdHex().length()));
        String base = world.name() == null || world.name().isBlank() ? "Nodera World"
                : world.name().replaceAll("[\\\\/:*?\"<>|]", "_");
        return base + " [" + suffix.toLowerCase(Locale.ROOT) + "]";
    }

}
