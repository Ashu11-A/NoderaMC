package dev.nodera.testkit.mc;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.Topology;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * A Source-RCON client for driving a Minecraft server from a scenario.
 *
 * <h2>Why every command is retried, and why teleports are verified</h2>
 *
 * <p>RCON is answered on the server thread. A long tick — generating terrain two hundred kilometres
 * from spawn on a two-core runner, or a region re-plan sweep — delays the reply rather than losing
 * it. The shell version of this client printed nothing in that case, and the caller could not tell
 * "the server said no" from "the server said nothing yet": a teleport that never ran then read as a
 * feature that never fired. So the socket timeout is generous, every command is attempted three
 * times, and {@link #teleport} reads the reply instead of assuming it.
 *
 * <p>{@link #awaitReady} exists for the same reason: a long teleport IS a chunk-generation request,
 * and the next command after it is often heard minutes later. Waiting for the server to talk again
 * is the difference between "the next command failed" and "the next command was never heard".
 *
 * <p>Thread-context: stateless; one short-lived socket per command.
 */
public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

    private final int port;
    private final String password;
    private final Topology topology;

    public RconClient(int port, String password, Topology topology) {
        this.port = port;
        this.password = password;
        this.topology = topology;
    }

    /**
     * Run one command, retrying a silent server.
     *
     * @return the server's reply, or empty after three silent attempts.
     */
    public Optional<String> send(String command) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String reply = sendOnce(command);
                if (reply != null && !reply.isBlank()) {
                    return Optional.of(reply);
                }
            } catch (IOException busyOrClosed) {
                // Fall through to the retry: the server thread is mid-tick, not gone.
            }
            Topology.sleep(Duration.ofSeconds(3));
        }
        return Optional.empty();
    }

    /**
     * Run one command and require an answer.
     *
     * @throws HarnessException naming the command the server never answered.
     */
    public String require(String command) {
        return send(command).orElseThrow(() -> new HarnessException(
                "the server never answered RCON command '" + command + "' (port " + port + ")"));
    }

    /** @return {@code true} if the server is answering RCON at all. */
    public boolean isUp() {
        return send("list").isPresent();
    }

    /** Wait until the server answers RCON. */
    public void awaitUp(Duration deadline) {
        long limit = System.nanoTime() + topology.scaled(deadline).toNanos();
        while (System.nanoTime() < limit) {
            if (isUp()) {
                return;
            }
            Topology.sleep(Duration.ofSeconds(5));
        }
        throw new HarnessException("the server never answered RCON on port " + port
                + " within " + topology.scaled(deadline).toSeconds() + "s");
    }

    /** @return {@code true} if {@code player} is in the {@code list} output. */
    public boolean isOnline(String player) {
        return send("list").filter(reply -> reply.contains(player)).isPresent();
    }

    /** Wait for a player to appear in {@code list}. */
    public void awaitPlayer(String player, Duration deadline) {
        long limit = System.nanoTime() + topology.scaled(deadline).toNanos();
        while (System.nanoTime() < limit) {
            if (isOnline(player)) {
                return;
            }
            Topology.sleep(Duration.ofSeconds(5));
        }
        throw new HarnessException("player " + player + " never appeared in the server's player list");
    }

    /**
     * Teleport a player and prove it happened.
     *
     * @throws HarnessException if the server did not accept the teleport — every assertion after an
     *                          unverified teleport measures a player who never moved.
     */
    public String teleport(String player, double x, double y, double z, String dimension) {
        String reply = require("execute in " + dimension + " run tp " + player
                + " " + x + " " + y + " " + z);
        if (!reply.contains("Teleported")) {
            throw new HarnessException("the teleport of " + player + " to (" + x + ", " + y + ", "
                    + z + ") was not accepted: " + reply);
        }
        awaitReady(player);
        return reply;
    }

    /** Teleport within the overworld. */
    public String teleport(String player, double x, double y, double z) {
        return teleport(player, x, y, z, "minecraft:overworld");
    }

    /**
     * Block until the server answers again after a long operation.
     *
     * @throws HarnessException if it stays busy — silence that outlives this is a hung server, and
     *                          the next command's failure would blame the wrong thing.
     */
    public void awaitReady(String player) {
        long limit = System.nanoTime() + topology.scaled(Duration.ofSeconds(240)).toNanos();
        while (System.nanoTime() < limit) {
            if (isOnline(player)) {
                return;
            }
            Topology.sleep(Duration.ofSeconds(5));
        }
        throw new HarnessException("the server never answered again after an operation on " + player
                + " (busy for over four minutes)");
    }

    private String sendOnce(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) SOCKET_TIMEOUT.toMillis());
            socket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            out.write(packet(1, TYPE_AUTH, password));
            out.flush();
            if (readPacketId(in) == -1) {
                throw new HarnessException("RCON authentication failed on port " + port
                        + " — the password does not match the server's");
            }

            out.write(packet(2, TYPE_COMMAND, command));
            out.flush();
            return readPacketBody(in);
        }
    }

    private static byte[] packet(int id, int type, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + payload.length + 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4 + 4 + payload.length + 2);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(payload);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        return buffer.array();
    }

    private static int readPacketId(DataInputStream in) throws IOException {
        byte[] frame = readFrame(in);
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static String readPacketBody(DataInputStream in) throws IOException {
        byte[] frame = readFrame(in);
        if (frame.length <= 10) {
            return "";
        }
        return new String(frame, 8, frame.length - 10, StandardCharsets.UTF_8);
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 0 || length > 4 * 1024 * 1024) {
            throw new IOException("implausible RCON frame length " + length);
        }
        byte[] body = new byte[length];
        in.readFully(body);
        return body;
    }
}
