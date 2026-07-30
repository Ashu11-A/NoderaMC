package dev.nodera.core.action;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActionEnvelope} byte-stability and signature-exclusion checks (Task 2). The
 * {@code signedPortion} contract is consensus-critical: it MUST be a strict prefix of
 * {@code encode} and MUST NOT contain the signature bytes.
 */
final class ActionEnvelopeTest {

    private static final UUID ACTOR_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void signedPortionExcludesSignature() {
        ActionEnvelope env = sample();
        Bytes signed = env.signedPortion();
        assertThat(signed.toHex()).doesNotContain(env.signature().toHex());
    }

    @Test
    void signedPortionIsStrictPrefixOfEncode() {
        ActionEnvelope env = sample();
        byte[] signed = env.signedPortion().toArray();
        byte[] full = encode(env);
        assertThat(full.length).isGreaterThan(signed.length);
        for (int i = 0; i < signed.length; i++) {
            assertThat(full[i]).isEqualTo(signed[i]);
        }
    }

    @Test
    void encodeDecodeRoundTrip() {
        ActionEnvelope env = sample();
        ActionEnvelope decoded = ActionEnvelope.decode(new CanonicalReader(encode(env)));
        assertThat(decoded).isEqualTo(env);
    }

    @Test
    void signedPortionIsByteStable() {
        ActionEnvelope env = sample();
        Bytes first = env.signedPortion();
        Bytes second = env.signedPortion();
        assertThat(first).isEqualTo(second);
    }

    private static ActionEnvelope sample() {
        NodeId actor = new NodeId(ACTOR_UUID);
        RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
        GameAction action = new PlaceBlockAction(new NBlockPos(1, 2, 3), 10, 1);
        byte[] sig = new byte[64];
        for (int i = 0; i < sig.length; i++) {
            sig[i] = (byte) (i + 1);
        }
        return new ActionEnvelope(actor, 7L, 8L, 9L, region, action, new Bytes(sig));
    }

    private static byte[] encode(ActionEnvelope env) {
        CanonicalWriter w = new CanonicalWriter();
        env.encode(w);
        return w.toByteArray();
    }
}
