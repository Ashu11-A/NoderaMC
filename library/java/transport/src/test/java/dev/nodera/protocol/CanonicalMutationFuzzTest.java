package dev.nodera.protocol;

import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.NoderaFrame;
import dev.nodera.protocol.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the wire to the canonical-encoding invariant under mutated input.
 *
 * <p>The contract is <b>one byte string per message</b>: a frame either
 * fails to decode, or it decodes to a value that re-encodes to exactly the bytes it came from. Any
 * frame that decodes but re-encodes differently is a second valid encoding of the same value —
 * which is a consensus break, because the two peers that hashed the two spellings disagree about a
 * root while agreeing about the state.
 *
 * <p>This test mutates every golden sample and asserts that invariant on whatever survives. It is
 * the Java half of the differential pair; {@code rust/nodera-codec/tests/mutation.rs} asserts the
 * identical invariant against the same corpus, so a divergence in tolerance between the two
 * implementations shows up as one side accepting a frame the other rejects.
 *
 * <p>Mutations are deterministic — a fixed stride over byte positions and a fixed set of bit
 * patterns — so a failure is reproducible from the message name and offset in the report.
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
final class CanonicalMutationFuzzTest {

    /** Bit patterns XOR-ed into a byte: low bit, high bit, and a full flip. */
    private static final int[] PATTERNS = {0x01, 0x80, 0xFF};

    /** Cap on mutation sites per message, so a 4 KiB tunnel payload does not dominate the run. */
    private static final int MAX_SITES = 96;

    /**
     * <b>There are no exceptions.</b>
     *
     * <p>There used to be two, and both were symptoms rather than quirks.
     *
     * <p>{@code RegionRefusal} collapsed an unrecognised reason code to {@code UNKNOWN}, which then
     * re-encoded as {@code UNKNOWN}'s own ordinal — a frame that decoded but came out different. The
     * tolerance was intentional; flattening the value was not. It now keeps the raw code, so an
     * unknown reason survives a relay instead of being rewritten into a reason this build made up.
     *
     * <p>{@code PeerJoin} read its trailing {@code clientVersion} only if bytes remained, so a frame
     * cut just before that field was byte-for-byte a valid older {@code PeerJoin} and <em>no</em>
     * decoder could tell the two apart. That is not a codec bug either: a positional body with an
     * optional tail carries no statement of its own length. Length-delimited fields remove the
     * ambiguity outright.
     *
     * <p>Both were named here so they could not quietly become three. The list is now empty, and a
     * new entry in it is a regression to argue about, not a note to add.
     */
    private static final List<String> DOCUMENTED_EXCEPTIONS = List.of();

    /**
     * Body mutations start after the {@code NDR2} header.
     *
     * <p>Mutating the header does not corrupt a message, it re-addresses it: changing the kind
     * selects a different message, and changing the flags or correlation changes routing metadata
     * that the encoder re-emits from the message's own defaults. The header has its own tests —
     * magic, epoch, declared length, and unknown kind — and they assert the things that actually
     * matter about it.
     */
    private static final int HEADER_BYTES = NoderaFrame.HEADER_BYTES;

    /**
     * The cardinality this file was missing.
     *
     * <p>Every loop below produces a value and then asserts something about the survivors, and
     * nothing asserted <b>how many things the value covered</b>. `unknownKind() -> continue` and
     * `catch (RuntimeException rejected) { continue; }` are both correct outcomes and both silent:
     * a change that made every mutated frame re-address itself, or made the decoder throw on
     * everything, would leave `violations` empty and this test green while asserting nothing. That
     * is the same shape as the magic-bytes bug already fixed in the Rust half of this pair.
     *
     * <p>So the loop counts. A mutation lands in exactly one of three buckets — rejected,
     * re-addressed to an unknown kind, or decoded — and the decoded bucket is the only one the
     * invariant can be checked on. It must not be empty, and it must not be a rounding error
     * against the number of mutations attempted.
     */
    private static final class MutationTally {
        int attempted;
        int rejected;
        int reAddressed;
        int decoded;

        @Override
        public String toString() {
            return attempted + " mutations: " + decoded + " decoded, " + rejected + " rejected, "
                    + reAddressed + " re-addressed to an unknown kind";
        }
    }

    @Test
    void decodedMutationsReEncodeToTheirOwnBytes() {
        MessageSamples.assertTotal();
        List<String> violations = new ArrayList<>();
        MutationTally tally = new MutationTally();

        for (Map.Entry<Integer, NoderaMessage> entry : MessageSamples.byTag().entrySet()) {
            String name = MessageCodec.typeName(entry.getKey());
            if (DOCUMENTED_EXCEPTIONS.contains(name)) {
                continue;
            }
            byte[] encoded = WireCodec.encode(entry.getValue());
            int stride = Math.max(1, encoded.length / MAX_SITES);

            for (int offset = HEADER_BYTES; offset < encoded.length; offset += stride) {
                for (int pattern : PATTERNS) {
                    byte[] mutated = encoded.clone();
                    mutated[offset] ^= (byte) pattern;
                    if (java.util.Arrays.equals(mutated, encoded)) {
                        continue;
                    }
                    tally.attempted++;

                    WireCodec.DecodedFrame decoded;
                    try {
                        decoded = WireCodec.decodeFrame(mutated);
                        if (decoded.unknownKind()) {
                            // Re-addressed, not corrupted; the header has its own tests.
                            tally.reAddressed++;
                            continue;
                        }
                    } catch (RuntimeException rejected) {
                        tally.rejected++; // A clean rejection is the expected outcome.
                        continue;
                    }
                    tally.decoded++;
                    byte[] reencoded;
                    try {
                        // Re-encoded WITH whatever this build could not read, because that is what
                        // forwarding does: a peer that drops the fields it does not understand is a
                        // lossy relay between two peers that understand each other.
                        reencoded = WireCodec.encode(decoded.message().orElseThrow(),
                                decoded.overlay(), decoded.flags(), decoded.correlationId());
                    } catch (RuntimeException broken) {
                        violations.add(name + " @" + offset + "^" + Integer.toHexString(pattern)
                                + ": decoded but could not re-encode (" + broken + ")");
                        continue;
                    }
                    if (!java.util.Arrays.equals(mutated, reencoded)) {
                        violations.add(name + " @" + offset + "^" + Integer.toHexString(pattern)
                                + ": accepted a frame that re-encodes to different bytes");
                    }
                }
            }
        }

        assertThat(violations)
                .as("a frame that decodes must re-encode to itself; these do not")
                .isEmpty();
        assertThat(tally.attempted)
                .as("the corpus has to actually be mutated: %s", tally)
                .isGreaterThan(1_000);
        assertThat(tally.decoded)
                .as("a run in which nothing survives decoding asserts nothing about canonical "
                        + "re-encoding, and would still report green: %s", tally)
                .isGreaterThan(tally.attempted / 100);
        assertThat(tally.rejected + tally.reAddressed + tally.decoded)
                .as("every mutation lands in exactly one bucket: %s", tally)
                .isEqualTo(tally.attempted);
    }

    @Test
    void everyTruncationIsRejectedCleanly() {
        MessageSamples.assertTotal();
        int truncations = 0;
        for (Map.Entry<Integer, NoderaMessage> entry : MessageSamples.byTag().entrySet()) {
            String name = MessageCodec.typeName(entry.getKey());
            if (DOCUMENTED_EXCEPTIONS.contains(name)) {
                continue;
            }
            byte[] encoded = WireCodec.encode(entry.getValue());
            int stride = Math.max(1, encoded.length / MAX_SITES);

            for (int cut = 0; cut < encoded.length; cut += stride) {
                truncations++;
                byte[] prefix = java.util.Arrays.copyOf(encoded, cut);
                try {
                    WireCodec.decode(prefix);
                    throw new AssertionError(
                            name + ": a " + cut + "-byte prefix of a " + encoded.length
                                    + "-byte frame decoded as valid");
                } catch (RuntimeException expected) {
                    // A prefix is malformed input arriving pre-auth: erroring is the contract.
                    assertThat(expected)
                            .as("%s: truncation must fail as a codec error, not a stray runtime "
                                    + "exception", name)
                            .isInstanceOfAny(IllegalStateException.class,
                                    IllegalArgumentException.class,
                                    IndexOutOfBoundsException.class,
                                    java.io.UncheckedIOException.class);
                }
            }
        }
        assertThat(truncations)
                .as("a corpus that produced no prefixes would pass this test by doing nothing")
                .isGreaterThan(100);
    }

    @Test
    void appendingToAnyFrameIsRejected() {
        MessageSamples.assertTotal();
        int frames = 0;
        for (Map.Entry<Integer, NoderaMessage> entry : MessageSamples.byTag().entrySet()) {
            frames++;
            String name = MessageCodec.typeName(entry.getKey());
            byte[] encoded = WireCodec.encode(entry.getValue());
            byte[] extended = java.util.Arrays.copyOf(encoded, encoded.length + 1);

            assertThat(catchDecode(extended))
                    .as("%s: a trailing byte must invalidate the frame", name)
                    .isNotNull();
        }
        assertThat(frames)
                .as("every known tag has to be exercised, not whatever the map happened to hold")
                .isEqualTo(MessageCodec.KNOWN_TAGS.size());
    }

    private static RuntimeException catchDecode(byte[] frame) {
        try {
            WireCodec.decode(frame);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
