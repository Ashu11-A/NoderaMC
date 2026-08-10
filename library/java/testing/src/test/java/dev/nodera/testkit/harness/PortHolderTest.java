package dev.nodera.testkit.harness;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three answers a held port can have, and the three different actions they imply.
 *
 * <p>"Quit the product you are developing", "kill a leftover from the last run" and "something
 * unrelated is on this port" are opposite instructions, and the preflight used to give none of
 * them — it said {@code port 25610 is still held … (scripts/dev.sh? a stale client JVM?)} and both
 * hints were wrong on the machine where it mattered. This test pins the classification rather than
 * the wording, so the sentence can be improved without the diagnosis drifting.
 *
 * <p>The lookup itself is not mocked here: it reads {@code /proc}, and a test that faked that would
 * be testing its own fake. What is tested is what the harness does with what it found.
 *
 * <p>Thread-context: ordinary JUnit.
 */
class PortHolderTest {

    private static final String INSTALLED_APP = "/usr/lib/jvm/java-25-openjdk-amd64/bin/java "
            + "-Xms64m -cp /usr/lib/Nodera/resources/nodera-headless.jar dev.nodera.headless.Main";

    @Test
    void anInstalledNoderaIsNotReportedAsALeftoverTestStack() {
        PortHolder holder = new PortHolder(742938, INSTALLED_APP,
                "/usr/lib/jvm/java-25-openjdk-amd64/bin/java");

        assertThat(holder.isNodera()).isTrue();
        assertThat(holder.isInsideThisCheckout()).isFalse();
        assertThat(holder.looksLikeAnInstall()).isTrue();
        assertThat(holder.describe())
                .contains("pid 742938")
                .contains("INSTALLED Nodera")
                // The evidence is quoted because the executable is the JVM: a reader told "this is
                // Nodera" while looking at /usr/lib/jvm/... has every reason to disbelieve it.
                .contains("/usr/lib/Nodera/resources/nodera-headless.jar")
                .doesNotContain("leftover run");
    }

    @Test
    void aBinaryFromThisCheckoutIsReportedAsALeftoverToKill() {
        String root = System.getProperty("user.dir");
        PortHolder holder = new PortHolder(4242,
                root + "/peer/build/install/nodera-headless/bin/nodera-headless --test-mode",
                root + "/peer/build/install/nodera-headless/bin/nodera-headless");

        assertThat(holder.looksLikeAnInstall()).isFalse();
        assertThat(holder.describe()).contains("leftover run").contains("kill 4242");
    }

    @Test
    void somethingElseEntirelyIsNotBlamedOnNodera() {
        PortHolder holder = new PortHolder(9, "/usr/bin/postgres -D /var/lib/postgres",
                "/usr/bin/postgres");

        assertThat(holder.isNodera()).isFalse();
        assertThat(holder.describe()).contains("not a Nodera process");
    }

    @Test
    void aPortNobodyIsHoldingStillProducesAUsableSentence() {
        // Port 0 is never a listener, so the lookup finds nothing — and the message must still say
        // which port and how the reader can look for themselves.
        String message = PortHolder.describeHeldPort(0, Duration.ofSeconds(60));

        assertThat(message).contains("port 0 is still held after 60s")
                .contains("cannot identify")
                .contains("ss -lntp");
    }
}
