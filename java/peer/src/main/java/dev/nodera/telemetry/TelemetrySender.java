package dev.nodera.telemetry;

import dev.nodera.transport.Frames;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Submits a batch to the ingest service over the project's shared framing.
 *
 * <p>Same framing as every other Nodera TCP leg ({@link Frames}: {@code u32} length + body), so an
 * operator debugging a connection reasons about one framing rule for the whole system. The body is
 * JSON because it leaves the Nodera world immediately for a warehouse that speaks JSON — the one
 * deliberate departure from the canonical encoding, argued in {@code docs/telemetry/Task.1.md}.
 *
 * <p><b>Every failure here is best-effort and quiet.</b> A telemetry endpoint that is down, slow,
 * or hostile must be indistinguishable — from the rest of the node's behaviour — from telemetry
 * being switched off. So this class has a short timeout, no retry loop of its own, and returns a
 * result rather than throwing.
 *
 * @Thread-context one sender per worker; {@link #send} is called from the telemetry thread only.
 */
public final class TelemetrySender {

    /** Short on purpose: a stalled telemetry endpoint must not hold a thread for long. */
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;
    /** The receiver's own per-batch bound; sending more would be refused whole. */
    public static final int MAX_EVENTS_PER_BATCH = 500;

    private final String host;
    private final int port;
    private final String agent;
    private final String source;

    private TelemetrySender(String host, int port, String agent, String source) {
        this.host = host;
        this.port = port;
        this.agent = agent;
        this.source = source;
    }

    /**
     * Parse an endpoint of the form {@code tcp://host:port} (or {@code host:port}).
     *
     * @return empty when the endpoint is blank or unparsable — both of which mean "do not send",
     *         never an exception, because an operator's typo must degrade telemetry and nothing else.
     */
    public static Optional<TelemetrySender> of(String endpoint, String agent, String source) {
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        String value = endpoint.trim();
        try {
            if (!value.contains("://")) {
                value = "tcp://" + value;
            }
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getPort() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new TelemetrySender(uri.getHost(), uri.getPort(), agent, source));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** What one submission did. {@code accepted < 0} means the batch never reached the service. */
    public record Result(boolean delivered, int accepted, int rejected, String error) {

        public static Result failure(String error) {
            return new Result(false, -1, -1, error);
        }
    }

    /**
     * Send one batch.
     *
     * <p>The consent token is passed in rather than read from a field so that this class cannot
     * send without a caller having just consulted the consent state — there is no cached "we were
     * granted once" to go stale.
     */
    public Result send(List<TelemetryEvent> events, InstallId install, TelemetryConsent consent,
                       long nowMillis) {
        if (!consent.collects()) {
            // Defence in depth: the caller already checked, and the receiver checks again.
            return Result.failure("consent is not granted");
        }
        if (events.isEmpty()) {
            return new Result(true, 0, 0, "");
        }
        String body = envelope(events, install, nowMillis);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            Frames.write(socket.getOutputStream(), body.getBytes(StandardCharsets.UTF_8));
            Optional<byte[]> reply = Frames.read(socket.getInputStream());
            if (reply.isEmpty()) {
                return Result.failure("the telemetry service closed the connection without answering");
            }
            return parseReply(new String(reply.get(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Result.failure(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** The batch envelope, exactly as {@code nodera-telemetry}'s validator expects it. */
    String envelope(List<TelemetryEvent> events, InstallId install, long nowMillis) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"v\":").append(TelemetryRegistry.BATCH_VERSION)
                .append(",\"src\":\"").append(source)
                .append("\",\"consent\":\"").append(TelemetryRegistry.CONSENT_GRANTED)
                .append("\",\"install\":\"").append(install.value())
                .append("\",\"agent\":\"").append(agent)
                .append("\",\"sent_at\":").append(nowMillis)
                .append(",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(events.get(i).toJson());
        }
        return json.append("]}").toString();
    }

    /**
     * Read the service's verdict.
     *
     * <p>A batch the service refused is <b>delivered</b>: it arrived, it was understood, and it was
     * declined. Treating a refusal as a delivery failure would produce a retry loop that spends a
     * node's bandwidth being wrong faster — the emitter is the thing that needs fixing, so the
     * reason is surfaced instead.
     */
    static Result parseReply(String reply) {
        String error = value(reply, "\"error\":\"");
        int accepted = (int) number(reply, "\"accepted\":");
        int rejected = (int) number(reply, "\"rejected\":");
        return new Result(true, accepted, rejected, error == null ? "" : error);
    }

    private static String value(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return null;
        }
        int start = at + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static long number(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return 0;
        }
        int start = at + key.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String endpoint() {
        return "tcp://" + host + ":" + port;
    }
}
