package dev.nodera.mod.common;

import dev.nodera.mod.common.WorkerStateParser.HostedWorldInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dependency-free reader for the worker's {@code NODERA-STATE} {@code connected_worlds} array —
 * exercised against JSON shaped exactly like {@code WorkerControlHandler.stateJson} emits, including
 * the empty case, multiple worlds, escaped names, and malformed input (must never throw).
 */
final class WorkerStateParserTest {

    @Test
    void readsMultipleHostedWorlds() {
        String json = "{\"node_id\":\"abc\",\"connected_worlds\":["
                + "{\"world_id\":\"deadbeef\",\"name\":\"Caveman World\",\"players\":3},"
                + "{\"world_id\":\"cafe01\",\"name\":\"Skyblock\",\"players\":0}"
                + "],\"daemon_up\":true}";
        List<HostedWorldInfo> worlds = WorkerStateParser.connectedWorlds(json);
        assertThat(worlds).extracting(HostedWorldInfo::name)
                .containsExactly("Caveman World", "Skyblock");
        assertThat(worlds).extracting(HostedWorldInfo::worldId)
                .containsExactly("deadbeef", "cafe01");
        assertThat(worlds.get(0).players()).isEqualTo(3);
        assertThat(worlds.get(1).players()).isEqualTo(0);
    }

    @Test
    void readsTheGameEndpointWhenPresent() {
        String json = "{\"connected_worlds\":["
                + "{\"world_id\":\"aa\",\"name\":\"Open\",\"players\":1,\"mc_route\":\"10.0.0.5:25565\"},"
                + "{\"world_id\":\"bb\",\"name\":\"Closed\",\"players\":0,\"mc_route\":\"\"},"
                + "{\"world_id\":\"cc\",\"name\":\"Legacy\",\"players\":0}"
                + "]}";
        List<HostedWorldInfo> worlds = WorkerStateParser.connectedWorlds(json);
        assertThat(worlds).extracting(HostedWorldInfo::mcRoute)
                .containsExactly("10.0.0.5:25565", "", "");
    }

    @Test
    void emptyArrayYieldsNoWorlds() {
        String json = "{\"connected_worlds\":[],\"daemon_up\":true}";
        assertThat(WorkerStateParser.connectedWorlds(json)).isEmpty();
    }

    @Test
    void decodesEscapedNames() {
        String json = "{\"connected_worlds\":[{\"world_id\":\"aa\",\"name\":\"My \\\"Cool\\\" World\","
                + "\"players\":1}]}";
        assertThat(WorkerStateParser.connectedWorlds(json))
                .singleElement()
                .extracting(HostedWorldInfo::name)
                .isEqualTo("My \"Cool\" World");
    }

    @Test
    void nameWithBracketsDoesNotBreakArrayScan() {
        // A '}' or ']' inside the name string must not be mistaken for the object/array end.
        String json = "{\"connected_worlds\":[{\"world_id\":\"aa\",\"name\":\"weird ] } name\","
                + "\"players\":2},{\"world_id\":\"bb\",\"name\":\"second\",\"players\":0}]}";
        List<HostedWorldInfo> worlds = WorkerStateParser.connectedWorlds(json);
        assertThat(worlds).hasSize(2);
        assertThat(worlds.get(0).name()).isEqualTo("weird ] } name");
        assertThat(worlds.get(1).name()).isEqualTo("second");
    }

    @Test
    void malformedOrMissingInputNeverThrows() {
        assertThat(WorkerStateParser.connectedWorlds(null)).isEmpty();
        assertThat(WorkerStateParser.connectedWorlds("")).isEmpty();
        assertThat(WorkerStateParser.connectedWorlds("NODERA-ERR nope")).isEmpty();
        assertThat(WorkerStateParser.connectedWorlds("{\"connected_worlds\":[{oops")).isEmpty();
        assertThat(WorkerStateParser.connectedWorlds("{\"other\":1}")).isEmpty();
    }
}
