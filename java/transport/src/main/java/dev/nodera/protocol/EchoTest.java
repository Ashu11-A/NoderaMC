package dev.nodera.protocol;

import dev.nodera.core.Bytes;

import java.util.Objects;

/**
 * Loopback test message used by Task 4 acceptance tests to verify end-to-end framing and
 * transport round-trips without exercising the simulation engine.
 *
 * <p>Carries an opaque {@link Bytes} payload that the receiver echoes back unchanged. The
 * codec frame for an {@code EchoTest} is the canonical golden vector asserted in
 * {@code MessageCodecGoldenTest}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param payload opaque echo payload.
 */
public record EchoTest(Bytes payload) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code payload} is null.
     */
    public EchoTest {
        Objects.requireNonNull(payload, "payload");
    }
}
