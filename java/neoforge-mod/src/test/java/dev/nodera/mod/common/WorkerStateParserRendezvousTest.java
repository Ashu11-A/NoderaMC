package dev.nodera.mod.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read half of L-84: the mod takes its rendezvous list from the worker's live, measured
 * selection instead of a static config list read once at world open.
 *
 * <p>These assert against the exact JSON shape {@code WorkerControlHandler.stateJson} emits — the
 * worker's STATE line is called "the single wire contract the Rust companion parses" there, and this
 * parser is a second reader of it. A test that invented its own plausible shape would pass against a
 * format nothing produces.
 */
class WorkerStateParserRendezvousTest {

    /** One endpoint row exactly as {@code WorkerControlHandler.endpointArray} writes it. */
    private static String row(String host, int port, String scheme, boolean reachable, int latency) {
        return "{\"host\":\"" + host + "\",\"port\":" + port + ",\"scheme\":\"" + scheme
                + "\",\"reachable\":" + reachable + ",\"latency_ms\":" + latency + "}";
    }

    private static String state(String... rows) {
        return "{\"protocol\":2,\"connected_worlds\":[],\"rendezvous\":["
                + String.join(",", rows) + "],\"peers\":0}";
    }

    @Test
    @DisplayName("the worker's selection is read in the order it chose")
    void readsTheSelectionInOrder() {
        // The worker already sorted these best-first from its own probes. Re-sorting here would
        // discard the measurement and replace it with an opinion this side has no evidence for.
        List<String> routes = WorkerStateParser.rendezvousRoutes(state(
                row("relay-a.example.org", 7500, "tcp", true, 21),
                row("relay-b.example.org", 7500, "tcp", true, 94)));

        assertEquals(List.of("tcp://relay-a.example.org:7500", "tcp://relay-b.example.org:7500"),
                routes);
    }

    @Test
    @DisplayName("a relay the worker could not reach is not handed to the transport")
    void unreachableRelaysAreDropped() {
        // The worker reports on these rather than recommending them. Composing a transport from one
        // would be worse than falling back to the configured list.
        List<String> routes = WorkerStateParser.rendezvousRoutes(state(
                row("dead.example.org", 7500, "tcp", false, -1),
                row("live.example.org", 7500, "tcp", true, 30)));

        assertEquals(List.of("tcp://live.example.org:7500"), routes);
    }

    @Test
    @DisplayName("no reachable relay means no opinion, so the caller falls back")
    void noReachableRelayYieldsEmpty() {
        assertTrue(WorkerStateParser.rendezvousRoutes(
                state(row("dead.example.org", 7500, "tcp", false, -1))).isEmpty());
    }

    @Test
    @DisplayName("an older worker without the field costs the player nothing")
    void aStateWithoutTheFieldIsEmpty() {
        // The mod and the worker are updated separately. A worker whose STATE predates this field
        // must leave the mod on its configured list, not on nothing.
        assertTrue(WorkerStateParser.rendezvousRoutes(
                "{\"protocol\":2,\"connected_worlds\":[],\"peers\":0}").isEmpty());
        assertTrue(WorkerStateParser.rendezvousRoutes(null).isEmpty());
        assertTrue(WorkerStateParser.rendezvousRoutes("not json at all").isEmpty());
    }

    @Test
    @DisplayName("a malformed row is skipped without taking the rest of the list with it")
    void aMalformedRowDoesNotDiscardTheGoodOnes() {
        List<String> routes = WorkerStateParser.rendezvousRoutes(state(
                "{\"host\":\"\",\"port\":7500,\"scheme\":\"tcp\",\"reachable\":true,\"latency_ms\":5}",
                "{\"host\":\"nop.example.org\",\"port\":0,\"scheme\":\"tcp\",\"reachable\":true,\"latency_ms\":5}",
                row("good.example.org", 7500, "tcp", true, 12)));

        assertEquals(List.of("tcp://good.example.org:7500"), routes);
    }

    @Test
    @DisplayName("a missing scheme defaults to tcp rather than producing an unparseable route")
    void aMissingSchemeDefaultsToTcp() {
        List<String> routes = WorkerStateParser.rendezvousRoutes(state(
                "{\"host\":\"relay.example.org\",\"port\":7500,\"scheme\":\"\","
                        + "\"reachable\":true,\"latency_ms\":8}"));

        assertEquals(List.of("tcp://relay.example.org:7500"), routes);
    }

    @Test
    @DisplayName("the rendezvous array is not confused with the worlds array beside it")
    void doesNotReadTheWrongArray() {
        // Both arrays are objects with string fields in one flat line; a scanner that found the
        // first '[' after the wrong key would silently read world rows as relays.
        String json = "{\"connected_worlds\":[{\"world_id\":\"ab\",\"name\":\"host\",\"players\":1,"
                + "\"mc_route\":\"1.2.3.4:25565\"}],\"rendezvous\":["
                + row("relay.example.org", 7500, "tcp", true, 15) + "]}";

        assertEquals(List.of("tcp://relay.example.org:7500"),
                WorkerStateParser.rendezvousRoutes(json));
        assertEquals(1, WorkerStateParser.connectedWorlds(json).size());
    }

    @Test
    @DisplayName("every route the parser emits is one RendezvousEndpoint can parse")
    void everyRouteIsParseableByTheTransport() {
        // The contract between the two halves of this lane: whatever comes out of here is handed
        // straight to RendezvousEndpoint.parse, and a form it rejects would be dropped with a
        // warning and leave the transport on a stale list.
        for (String route : WorkerStateParser.rendezvousRoutes(state(
                row("relay.example.org", 7500, "tcp", true, 15),
                row("192.0.2.10", 7500, "tcp", true, 40)))) {
            dev.nodera.transport.rendezvous.RendezvousEndpoint.parse(route);
        }
    }
}
