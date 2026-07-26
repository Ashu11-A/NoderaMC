package dev.nodera.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointPlatformTest {

    @Test
    @DisplayName("the regionised scheduler is what identifies Folia, not a name")
    void foliaIsDetectedByItsScheduler() {
        EndpointPlatform platform = EndpointPlatform.detect(
                Set.of("io.papermc.paper.threadedregions.RegionizedServer")::contains);

        assertThat(platform).isEqualTo(EndpointPlatform.FOLIA);
        assertThat(platform.isRegionised()).isTrue();
    }

    @Test
    @DisplayName("Paper without regions is Paper")
    void paperIsDetectedByItsConfiguration() {
        EndpointPlatform platform = EndpointPlatform.detect(
                Set.of("io.papermc.paper.configuration.Configuration")::contains);

        assertThat(platform).isEqualTo(EndpointPlatform.PAPER);
        assertThat(platform.isRegionised()).isFalse();
    }

    @Test
    @DisplayName("an older Paper is still Paper")
    void theLegacyPaperConfigCounts() {
        assertThat(EndpointPlatform.detect(Set.of("com.destroystokyo.paper.PaperConfig")::contains))
                .isEqualTo(EndpointPlatform.PAPER);
    }

    @Test
    @DisplayName("anything else says so rather than guessing Paper")
    void anUnrecognisedPlatformIsUnknown() {
        EndpointPlatform platform = EndpointPlatform.detect(name -> false);

        assertThat(platform).isEqualTo(EndpointPlatform.UNKNOWN);
        assertThat(platform.isRegionised())
                .as("an unknown platform must not be treated as regionised on a guess")
                .isFalse();
        assertThat(platform.label()).isEqualTo("unknown Bukkit platform");
    }

    @Test
    @DisplayName("a Folia fork that also ships Paper's configuration is still Folia")
    void regionsWinOverConfiguration() {
        assertThat(EndpointPlatform.detect(Set.of(
                "io.papermc.paper.threadedregions.RegionizedServer",
                "io.papermc.paper.configuration.Configuration")::contains))
                .isEqualTo(EndpointPlatform.FOLIA);
    }
}
