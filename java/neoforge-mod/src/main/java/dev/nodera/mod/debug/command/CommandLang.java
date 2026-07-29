package dev.nodera.mod.debug.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every translation key the {@code /nodera} and {@code /noderac} command trees can put in front of
 * a player, with the number of arguments each one takes (MC-GUI-5).
 *
 * <p><b>Why a registry and not bare constants.</b> The commands are the one surface {@code #113}
 * left assembling English: a reply was a {@code StringBuilder} handed to
 * {@code Component.literal}, so no lang file could reach it and no resource pack could change it.
 * Moving them to {@code Component.translatable} is only half the fix — a key with the wrong number
 * of arguments renders as a broken sentence at runtime and nowhere else. Recording the arity here,
 * beside the key, is what lets {@code CommandLangCoverageTest} check every key against
 * {@code en_us.json} on the gate: the class is Minecraft-free on purpose so the gate can load it.
 *
 * <p>Command classes reference these constants; {@code CommandsHaveNoHardcodedEnglishTest} fails
 * the build if one of them builds a sentence instead.
 *
 * @Thread-context immutable after class initialisation; any thread.
 */
public final class CommandLang {

    /** key → the number of arguments the command supplies for it. Declared first: fields below fill it. */
    private static final Map<String, Integer> ARITY = new LinkedHashMap<>();

    private CommandLang() {}

    private static String key(String id, int args) {
        ARITY.put(id, args);
        return id;
    }

    /** @return every registered key with the argument count its call site supplies. */
    public static Map<String, Integer> registry() {
        return Map.copyOf(ARITY);
    }

    // ---- shared -----------------------------------------------------------------------------

    /** No diagnostics snapshot yet (runtime not started). */
    public static final String OFFLINE = key("nodera.cmd.offline", 0);
    /** Generic on/off words, so a toggle reply is one key rather than two. */
    public static final String ON = key("nodera.cmd.state.on", 0);
    /** @see #ON */
    public static final String OFF = key("nodera.cmd.state.off", 0);

    // ---- /tps -------------------------------------------------------------------------------

    public static final String TPS_TITLE = key("nodera.cmd.tps.title", 0);
    /** {@code %s} = TPS, {@code %s} = average tick milliseconds. */
    public static final String TPS_HEADER = key("nodera.cmd.tps.header", 2);
    /** {@code %s} = player name, {@code %s} = latency in ms. */
    public static final String TPS_PING = key("nodera.cmd.tps.ping", 2);

    // ---- /nodera share ----------------------------------------------------------------------

    /** {@code %s} = world name. */
    public static final String SHARE_STARTED = key("nodera.cmd.share.started", 1);
    /** {@code %s} = world name. */
    public static final String SHARE_STOPPED = key("nodera.cmd.share.stopped", 1);
    public static final String SHARE_NOT_SHARING = key("nodera.cmd.share.not_sharing", 0);
    public static final String SHARE_NOT_AUTHOR = key("nodera.cmd.share.not_author", 0);
    public static final String SHARE_SAME_PASSWORD = key("nodera.cmd.share.same_password", 0);
    /** {@code %s} = world name. */
    public static final String SHARE_REKEYING = key("nodera.cmd.share.rekeying", 1);
    public static final String SHARE_STATUS_TITLE = key("nodera.cmd.share.status.title", 0);
    /** {@code %s} = the P2P route. */
    public static final String SHARE_STATUS_P2P = key("nodera.cmd.share.status.p2p", 1);
    /** {@code %s} = the game endpoint (reused from the Share screen). */
    public static final String SHARE_STATUS_ROUTE = key("nodera.share.status.route", 1);
    public static final String SHARE_STATUS_NO_ROUTE = key("nodera.share.status.offline", 0);
    public static final String SHARE_STATUS_WORKER_ON = key("nodera.share.status.worker.on", 0);
    public static final String SHARE_STATUS_WORKER_OFF = key("nodera.share.status.worker.off", 0);

    // ---- /nodera telemetry ------------------------------------------------------------------

    public static final String TELEMETRY_TITLE = key("nodera.cmd.telemetry.title", 0);
    /** {@code %s} = one of the three state phrases below. */
    public static final String TELEMETRY_STATE = key("nodera.cmd.telemetry.state", 1);
    public static final String TELEMETRY_GRANTED = key("nodera.cmd.telemetry.granted", 0);
    public static final String TELEMETRY_DENIED = key("nodera.cmd.telemetry.denied", 0);
    public static final String TELEMETRY_UNANSWERED = key("nodera.cmd.telemetry.unanswered", 0);
    public static final String TELEMETRY_SHARED = key("nodera.cmd.telemetry.shared", 0);
    public static final String TELEMETRY_CHANGE = key("nodera.cmd.telemetry.change", 0);
    /** {@code %s} = the reason the node gave. */
    public static final String TELEMETRY_ERROR = key("nodera.cmd.telemetry.error", 1);
    public static final String TELEMETRY_ON = key("nodera.cmd.telemetry.on", 0);
    public static final String TELEMETRY_OFF = key("nodera.cmd.telemetry.off", 0);

    // ---- /nodera worlds and /noderac worlds -------------------------------------------------

    public static final String WORLDS_TITLE = key("nodera.cmd.worlds.title", 0);
    public static final String WORLDS_NONE_LISTED = key("nodera.cmd.worlds.none_listed", 0);
    public static final String WORLDS_NONE_KNOWN = key("nodera.cmd.worlds.none_known", 0);
    /** {@code %s} = peer count (peers, not players: a tracker only sees announcers). */
    public static final String WORLDS_PEERS = key("nodera.cmd.worlds.peers", 1);
    /** {@code %s} = host name (reused from the multiplayer world rows). */
    public static final String WORLDS_BY_HOST = key("nodera.multiplayer.row.by", 1);
    public static final String WORLD_JOINABLE = key("nodera.cmd.world.joinable", 0);
    /** {@code %s} = the game endpoint. */
    public static final String WORLD_JOINABLE_AT = key("nodera.cmd.world.joinable_at", 1);
    public static final String WORLD_GAME_CLOSED = key("nodera.cmd.world.game_closed", 0);

    // ---- /noderac worker --------------------------------------------------------------------

    public static final String WORKER_ABSENT = key("nodera.cmd.worker.absent", 0);
    public static final String WORKER_TITLE = key("nodera.cmd.worker.title", 0);
    /** {@code %s} = control protocol version, {@code %s} = daemon version. */
    public static final String WORKER_LINKED = key("nodera.cmd.worker.linked", 2);
    public static final String WORKER_NO_WORLDS = key("nodera.cmd.worker.no_worlds", 0);

    // ---- /nodera whois, op, deop ------------------------------------------------------------

    /** {@code %s} = target player name. */
    public static final String WHOIS_STAGED = key("nodera.cmd.whois.staged", 1);
    public static final String GRANT_NOT_SHARED = key("nodera.cmd.grant.not_shared", 0);
    public static final String GRANT_UNAUTHORIZED = key("nodera.cmd.grant.unauthorized", 0);
    /** {@code %s} = target player name. */
    public static final String GRANT_NO_IDENTITY = key("nodera.cmd.grant.no_identity", 1);
    public static final String GRANT_NO_WORKER = key("nodera.cmd.grant.no_worker", 0);
    public static final String GRANT_DECLINED = key("nodera.cmd.grant.declined", 0);
    public static final String GRANT_MALFORMED = key("nodera.cmd.grant.malformed", 0);
    public static final String GRANT_REJECTED = key("nodera.cmd.grant.rejected", 0);
    /** {@code %s} = target player name. */
    public static final String GRANT_OPPED = key("nodera.cmd.grant.opped", 1);
    /** {@code %s} = target player name. */
    public static final String GRANT_DEOPPED = key("nodera.cmd.grant.deopped", 1);

    // ---- /nodera hud ------------------------------------------------------------------------

    public static final String HUD_NOT_PLAYER = key("nodera.cmd.hud.not_player", 0);
    /** {@code %s} = the surface, {@code %s} = on/off. */
    public static final String HUD_SET = key("nodera.cmd.hud.set", 2);
    /** Prefix completed by the lower-cased {@code Surface} name; see {@link #hudSurfaceKey}. */
    public static final String HUD_SURFACE_PREFIX = "nodera.cmd.hud.surface.";
    /**
     * The four surfaces, registered by hand rather than derived: {@code Surface} lives on a class
     * that touches Minecraft, so the gate cannot enumerate it — and an unregistered key is a key
     * nothing checks.
     */
    public static final String HUD_SURFACE_TAB = key(HUD_SURFACE_PREFIX + "tab", 0);
    /** @see #HUD_SURFACE_TAB */
    public static final String HUD_SURFACE_BARS = key(HUD_SURFACE_PREFIX + "bars", 0);
    /** @see #HUD_SURFACE_TAB */
    public static final String HUD_SURFACE_ALERTS = key(HUD_SURFACE_PREFIX + "alerts", 0);
    /** @see #HUD_SURFACE_TAB */
    public static final String HUD_SURFACE_ALL = key(HUD_SURFACE_PREFIX + "all", 0);

    /** @return the lang key naming one HUD surface. */
    public static String hudSurfaceKey(String surfaceName) {
        return HUD_SURFACE_PREFIX + surfaceName.toLowerCase(java.util.Locale.ROOT);
    }

    // ---- /nodera debug ----------------------------------------------------------------------

    /** {@code %s} = ticks. */
    public static final String DEBUG_SAMPLE_RATE = key("nodera.cmd.debug.sample_rate", 1);
    public static final String DEBUG_VERBOSE_ON = key("nodera.cmd.debug.verbose.on", 0);
    public static final String DEBUG_VERBOSE_OFF = key("nodera.cmd.debug.verbose.off", 0);
    public static final String DRIVE_NOT_PLAYER = key("nodera.cmd.debug.drive.not_player", 0);
    public static final String DRIVE_NO_LANE = key("nodera.cmd.debug.drive.no_lane", 0);
    /** {@code %s} = edits driven, {@code %s} = edits the lane accepted. */
    public static final String DRIVE_DONE = key("nodera.cmd.debug.drive.done", 2);
    public static final String CAPTURE_TITLE = key("nodera.cmd.debug.capture.title", 0);
    /** {@code %s} = edits seen. */
    public static final String CAPTURE_TOTAL = key("nodera.cmd.debug.capture.total", 1);
    /** {@code %s} = outcome reason, {@code %s} = count. */
    public static final String CAPTURE_REASON = key("nodera.cmd.debug.capture.reason", 2);
    public static final String CAPTURE_NONE = key("nodera.cmd.debug.capture.none", 0);
    public static final String EXTRACT_NOT_PLAYER = key("nodera.cmd.debug.extract.not_player", 0);
    public static final String EXTRACT_TITLE = key("nodera.cmd.debug.extract.title", 0);
    /** {@code %s} = region, {@code %s} = milliseconds. */
    public static final String EXTRACT_REGION = key("nodera.cmd.debug.extract.region", 2);
    /** {@code %s} = chunks read, {@code %s} = chunks not resident. */
    public static final String EXTRACT_CHUNKS = key("nodera.cmd.debug.extract.chunks", 2);
    /** {@code %s} = dense section count. */
    public static final String EXTRACT_DENSE = key("nodera.cmd.debug.extract.dense", 1);
    /** {@code %s} = blocks the palette cannot express. */
    public static final String EXTRACT_EXCLUDED = key("nodera.cmd.debug.extract.excluded", 1);
    /** {@code %s} = changed blocks, {@code %s} = changed sections. */
    public static final String EXTRACT_DRIFT = key("nodera.cmd.debug.extract.drift", 2);
    public static final String EXTRACT_NO_COMMITTED = key("nodera.cmd.debug.extract.no_committed", 0);
    public static final String RELAY_TITLE = key("nodera.cmd.debug.relay.title", 0);
    public static final String RELAY_NONE = key("nodera.cmd.debug.relay.none", 0);

    // ---- /nodera selftest -------------------------------------------------------------------

    public static final String SELFTEST_NO_PLAYERS = key("nodera.cmd.selftest.no_players", 0);
    /** {@code %s} = the I/O error. */
    public static final String SELFTEST_WRITE_FAILED = key("nodera.cmd.selftest.write_failed", 1);
    /**
     * The player-facing run summary: ok, zero, syntax errors, exceptions, skipped, commands,
     * players, duration ms, report path. The {@code SELFTEST complete:} log line keeps its exact
     * machine-readable shape — {@code scripts/e2e-commands.sh} greps it.
     */
    public static final String SELFTEST_SUMMARY = key("nodera.cmd.selftest.summary", 9);
}
