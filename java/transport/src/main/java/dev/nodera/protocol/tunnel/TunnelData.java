package dev.nodera.protocol.tunnel;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * One chunk of a tunnelled TCP stream, in either direction.
 *
 * <p>The payload is <b>opaque</b>: this carries somebody else's protocol — Minecraft's, in the case
 * this exists for — and Nodera has no business parsing it. That is also the honest description of
 * what a LAN tunnel is: two players' game clients talking to each other, with this network doing
 * nothing but carrying the bytes.
 *
 * <p>Ordering comes from the transport, which is a reliable ordered byte pipe per peer; a stream is
 * therefore just a tagged subsequence of that, and no sequence number is needed. If the transport
 * ever becomes unordered, this record needs one — the tests pin that assumption rather than leave it
 * implicit.
 *
 * @param streamId the stream this chunk belongs to, as named by the guest that opened it.
 * @param payload  the raw bytes; not null, may be empty only as a keep-alive.
 */
public record TunnelData(long streamId, Bytes payload) implements NoderaMessage {

    public TunnelData {
        Objects.requireNonNull(payload, "payload");
    }
}
