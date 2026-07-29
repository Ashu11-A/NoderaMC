package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * THE UNMODIFIED CLIENT of the endpoint scenarios — a Minecraft-protocol client with no Minecraft.
 *
 * <p>Handshake, offline-mode login, the configuration phase, and enough of the play phase to stay
 * connected, run a command, drop an item, and report what happened. This is the Java port of
 * {@code scripts/lib/vanilla-bot.py}, whose reasoning is reproduced here because it is the whole
 * justification for the class existing at all.
 *
 * <h2>Why this instead of a second launched game</h2>
 *
 * <ul>
 *   <li>a vanilla client needs a Mojang launcher, an asset index and an account, none of which
 *       belong in CI;</li>
 *   <li>two full Minecraft clients + a Paper server + three workers on one runner is a memory
 *       problem, not a test;</li>
 *   <li>and it buys nothing, because every assertion in these scenarios comes from a log line or a
 *       control socket. Nothing is ever read off a screen.</li>
 * </ul>
 *
 * <p>It is in fact MORE faithful to the thing under test: it proves the endpoint is reachable by
 * ANYTHING that speaks the protocol, with no NeoForge anywhere in the process.
 *
 * <h2>The evidence contract</h2>
 *
 * <p>The shell suites grep these lines and nothing else, so they are still written verbatim to the
 * bot's own log file — an old-style run and a new one leave the same evidence behind — and are also
 * returned from {@link #evidence()} for an assertion that does not want to read a file:
 *
 * <pre>
 *   BOT: connected &lt;host&gt;:&lt;port&gt;
 *   BOT: logged-in &lt;uuid&gt;
 *   BOT: joined
 *   BOT: sent &lt;what&gt;
 *   BOT: dropped
 *   BOT: disconnected &lt;reason&gt;
 *   BOT: failed &lt;reason&gt;
 * </pre>
 *
 * <h2>Packet ids</h2>
 *
 * <p>Every id lives in {@link #PROTOCOL_767}, keyed by protocol version. They are the one part of
 * this class that goes stale on a Minecraft update, so they are in ONE table rather than sprinkled
 * through the code, and an id the bot does not recognise is IGNORED (which is what a real client
 * does) rather than fatal. Source of truth: https://minecraft.wiki/w/Java_Edition_protocol
 *
 * <p>Thread-context: one bot per connection, driven from the scenario's thread; the socket is not
 * shared. {@link #close} is idempotent.
 */
public final class ServerVanillaBot implements AutoCloseable {

    /** 767 = Minecraft 1.21.1, the version the mod pins (docs/README.md §4.6). */
    public static final Map<String, Integer> PROTOCOL_767 = Map.ofEntries(
            // serverbound
            Map.entry("sb_handshake", 0x00),
            Map.entry("sb_login_start", 0x00),
            Map.entry("sb_login_ack", 0x03),
            Map.entry("sb_cfg_client_information", 0x00),
            Map.entry("sb_cfg_plugin_message", 0x02),
            Map.entry("sb_cfg_finish_ack", 0x03),
            Map.entry("sb_cfg_keep_alive", 0x04),
            Map.entry("sb_cfg_pong", 0x05),
            Map.entry("sb_cfg_known_packs", 0x07),
            Map.entry("sb_play_confirm_teleport", 0x00),
            Map.entry("sb_play_chat_command", 0x04),
            Map.entry("sb_play_chat_message", 0x06),
            Map.entry("sb_play_keep_alive", 0x18),
            Map.entry("sb_play_position", 0x1A),
            Map.entry("sb_play_player_action", 0x24),
            // clientbound
            Map.entry("cb_login_disconnect", 0x00),
            Map.entry("cb_login_encryption", 0x01),
            Map.entry("cb_login_success", 0x02),
            Map.entry("cb_login_compression", 0x03),
            Map.entry("cb_cfg_disconnect", 0x02),
            Map.entry("cb_cfg_finish", 0x03),
            Map.entry("cb_cfg_keep_alive", 0x04),
            Map.entry("cb_cfg_ping", 0x05),
            Map.entry("cb_cfg_known_packs", 0x0E),
            Map.entry("cb_play_login", 0x2B),
            Map.entry("cb_play_disconnect", 0x1D),
            Map.entry("cb_play_keep_alive", 0x26),
            Map.entry("cb_play_sync_position", 0x40));

    /**
     * The server refused us.
     *
     * <p>The python driver signalled this with exit code 3, which one scenario stage EXPECTS
     * ({@code --expect-refused}) and every other treats as a failure. Keeping it a distinct type is
     * what lets a caller say which of the two it meant.
     */
    public static final class BotRefused extends HarnessException {
        private static final long serialVersionUID = 1L;

        BotRefused(String message) {
            super(message);
        }
    }

    /** A failure the scenario should report: never reached PLAY, or an unexpected close. */
    public static final class BotFailed extends HarnessException {
        private static final long serialVersionUID = 1L;

        BotFailed(String message) {
            super(message);
        }
    }

    private final String host;
    private final int port;
    private final String name;
    private final int protocol;
    private final Duration timeout;
    private final Path logFile;
    private final Map<String, Integer> ids;
    private final List<String> evidence = new ArrayList<>();

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private byte[] buffer = new byte[0];
    private int bufferOffset;
    private int threshold = -1;   // < 0 until the server sends SetCompression
    private boolean joined;

    /**
     * @param host     the server to dial.
     * @param port     the game port.
     * @param name     the player name the server sees.
     * @param protocol the protocol version; only {@link #PROTOCOL_767} is tabled today.
     * @param timeout  the socket timeout, and the default window a {@code wait} step gets.
     * @param logFile  where the evidence lines are appended, so the old greps still work.
     */
    public ServerVanillaBot(String host, int port, String name, int protocol, Duration timeout,
                            Path logFile) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.protocol = protocol;
        this.timeout = timeout;
        this.logFile = logFile;
        if (protocol != 767) {
            throw new BotFailed("no packet table for protocol " + protocol
                    + " — add one to ServerVanillaBot.PROTOCOL_*");
        }
        this.ids = PROTOCOL_767;
        // HARNESS-GAP: the python driver honoured NODERA_VANILLA_CLIENT by exec'ing a real vanilla
        // client with the same arguments. Nothing here does that: a scenario that wants a real
        // client must launch it itself, because this class IS the in-process client.
    }

    /** Every evidence line this bot has emitted, in order. */
    public List<String> evidence() {
        return List.copyOf(evidence);
    }

    /** @return {@code true} if the bot ever reached the world (the position sync arrived). */
    public boolean joined() {
        return joined;
    }

    // ---------------------------------------------------------------------------------------
    // The driver
    // ---------------------------------------------------------------------------------------

    /**
     * Connect, log in, configure, and run a script — the equivalent of the python driver's
     * {@code main()}.
     *
     * @param script steps: {@code wait:joined}, {@code sleep:<s>}, {@code hold:<s>},
     *               {@code chat:<text>}, {@code cmd:<command without the leading slash>},
     *               {@code drop}, {@code quit}.
     * @throws BotFailed  when the run should be reported as a failure.
     * @throws BotRefused when the server closed us during login or configuration.
     */
    public void run(List<String> script) {
        connect();
        login();
        configuration();
        for (String step : script) {
            int colon = step.indexOf(':');
            String verb = colon < 0 ? step : step.substring(0, colon);
            String argument = colon < 0 ? "" : step.substring(colon + 1);
            switch (verb) {
                case "wait" -> {
                    if (!"joined".equals(argument)) {
                        throw new BotFailed("unknown script step '" + step + "'");
                    }
                    if (!pump(timeout)) {
                        die("closed before reaching the world");
                    }
                }
                case "sleep", "hold" -> {
                    double seconds = argument.isBlank() ? 5 : Double.parseDouble(argument);
                    if (!pump(Duration.ofMillis((long) (seconds * 1000)))) {
                        die("closed during " + verb);
                    }
                }
                case "chat" -> {
                    sendChat(argument);
                    say("sent chat");
                }
                case "cmd" -> {
                    sendCommand(argument);
                    say("sent command");
                }
                case "drop" -> {
                    dropHeld();
                    say("dropped");
                }
                case "quit" -> {
                    close();
                    return;
                }
                default -> throw new BotFailed("unknown script step '" + step + "'");
            }
            // Keep the connection serviced between steps so a keep-alive is never missed.
            pump(Duration.ofSeconds(1));
        }
        close();
    }

    /**
     * The server MUST refuse us; reaching PLAY is the failure.
     *
     * <p>Gives the server a generous window to close us, exactly as {@code --expect-refused} did.
     *
     * @throws BotFailed if the server admitted us instead.
     */
    public void expectRefused() {
        connect();
        try {
            login();
            configuration();
        } catch (BotRefused refusedDuringHandshake) {
            return;
        }
        if (pump(timeout)) {
            die("the server ADMITTED us — it was expected to refuse");
        }
    }

    // ---------------------------------------------------------------------------------------
    // The three protocol phases
    // ---------------------------------------------------------------------------------------

    /** Open the socket and say so. */
    public void connect() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            out = socket.getOutputStream();
            in = socket.getInputStream();
        } catch (IOException e) {
            die("cannot reach " + host + ":" + port + " (" + e + ")");
        }
        say("connected " + host + ":" + port);
    }

    /**
     * Handshake + offline-mode login.
     *
     * @return the UUID the server assigned.
     */
    public String login() {
        try {
            send(ids.get("sb_handshake"), concat(varInt(protocol), mcString(host),
                    shortBe(port), varInt(2)));
            // Offline mode: the UUID is the server's business, but the field is required.
            send(ids.get("sb_login_start"), concat(mcString(name), offlineUuidBytes(name)));

            while (true) {
                Packet packet = receive();
                int id = packet.id();
                Cursor reader = packet.reader();
                if (id == ids.get("cb_login_compression")) {
                    threshold = reader.varInt();
                } else if (id == ids.get("cb_login_success")) {
                    String player = reader.uuid();
                    send(ids.get("sb_login_ack"), new byte[0]);
                    say("logged-in " + player);
                    return player;
                } else if (id == ids.get("cb_login_disconnect")) {
                    String reason = describe(reader.string());
                    say("disconnected " + reason);
                    throw new BotRefused("the server refused the login: " + reason);
                } else if (id == ids.get("cb_login_encryption")) {
                    die("the server is in ONLINE mode — the scenarios stage offline-mode servers "
                            + "(server.properties online-mode=false)");
                }
                // anything else in the login phase is not ours to care about
            }
        } catch (IOException e) {
            die("handshake/configuration failed: " + e);
            throw new IllegalStateException("unreachable");
        }
    }

    /** The configuration phase, accepted wholesale. */
    public void configuration() {
        try {
            // A minimal, honest client-information packet: locale, view distance, chat settings,
            // skin parts, main hand, text filtering, server listing, particles.
            byte[] info = concat(mcString("en_us"), new byte[] {10}, varInt(0), new byte[] {1},
                    new byte[] {0x7F}, varInt(1), new byte[] {0}, new byte[] {1}, varInt(0));
            send(ids.get("sb_cfg_client_information"), info);
            send(ids.get("sb_cfg_plugin_message"),
                    concat(mcString("minecraft:brand"), mcString("nodera-vanilla-bot")));

            while (true) {
                Packet packet = receive();
                int id = packet.id();
                Cursor reader = packet.reader();
                if (id == ids.get("cb_cfg_known_packs")) {
                    // Echo an empty known-pack set: we accept whatever the server sends.
                    send(ids.get("sb_cfg_known_packs"), varInt(0));
                } else if (id == ids.get("cb_cfg_keep_alive")) {
                    send(ids.get("sb_cfg_keep_alive"), reader.take(8));
                } else if (id == ids.get("cb_cfg_ping")) {
                    send(ids.get("sb_cfg_pong"), reader.take(4));
                } else if (id == ids.get("cb_cfg_finish")) {
                    send(ids.get("sb_cfg_finish_ack"), new byte[0]);
                    return;
                } else if (id == ids.get("cb_cfg_disconnect")) {
                    String reason = describe(reader.string());
                    say("disconnected " + reason);
                    throw new BotRefused("the server refused us during configuration: " + reason);
                }
                // registry data, tags, resource packs: accepted implicitly by ignoring them
            }
        } catch (IOException e) {
            die("handshake/configuration failed: " + e);
        }
    }

    /**
     * Service the play phase for {@code window}.
     *
     * @return {@code false} if the server closed the connection.
     */
    public boolean pump(Duration window) {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            Packet packet;
            try {
                packet = receive();
            } catch (SocketTimeoutException nothingYet) {
                continue;
            } catch (IOException closed) {
                say("disconnected " + closed);
                return false;
            }
            int id = packet.id();
            Cursor reader = packet.reader();
            try {
                if (id == ids.get("cb_play_keep_alive")) {
                    send(ids.get("sb_play_keep_alive"), reader.take(8));
                } else if (id == ids.get("cb_play_sync_position")) {
                    // x,y,z (3xf64) + yaw,pitch (2xf32) + flags (1) + teleport id (varint)
                    byte[] body = reader.take(8 * 3 + 4 * 2 + 1);
                    send(ids.get("sb_play_confirm_teleport"), varInt(reader.varInt()));
                    ByteBuffer position = ByteBuffer.wrap(body, 0, 24);
                    double x = position.getDouble();
                    double y = position.getDouble();
                    double z = position.getDouble();
                    send(ids.get("sb_play_position"), ByteBuffer.allocate(25)
                            .putDouble(x).putDouble(y).putDouble(z).put((byte) 1).array());
                    if (!joined) {
                        joined = true;
                        say("joined");
                    }
                } else if (id == ids.get("cb_play_disconnect")) {
                    // The play-phase disconnect carries an NBT component rather than JSON, so the
                    // python read the remaining bytes as text and let describe() fall back. Same
                    // here: an approximate reason beats no reason.
                    say("disconnected " + describe(new String(reader.rest(), StandardCharsets.UTF_8)));
                    return false;
                }
                // cb_play_login is deliberately ignored: the position sync is the real
                // "we are in the world" signal.
            } catch (IOException writeFailed) {
                say("disconnected " + writeFailed);
                return false;
            } catch (HarnessException truncated) {
                // A packet the bot decoded wrongly is not a reason to fail the scenario: a real
                // client ignores what it does not understand.
                continue;
            }
        }
        return true;
    }

    /** Run a command as the player — WITHOUT the leading slash. */
    public void sendCommand(String command) {
        try {
            // ChatCommand: the command, a timestamp, a salt, an empty signature array, and the
            // message-count/acknowledged bitset.
            send(ids.get("sb_play_chat_command"), concat(mcString(command),
                    longBe(System.currentTimeMillis()), longBe(0), varInt(0), varInt(0),
                    new byte[3]));
        } catch (IOException e) {
            die("could not send the command '" + command + "': " + e);
        }
    }

    /** Say something in chat. */
    public void sendChat(String text) {
        try {
            send(ids.get("sb_play_chat_message"), concat(mcString(text),
                    longBe(System.currentTimeMillis()), longBe(0), new byte[] {0},
                    varInt(0), new byte[3]));
        } catch (IOException e) {
            die("could not send the chat message: " + e);
        }
    }

    /** Drop the entire held stack. */
    public void dropHeld() {
        try {
            // PlayerAction status 3 = drop the entire held stack, at (0,0,0), face 0.
            send(ids.get("sb_play_player_action"),
                    concat(varInt(3), longBe(0), new byte[] {0}, varInt(0)));
        } catch (IOException e) {
            die("could not drop the held stack: " + e);
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException alreadyGone) {
            // Closing a socket the server already closed is not an error worth reporting.
        }
    }

    // ---------------------------------------------------------------------------------------
    // Evidence
    // ---------------------------------------------------------------------------------------

    private void say(String message) {
        String line = "BOT: " + message;
        evidence.add(line);
        if (logFile == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new HarnessException("cannot write the bot's evidence to " + logFile, e);
        }
    }

    private void die(String reason) {
        say("failed " + reason);
        throw new BotFailed(reason);
    }

    // ---------------------------------------------------------------------------------------
    // Wire primitives — length-prefixed, optionally zlib-compressed Minecraft framing
    // ---------------------------------------------------------------------------------------

    /** One decoded packet: its id and a cursor over its body. */
    private record Packet(int id, Cursor reader) { }

    /** Cursor over one decoded packet body. Never reads past its own buffer. */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        byte[] take(int count) {
            if (position + count > data.length) {
                throw new HarnessException("packet truncated");
            }
            byte[] chunk = new byte[count];
            System.arraycopy(data, position, chunk, 0, count);
            position += count;
            return chunk;
        }

        byte[] rest() {
            return take(data.length - position);
        }

        int varInt() {
            int result = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = take(1)[0] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new HarnessException("varint longer than 5 bytes");
        }

        String string() {
            return new String(take(varInt()), StandardCharsets.UTF_8);
        }

        String uuid() {
            ByteBuffer bytes = ByteBuffer.wrap(take(16));
            return new UUID(bytes.getLong(), bytes.getLong()).toString();
        }
    }

    private void send(int packetId, byte[] payload) throws IOException {
        byte[] body = concat(varInt(packetId), payload);
        if (threshold >= 0) {
            if (body.length >= threshold) {
                body = concat(varInt(body.length), deflate(body));
            } else {
                body = concat(varInt(0), body);
            }
        }
        out.write(concat(varInt(body.length), body));
        out.flush();
    }

    private Packet receive() throws IOException {
        int length = readVarIntFromStream();
        byte[] body = readExactly(length);
        if (threshold >= 0) {
            Cursor framed = new Cursor(body, 0);
            int uncompressed = framed.varInt();
            byte[] remainder = framed.rest();
            body = uncompressed > 0 ? inflate(remainder, uncompressed) : remainder;
        }
        Cursor reader = new Cursor(body, 0);
        return new Packet(reader.varInt(), reader);
    }

    private int readVarIntFromStream() throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = readExactly(1)[0] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("varint longer than 5 bytes");
    }

    private byte[] readExactly(int count) throws IOException {
        while (buffer.length - bufferOffset < count) {
            byte[] chunk = new byte[Math.max(4096, count - (buffer.length - bufferOffset))];
            int read = in.read(chunk);
            if (read < 0) {
                throw new IOException("server closed the connection");
            }
            pending.reset();
            pending.write(buffer, bufferOffset, buffer.length - bufferOffset);
            pending.write(chunk, 0, read);
            buffer = pending.toByteArray();
            bufferOffset = 0;
        }
        byte[] out = new byte[count];
        System.arraycopy(buffer, bufferOffset, out, 0, count);
        bufferOffset += count;
        return out;
    }

    private static byte[] deflate(byte[] body) {
        Deflater deflater = new Deflater();
        deflater.setInput(body);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] body, int expected) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(body);
        byte[] out = new byte[expected];
        try {
            int written = 0;
            while (written < expected && !inflater.finished()) {
                int step = inflater.inflate(out, written, expected - written);
                if (step == 0 && inflater.needsInput()) {
                    break;
                }
                written += step;
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("the server sent a malformed compressed packet", e);
        } finally {
            inflater.end();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Encoding helpers
    // ---------------------------------------------------------------------------------------

    static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        int remaining = value;
        while (true) {
            int b = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    static byte[] mcString(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        return concat(varInt(raw.length), raw);
    }

    private static byte[] shortBe(int value) {
        return ByteBuffer.allocate(2).putShort((short) value).array();
    }

    private static byte[] longBe(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /**
     * The offline-mode UUID the python driver sent: a version-3 (MD5) UUID over the OID namespace
     * and {@code "OfflinePlayer:<name>"}.
     */
    private static byte[] offlineUuidBytes(String player) {
        byte[] namespace = ByteBuffer.allocate(16)
                .putLong(UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8")
                        .getMostSignificantBits())
                .putLong(UUID.fromString("6ba7b812-9dad-11d1-80b4-00c04fd430c8")
                        .getLeastSignificantBits())
                .array();
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(namespace);
            md5.update(("OfflinePlayer:" + player).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md5.digest();
            digest[6] = (byte) ((digest[6] & 0x0F) | 0x30);   // version 3
            digest[8] = (byte) ((digest[8] & 0x3F) | 0x80);   // IETF variant
            return digest;
        } catch (NoSuchAlgorithmException noMd5) {
            throw new HarnessException("this JVM has no MD5, so no offline UUID can be minted",
                    noMd5);
        }
    }

    /** Best-effort human text out of a chat/disconnect component. */
    static String describe(String raw) {
        if (raw == null) {
            return "null";
        }
        Object parsed = ServerJson.tryParse(raw).orElse(null);
        if (parsed == null) {
            return raw.length() <= 200 ? raw : raw.substring(0, 200);
        }
        if (parsed instanceof String s) {
            return s;
        }
        if (parsed instanceof Map<?, ?> map) {
            Object plain = map.get("text");
            StringBuilder text = new StringBuilder(plain == null ? "" : String.valueOf(plain));
            Object extra = map.get("extra");
            if (extra instanceof List<?> parts) {
                parts.forEach(part -> text.append(describe(ServerJson.render(part))));
            }
            if (text.length() > 0) {
                return text.toString();
            }
        }
        String rendered = ServerJson.render(parsed);
        return rendered.length() <= 200 ? rendered : rendered.substring(0, 200);
    }
}
