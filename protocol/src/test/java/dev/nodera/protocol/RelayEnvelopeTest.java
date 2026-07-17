package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.codec.MessageCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelayEnvelope} encode/decode round-trip coverage (Task 4 §"NeoForgeRelayTransport"):
 * the inner frame is opaque bytes, untouched by the codec.
 *
 * <p>Thread-context: single test thread.
 */
final class RelayEnvelopeTest {

    @Test
    void relayEnvelopeRoundTripsWithOpaqueInnerFrame() {
        NodeId target = new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000ab"));
        byte[] innerFrame = MessageCodec.encode(new EchoTest(Bytes.fromHex("cafebabe")));

        RelayEnvelope env = new RelayEnvelope(target, Bytes.unsafeWrap(innerFrame));

        RelayEnvelope decoded = (RelayEnvelope) MessageCodec.decode(MessageCodec.encode(env));
        assertThat(decoded).isEqualTo(env);
        assertThat(decoded.target()).isEqualTo(target);
        assertThat(decoded.innerFrame().toArray()).isEqualTo(innerFrame);
    }

    @Test
    void relayEnvelopeRoundTripsWithArbitraryBytes() {
        NodeId target = new NodeId(UUID.fromString("12345678-1234-5678-1234-567812345678"));
        Bytes inner = Bytes.fromHex("00112233445566778899aabbccddeeff");

        RelayEnvelope env = new RelayEnvelope(target, inner);

        RelayEnvelope decoded = (RelayEnvelope) MessageCodec.decode(MessageCodec.encode(env));
        assertThat(decoded).isEqualTo(env);
        assertThat(decoded.innerFrame()).isEqualTo(inner);
    }

    @Test
    void typeTagOfRelayEnvelopeMatchesConstant() {
        RelayEnvelope env = new RelayEnvelope(
                new NodeId(UUID.randomUUID()), Bytes.fromHex("00"));
        assertThat(MessageCodec.typeTagOf(env)).isEqualTo(MessageCodec.TAG_RELAY_ENVELOPE);
    }

    @Test
    void decodeOfEncodeReturnsExactSameClass() {
        RelayEnvelope env = new RelayEnvelope(
                new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                Bytes.fromHex("abcd"));
        NoderaMessage decoded = MessageCodec.decode(MessageCodec.encode(env));
        assertThat(decoded.getClass()).isEqualTo(RelayEnvelope.class);
    }
}
