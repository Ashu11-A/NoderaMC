package dev.nodera.protocol;

/**
 * Root interface for every Nodera wire message (Task 4 protocol).
 *
 * <p>Every type that crosses a transport boundary — handshake, region assignment, simulation
 * simulation messages, consensus votes, health, relay envelopes, test echo — is one of the
 * records listed below. There is exactly ONE canonical encoding for any given message:
 * {@code dev.nodera.protocol.codec.MessageCodec} produces the bytes used for wire
 * transport, hashing, and signing alike (the same discipline as core's {@code Encodable}).
 * Individual message records therefore do NOT implement {@code Encodable}; the codec owns
 * framing.
 *
 * <p><b>Wire contract.</b> A message's canonical frame is
 * {@code u16 typeTag + u16 version + body}, big-endian, fixed-width, no varints — produced by
 * core's {@code CanonicalWriter}. Type tags live in {@code MessageCodec} as append-only
 * constants: assigning a tag is permanent; <b>never renumber an existing tag</b>. Later tasks
 * that introduce new messages append both a new record and a new tag constant — and NEVER
 * reorder or reuse existing numbers.
 *
 * <h2>Why this is not {@code sealed}</h2>
 *
 * <p>The build runs in an unnamed module (no {@code module-info.java}, per the Phase-0 pure-Java
 * layout). In that mode the JLS only allows a {@code sealed} type to be extended by subtypes in
 * the <i>same package</i>. Nodera intentionally spreads its message records across subpackages
 * ({@code handshake}, {@code assignment}, {@code simulationmsg}, {@code health}) for clarity, so
 * the {@code sealed} modifier cannot be used until/unless the project adopts named modules.
 * The intended closed hierarchy is instead enforced structurally by
 * {@link dev.nodera.protocol.codec.MessageCodec}: its encode/decode switches are the exhaustive
 * registry of permitted messages, and {@code MessageCodecTypeTagTest} pins the tag table.
 *
 * <p>Thread-context: implementations are immutable records; safe for any thread.
 *
 * @see dev.nodera.protocol.codec.MessageCodec
 */
public interface NoderaMessage {
}
