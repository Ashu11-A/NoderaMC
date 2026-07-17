package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Wire envelope around a core {@link ActionBatch} (Task 4).
 *
 * <p>{@code ActionBatch} already implements core's {@code Encodable}; this thin wrapper is the
 * {@link NoderaMessage} that lets an action batch cross the transport. The codec delegates to
 * {@link ActionBatch#encode(dev.nodera.core.crypto.CanonicalWriter)} for the body, so the
 * on-wire bytes of an action batch are identical whether it is hashed, signed, or transported.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param batch the wrapped action batch.
 */
public record ActionBatchMsg(ActionBatch batch) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code batch} is null.
     */
    public ActionBatchMsg {
        Objects.requireNonNull(batch, "batch");
    }
}
