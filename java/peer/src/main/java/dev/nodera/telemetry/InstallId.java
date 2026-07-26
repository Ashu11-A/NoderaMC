package dev.nodera.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * The installation identifier: 128 random bits, hex, stored beside the node's other local state.
 *
 * <p>Two properties make it acceptable to send at all:
 *
 * <ul>
 *   <li><b>It is not the node id.</b> It has no relationship to the Ed25519 identity the network
 *       knows this peer by, so telemetry cannot be joined to anything on the wire.</li>
 *   <li><b>It is regenerated on every consent grant.</b> Turning telemetry off and on again
 *       produces a new installation as far as the pipeline is concerned, so a revocation cannot be
 *       undone by re-granting — the earlier reports stay unlinkable to the later ones by anything
 *       the client sends.</li>
 * </ul>
 *
 * <p>The receiver never stores this value: it replaces it with a rotating HMAC subject
 * ({@code rust/nodera-telemetry/src/subject.rs}). Sending it at all is what lets the receiver count
 * an installation once instead of once per report.
 *
 * @Thread-context instances are immutable; the file operations are called from the worker's
 *                 telemetry thread only.
 */
public final class InstallId {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private InstallId(String value) {
        this.value = value;
    }

    /** A fresh random identifier. */
    public static InstallId generate() {
        byte[] material = new byte[16];
        RANDOM.nextBytes(material);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : material) {
            hex.append(String.format("%02x", b));
        }
        return new InstallId(hex.toString());
    }

    /** Wrap an existing value, or {@code null} when it is not a 128-bit hex identifier. */
    public static InstallId parse(String value) {
        return value != null && TelemetryRegistry.isHex(value.trim(), 32)
                ? new InstallId(value.trim())
                : null;
    }

    /**
     * Read the identifier at {@code path}, creating one if it is missing or unreadable.
     *
     * <p>A corrupt file yields a new identifier rather than an exception: the consequence of losing
     * one is that this installation is counted as a new one, which is a rounding error in an
     * aggregate and never a reason to fail a node's startup.
     */
    public static InstallId loadOrCreate(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            InstallId existing = parse(Files.readString(path, StandardCharsets.UTF_8));
            if (existing != null) {
                return existing;
            }
        }
        InstallId created = generate();
        created.store(path);
        return created;
    }

    /** Write the identifier to {@code path}, creating parent directories. */
    public void store(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    /**
     * Delete the stored identifier — part of revocation.
     *
     * <p>Deleting rather than blanking, so that a revoked installation leaves no artefact that a
     * later bug could resurrect.
     */
    public static void forget(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        // Truncated deliberately: this string ends up in logs, and a log line is a place an
        // identifier gets copied into a bug report.
        return "InstallId[" + value.substring(0, 8) + "…]";
    }
}
