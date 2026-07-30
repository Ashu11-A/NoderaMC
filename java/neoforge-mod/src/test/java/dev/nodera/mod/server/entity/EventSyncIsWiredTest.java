package dev.nodera.mod.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9's forward event-sync was a complete, tested capability with no production caller — and
 * network L-30 cited its integration test ({@code EventSyncOverTransportIT}) as headless evidence,
 * so a register was resting on a mechanism no peer ran. That is the same shape as the R2/R3
 * handshake correction: green tests over an unreachable class.
 *
 * <p>The fault is an <b>absence</b>, which no behavioural test in this module can observe — the live
 * session needs a Minecraft server. So the call sites are asserted directly, the shape
 * {@code CompanionSessionBindingIsCalledTest} established for exactly this problem.
 */
final class EventSyncIsWiredTest {

    private static String read(String relative) throws IOException {
        Path direct = Path.of("src/main/java").resolve(relative);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("java/neoforge-mod/src/main/java").resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("cannot locate " + relative);
    }

    private static final String SESSION =
            "dev/nodera/mod/server/entity/LiveEntityLaneSession.java";

    @Test
    @DisplayName("the live session constructs the sync service over its own store and transport")
    void theSyncServiceExists() throws IOException {
        assertThat(read(SESSION))
                .as("a sync service built from anything but the session's own store would answer "
                        + "for a world it does not hold")
                .contains("new EventSyncService(store, host.transport())");
    }

    @Test
    @DisplayName("inbound sync messages reach it: the application lane asks sync before validation")
    void theServeHalfIsOnTheDispatchArm() throws IOException {
        assertThat(read(SESSION))
                .as("without this arm a peer's EventSyncQuery is delivered to a lane that ignores it")
                .contains("if (!sync.onMessage(from, message))");
    }

    @Test
    @DisplayName("the session asks its committee peers for what it missed")
    void theAskHalfRuns() throws IOException {
        String session = read(SESSION);
        assertThat(session)
                .as("a serve-only wiring is still a mechanism this node never uses")
                .contains("catchUp(sync, store, regions, peers)")
                .contains("sync.requestFrom(peer.address()");
    }

    @Test
    @DisplayName("a cold region is not synced — an empty log would ask for an unbounded answer")
    void aColdRegionIsNotAsked() throws IOException {
        assertThat(read(SESSION))
                .as("lastEventId is -1 for an empty log; asking from there requests the whole "
                        + "history in one frame-bounded answer")
                .contains("if (since < 0)");
    }
}
