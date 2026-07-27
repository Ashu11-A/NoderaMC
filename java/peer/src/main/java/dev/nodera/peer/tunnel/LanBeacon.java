package dev.nodera.peer.tunnel;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One "Open to LAN" announcement, as vanilla Minecraft broadcasts it.
 *
 * <h2>The format, and why parsing it is the whole trick</h2>
 *
 * <p>When a player opens a single-player world to LAN, their game joins the multicast group
 * {@code 224.0.2.60:4445} and shouts, roughly every 1.5 seconds:
 *
 * <pre>[MOTD]Steve's world[/MOTD][AD]54321[/AD]</pre>
 *
 * <p>That is a stable, decade-old part of vanilla Minecraft. It means this network can notice a
 * world being opened by a player running <b>no mod at all</b> — which is the entire reason the LAN
 * lane exists. Nothing has to be installed in the game, nothing has to be patched, and the detection
 * works identically for a modded client, a vanilla client, and any launcher.
 *
 * <p>The parser is deliberately strict about the port and lenient about the name: a MOTD is
 * user-supplied text that may contain almost anything (including, on some launchers, the section
 * sign and colour codes), whereas a port that is not a port is a beacon we cannot act on and should
 * ignore rather than guess at.
 *
 * @param motd the world's advertised name, as the player's game rendered it.
 * @param port the loopback port the host's game is listening on.
 * @Thread-context immutable record, safe for any thread.
 */
public record LanBeacon(String motd, int port) {

    /** The multicast group vanilla Minecraft announces LAN worlds on. Not ours to change. */
    public static final String GROUP = "224.0.2.60";

    /** The port that group is announced on. */
    public static final int PORT = 4445;

    private static final Pattern MOTD = Pattern.compile("\\[MOTD\\](.*?)\\[/MOTD\\]", Pattern.DOTALL);
    private static final Pattern AD = Pattern.compile("\\[AD\\](\\d{1,5})\\[/AD\\]");

    public LanBeacon {
        Objects.requireNonNull(motd, "motd");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("not a port: " + port);
        }
    }

    /**
     * Parse one datagram payload.
     *
     * @param payload the raw text of the packet.
     * @return the beacon, or empty when this is not a well-formed LAN announcement.
     * @Thread-context any thread.
     */
    public static Optional<LanBeacon> parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        Matcher ad = AD.matcher(payload);
        if (!ad.find()) {
            return Optional.empty();
        }
        int port;
        try {
            port = Integer.parseInt(ad.group(1));
        } catch (NumberFormatException notAPort) {
            return Optional.empty();
        }
        if (port <= 0 || port > 65535) {
            return Optional.empty();
        }
        Matcher motd = MOTD.matcher(payload);
        // A beacon with no MOTD is still a world; naming it after its port beats discarding it,
        // because the port is the part that makes it joinable.
        String name = motd.find() ? motd.group(1).trim() : "";
        return Optional.of(new LanBeacon(name.isEmpty() ? "LAN world on " + port : name, port));
    }

    /** @return the payload a Minecraft client would broadcast for this beacon. */
    public String encode() {
        return "[MOTD]" + motd + "[/MOTD][AD]" + port + "[/AD]";
    }
}
