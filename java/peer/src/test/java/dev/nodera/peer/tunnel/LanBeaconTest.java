package dev.nodera.peer.tunnel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading what an <b>unmodified</b> Minecraft says when a player opens a world to LAN.
 *
 * <p>This parser is the entire reason a player with no mod installed can be part of this network:
 * their game already broadcasts everything needed to reach it, and has for a decade. Getting this
 * wrong means the feature silently does not work for exactly the people it exists for, so the cases
 * below are real payloads and real edge cases rather than tidy ones.
 */
final class LanBeaconTest {

    @Test
    @DisplayName("the payload vanilla actually sends")
    void parsesAVanillaBeacon() {
        LanBeacon beacon = LanBeacon.parse("[MOTD]Steve's world[/MOTD][AD]54321[/AD]").orElseThrow();

        assertThat(beacon.motd()).isEqualTo("Steve's world");
        assertThat(beacon.port()).isEqualTo(54321);
    }

    @Test
    @DisplayName("a MOTD with formatting codes and brackets survives")
    void theNameIsUserSuppliedTextAndIsNotSanitised() {
        LanBeacon beacon = LanBeacon
                .parse("[MOTD]§6[Hardcore] Bob's §lWorld§r[/MOTD][AD]25565[/AD]").orElseThrow();

        // A world name is whatever the player typed plus whatever the launcher added. Rejecting it
        // for containing a bracket would drop real worlds.
        assertThat(beacon.motd()).isEqualTo("§6[Hardcore] Bob's §lWorld§r");
        assertThat(beacon.port()).isEqualTo(25565);
    }

    @Test
    @DisplayName("a beacon with no name is still a joinable world")
    void aMissingNameIsNotAMissingWorld() {
        LanBeacon beacon = LanBeacon.parse("[AD]12345[/AD]").orElseThrow();

        // The port is the part that makes it reachable; discarding the beacon for want of a label
        // would lose a world somebody is waiting in.
        assertThat(beacon.port()).isEqualTo(12345);
        assertThat(beacon.motd()).contains("12345");
    }

    @Test
    @DisplayName("anything without a usable port is not a beacon")
    void malformedPayloadsAreRejected() {
        assertThat(LanBeacon.parse(null)).isEmpty();
        assertThat(LanBeacon.parse("")).isEmpty();
        assertThat(LanBeacon.parse("[MOTD]No port here[/MOTD]")).isEmpty();
        assertThat(LanBeacon.parse("[MOTD]x[/MOTD][AD]notaport[/AD]")).isEmpty();
        assertThat(LanBeacon.parse("[MOTD]x[/MOTD][AD]0[/AD]")).isEmpty();
        assertThat(LanBeacon.parse("[MOTD]x[/MOTD][AD]70000[/AD]")).isEmpty();
        assertThat(LanBeacon.parse("some other program's multicast chatter")).isEmpty();
    }

    @Test
    @DisplayName("a beacon round-trips through its own encoding")
    void roundTrip() {
        LanBeacon original = new LanBeacon("A Nice World", 40000);

        assertThat(LanBeacon.parse(original.encode())).contains(original);
    }

    @Test
    @DisplayName("the group and port are vanilla's, not ours to choose")
    void theGroupIsFixedByMinecraft() {
        // Changing either of these would mean listening where no Minecraft is talking.
        assertThat(LanBeacon.GROUP).isEqualTo("224.0.2.60");
        assertThat(LanBeacon.PORT).isEqualTo(4445);
    }
}
