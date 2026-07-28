package dev.nodera.protocol.service;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * A service telling its connected peers directly that it is going away (wire tag
 * {@value dev.nodera.protocol.codec.MessageCodec#TAG_SERVICE_DRAIN_NOTICE}).
 *
 * <p>Pushed down every control channel the service holds, <b>not only</b> published to a tracker. A
 * peer that reserved a relay slot has an open socket to the service, and that socket is the fastest
 * and most reliable way to reach exactly the peers who are about to be hurt: they are, by
 * construction, the ones whose inbound path is about to vanish. The tracker path is the belt; this is
 * the braces.
 *
 * <p>The record is signed, so a peer cannot be evicted from a working rendezvous by a forged notice —
 * which would otherwise be a cheap way to herd a target's traffic onto a relay the attacker runs.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param record       the draining service's record, with {@link ServiceLifecycle#DRAINING} and a
 *                     drain deadline.
 * @param signature    Ed25519 over {@link ServiceRecord#signedBytes()}.
 * @param replacements where to go instead, best first, as the service last heard from a tracker.
 * @param reason       a short stable code: {@code update}, {@code operator}, {@code shutdown}.
 */
public record ServiceDrainNotice(
        ServiceRecord record,
        Bytes signature,
        List<ServiceDirectoryEntry> replacements,
        String reason) implements NoderaMessage {

    /** The service is restarting to install a newer release. */
    public static final String REASON_UPDATE = "update";

    /** An operator asked the service to stop. */
    public static final String REASON_OPERATOR = "operator";

    /** The process received a termination signal. */
    public static final String REASON_SHUTDOWN = "shutdown";

    /**
     * Compact constructor: validates and defensive-copies the replacement list.
     *
     * @throws IllegalArgumentException if a reference argument is null.
     */
    public ServiceDrainNotice {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(replacements, "replacements");
        Objects.requireNonNull(reason, "reason");
        replacements = List.copyOf(replacements);
    }
}
