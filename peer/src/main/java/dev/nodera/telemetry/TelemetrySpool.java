package dev.nodera.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The emitter's queue: bounded, oldest-dropped, and persisted so a restart does not lose the events
 * that were most interesting (the ones just before a crash).
 *
 * <p><b>Bounded, and it drops the oldest.</b> Unbounded means a node that has been offline for a
 * month either fills its disk or ships a month-old backlog the receiver rejects as stale. Dropping
 * the newest would be worse still: the events immediately before a failure are the ones anyone would
 * want. So the failure mode is "old telemetry is lost", which is the correct thing to lose, and the
 * drop is counted so the loss is visible rather than silent.
 *
 * <p>Persistence is a plain NDJSON file rewritten on flush. Not an append-only log with compaction:
 * the queue is bounded to a few thousand small lines, so the simplest correct thing is also the
 * cheapest, and a torn write costs at most one send's worth of already-best-effort data.
 *
 * @Thread-context all methods are synchronised; the enqueue path is called from game and worker
 *                 threads, the drain path from the telemetry thread only.
 */
public final class TelemetrySpool {

    /** Default bound: a few hours of windowed events, or a long burst of rare ones. */
    public static final int DEFAULT_CAPACITY = 2_000;

    private final Deque<TelemetryEvent> queue = new ArrayDeque<>();
    private final int capacity;
    private final Path file; // nullable — an in-memory spool for tests and for a node with no home
    private long dropped;

    public TelemetrySpool(int capacity, Path file) {
        this.capacity = Math.max(1, capacity);
        this.file = file;
    }

    /** An in-memory spool with the default bound. */
    public static TelemetrySpool inMemory() {
        return new TelemetrySpool(DEFAULT_CAPACITY, null);
    }

    /**
     * Add one event, dropping the oldest if the spool is full.
     *
     * <p>Never blocks and never touches disk: this is called from paths that must not do I/O, so
     * persistence happens on {@link #persist()} from the telemetry thread.
     */
    public synchronized void offer(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        while (queue.size() >= capacity) {
            queue.pollFirst();
            dropped++;
        }
        queue.addLast(event);
    }

    /** Take up to {@code max} events, oldest first. They are removed — a failed send re-offers. */
    public synchronized List<TelemetryEvent> drain(int max) {
        List<TelemetryEvent> batch = new ArrayList<>(Math.min(max, queue.size()));
        for (int i = 0; i < max && !queue.isEmpty(); i++) {
            batch.add(queue.pollFirst());
        }
        return batch;
    }

    /**
     * Put a failed batch back at the front, preserving order.
     *
     * <p>At the front rather than the back so a repeatedly failing send does not reorder a session's
     * events into nonsense; if the spool is full the oldest of them are dropped by the same rule as
     * everything else.
     */
    public synchronized void requeue(List<TelemetryEvent> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            if (queue.size() >= capacity) {
                dropped++;
                continue;
            }
            queue.addFirst(batch.get(i));
        }
    }

    public synchronized int size() {
        return queue.size();
    }

    /** How many events were lost to the bound. Surfaced in the worker's state JSON. */
    public synchronized long dropped() {
        return dropped;
    }

    /** Discard everything — part of consent revocation, where "queued" must become "gone". */
    public synchronized void clear() throws IOException {
        queue.clear();
        if (file != null) {
            Files.deleteIfExists(file);
        }
    }

    /** Write the queue to disk, atomically. No-op for an in-memory spool. */
    public synchronized void persist() throws IOException {
        if (file == null) {
            return;
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder body = new StringBuilder();
        for (TelemetryEvent event : queue) {
            body.append(event.toJson()).append('\n');
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, body.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Reload a persisted spool.
     *
     * <p>Unparsable lines are skipped rather than failing the load: a torn final line from a crash
     * is the expected case, and losing it is exactly the cost this design already accepts.
     */
    public synchronized void restore() throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            TelemetryEvent event = parse(line);
            if (event != null) {
                offer(event);
            }
        }
    }

    /**
     * Minimal reader for the one JSON shape this class writes.
     *
     * <p>Hand-rolled because {@code peer} has no JSON dependency and the alternative — adding one
     * for a file this class both writes and reads — is a dependency for nothing. Public because the
     * control verb reads the same shape from the mod and the app.
     *
     * @return the event, or {@code null} when the line does not name a declared event — which is
     *         the check that stops an undeclared name from ever entering the spool.
     */
    public static TelemetryEvent parse(String line) {
        String name = extractString(line, "\"name\":\"");
        if (name == null || !TelemetryRegistry.declares(name)) {
            return null;
        }
        long at = extractLong(line, "\"t\":");
        int attrsAt = line.indexOf("\"attrs\":{");
        TelemetryEvent.Builder builder = TelemetryEvent.named(name, at);
        if (attrsAt >= 0) {
            String attrs = line.substring(attrsAt + "\"attrs\":{".length(), line.lastIndexOf("}}"));
            for (String pair : attrs.split(",(?=\")")) {
                int colon = pair.indexOf("\":");
                if (colon <= 0) {
                    continue;
                }
                String key = pair.substring(1, colon);
                String value = pair.substring(colon + 2).trim();
                if (value.startsWith("\"")) {
                    String text = value.substring(1, value.length() - 1);
                    if (TelemetryRegistry.admits(name, key, text)) {
                        builder.enumeration(key, text);
                    }
                } else if ("true".equals(value) || "false".equals(value)) {
                    builder.flag(key, Boolean.parseBoolean(value));
                } else {
                    try {
                        builder.number(key, Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        // A value this reader cannot type is dropped, exactly as the receiver would.
                    }
                }
            }
        }
        return builder.build();
    }

    private static String extractString(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return null;
        }
        int start = at + key.length();
        int end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }

    private static long extractLong(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) {
            return 0L;
        }
        int start = at + key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(line.substring(start, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
