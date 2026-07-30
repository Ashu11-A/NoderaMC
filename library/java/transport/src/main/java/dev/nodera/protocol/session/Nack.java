package dev.nodera.protocol.session;

import dev.nodera.protocol.NoderaMessage;

/**
 * "I could not act on that, and here is why" ({@code Plan.7} D4).
 *
 * <p>Before this existed, an unknown kind was dropped in silence. From the sender's side that is
 * indistinguishable from a lost packet, a wedged peer, or a network fault — so the only recovery
 * available was a timeout, and the only diagnosis available was a guess. Worse, the receiver had no
 * safe alternative: with a positional frame it could not find the end of a message it did not
 * understand, so dropping the whole connection was the honest option and dropping the frame quietly
 * was the popular one.
 *
 * <p>A length-delimited frame makes the frame skippable, and this message makes the skip audible.
 * The connection survives; the sender learns immediately that this peer will never answer that
 * kind, and can stop asking.
 *
 * <p><b>The reason is stored as its wire number, not as an enum</b> ({@code Plan.7} D7). A newer
 * peer may refuse for a reason this build has never heard of, and resolving that to a stand-in
 * constant would make the message re-encode <em>as the stand-in</em> — a different refusal from the
 * one that arrived. Storing the number keeps a relayed or logged Nack faithful; {@link #code()}
 * resolves it for anyone who only wants to branch on the reasons this build knows.
 *
 * @param kind          the kind that was refused.
 * @param reasonCode    why, as the wire number; see {@link RejectCode}.
 * @param correlationId the correlation of the frame being refused, so the sender can fail exactly
 *                      the request that will never be answered rather than waiting it out.
 * @param detail        a short human-readable note for logs; never parsed.
 * @Thread-context immutable; any thread.
 */
public record Nack(int kind, int reasonCode, long correlationId, String detail)
        implements NoderaMessage {

    public Nack {
        if (kind < 0 || kind > 0xFFFF) {
            throw new IllegalArgumentException("kind must be a u16, got " + kind);
        }
        if (reasonCode < 0 || reasonCode > 0xFFFF) {
            throw new IllegalArgumentException("reasonCode must be a u16, got " + reasonCode);
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail must not be null; use \"\"");
        }
    }

    /** Build a Nack from a reason this build knows. */
    public Nack(int kind, RejectCode code, long correlationId, String detail) {
        this(kind, code.code(), correlationId, detail);
    }

    /**
     * The reason, resolved.
     *
     * @return the constant, or {@link RejectCode#UNAVAILABLE} for a code this build does not know —
     *         still a refusal, and {@link #reasonCode()} keeps the truth.
     * @Thread-context any thread.
     */
    public RejectCode code() {
        return RejectCode.fromCode(reasonCode);
    }

    /** @return {@code true} if this build recognises {@link #reasonCode()}. */
    public boolean reasonRecognised() {
        return code().code() == reasonCode;
    }

    /** A Nack for an unknown kind, carrying the correlation of the frame it refuses. */
    public static Nack unsupportedKind(int kind, long correlationId) {
        return new Nack(kind, RejectCode.UNSUPPORTED_KIND, correlationId,
                "this build does not know kind " + kind);
    }
}
