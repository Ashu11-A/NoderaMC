package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invitation you paste into a chat window.
 *
 * <p>Two encodings of one record — a URI to send and a file to save — so the tests care about the
 * same three things for both: it survives the trip, it carries no content, and a link somebody
 * mangled is refused rather than half-understood.
 */
final class WorldShareLinkTest {

    private static Bytes worldId() {
        return new dev.nodera.core.crypto.HashService().sha256("a world".getBytes());
    }

    private static WorldShareLink link() {
        return new WorldShareLink(
                worldId(),
                "Bob's Survival World",
                WorldShareLink.KIND_WORLD,
                List.of("tcp://tracker.example:25600", "udp://other.example:25600"),
                List.of("rendezvous.example:25601"),
                Bytes.fromHex("aabbccdd"),
                "0f8fad5b-d9cb-469f-a165-70867728950e",
                1_785_000_000_000L);
    }

    @Test
    @DisplayName("a link round-trips through its pasteable form")
    void uriRoundTrip() {
        WorldShareLink original = link();

        WorldShareLink back = WorldShareLink.parse(original.toUri()).orElseThrow();

        assertThat(back.worldId()).isEqualTo(original.worldId());
        assertThat(back.name()).isEqualTo(original.name());
        assertThat(back.kind()).isEqualTo(original.kind());
        assertThat(back.trackers()).isEqualTo(original.trackers());
        assertThat(back.rendezvous()).isEqualTo(original.rendezvous());
        assertThat(back.worldPublicKey()).isEqualTo(original.worldPublicKey());
        assertThat(back.hostNodeId()).isEqualTo(original.hostNodeId());
    }

    @Test
    @DisplayName("a world name with spaces and punctuation survives being pasted")
    void namesAreEscaped() {
        WorldShareLink original = new WorldShareLink(worldId(), "Bob & Alice's #1 World?",
                WorldShareLink.KIND_LAN, List.of(), List.of(), Bytes.empty(), "", 1L);

        String uri = original.toUri();

        // The parameters after the name must still be parameters — an unescaped '&' or '?' would
        // silently truncate the link at the first one, which is the kind of bug that only shows up
        // for the person whose world is called something with an ampersand in it.
        assertThat(uri).doesNotContain("Bob & Alice");
        assertThat(WorldShareLink.parse(uri).orElseThrow().name()).isEqualTo("Bob & Alice's #1 World?");
    }

    @Test
    @DisplayName("the magnet spelling is accepted, because that is what people paste")
    void magnetSchemeIsAccepted() {
        String uri = link().toUri().replace("nodera:?", "magnet:?");

        assertThat(WorldShareLink.parse(uri)).isPresent();
    }

    @Test
    @DisplayName("a LAN invitation says it is one")
    void lanLinksAreDistinguishable() {
        WorldShareLink lan = new WorldShareLink(worldId(), "Open to LAN", WorldShareLink.KIND_LAN,
                List.of(), List.of(), Bytes.empty(), "", 1L);

        // The two behave completely differently on arrival: one is joinable only while the host's
        // game is open, the other survives it. A receiver has to be able to tell.
        assertThat(lan.isLan()).isTrue();
        assertThat(WorldShareLink.parse(lan.toUri()).orElseThrow().isLan()).isTrue();
        assertThat(link().isLan()).isFalse();
    }

    @Test
    @DisplayName("anything that is not an invitation is refused")
    void malformedLinksAreRefused() {
        assertThat(WorldShareLink.parse(null)).isEmpty();
        assertThat(WorldShareLink.parse("")).isEmpty();
        assertThat(WorldShareLink.parse("https://example.com/world")).isEmpty();
        assertThat(WorldShareLink.parse("nodera:?dn=no+world+id")).isEmpty();
        assertThat(WorldShareLink.parse("nodera:?xt=urn:btih:aabbcc")).isEmpty();
        assertThat(WorldShareLink.parse("nodera:?xt=urn:nodera:nothex")).isEmpty();
        assertThat(WorldShareLink.parse("nodera:?xt=urn:nodera:")).isEmpty();
    }

    @Test
    @DisplayName("a mangled public key costs the badge, not the invitation")
    void anUnreadableKeyDoesNotDiscardTheLink() {
        String uri = "nodera:?xt=urn:nodera:" + worldId().toHex() + "&pk=not-base-64!!";

        WorldShareLink parsed = WorldShareLink.parse(uri).orElseThrow();

        // The link's job is to get you to the world; the key is verified against the network anyway,
        // so refusing the whole invitation over it would trade a working join for a cosmetic check.
        assertThat(parsed.worldPublicKey()).isEqualTo(Bytes.empty());
        assertThat(parsed.worldId()).isEqualTo(worldId());
    }

    @Test
    @DisplayName("the file form round-trips exactly")
    void fileRoundTrip() {
        WorldShareLink original = link();
        CanonicalWriter w = new CanonicalWriter();
        original.encode(w);

        WorldShareLink back = WorldShareLink.decode(new CanonicalReader(w.toBytes()));

        assertThat(back).isEqualTo(original);
    }

    @Test
    @DisplayName("an invitation must name a world")
    void aLinkWithoutAWorldIsNotALink() {
        assertThatThrownBy(() -> new WorldShareLink(Bytes.empty(), "x", WorldShareLink.KIND_WORLD,
                List.of(), List.of(), Bytes.empty(), "", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name a world");
    }

    @Test
    @DisplayName("a link carries no content and no secret")
    void nothingSensitiveIsInTheLink() {
        String uri = link().toUri();

        // Everything in an invitation is public by construction: an id, a name, some addresses, and
        // a public key. Anything else would make forwarding one a mistake.
        assertThat(uri).doesNotContain("password").doesNotContain("private");
        assertThat(uri).startsWith("nodera:?xt=urn%3Anodera%3A");
    }
}
