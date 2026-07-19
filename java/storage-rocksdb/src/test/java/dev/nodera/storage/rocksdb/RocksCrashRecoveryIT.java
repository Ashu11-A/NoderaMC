package dev.nodera.storage.rocksdb;

import dev.nodera.core.event.CommittedEventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9 acceptance #6, the durable-tier half: a writer JVM is forcibly killed mid write-storm
 * (no shutdown hook, no clean close — the RocksDB LOCK file and WAL are abandoned as-is), and the
 * reopened store recovers clean: WAL replay yields a prefix of the log with no torn entry, ids
 * stay contiguous from 0, the {@code prevRoot → resultingRoot} chain is unbroken, the recovered
 * head equals the tail event, and the store accepts the next append on that head.
 */
class RocksCrashRecoveryIT {

    @TempDir
    Path dir;

    @Test
    void forciblyKilledWriterReopensCleanWithAnIntactChain() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process victim = new ProcessBuilder(
                java,
                "--enable-native-access=ALL-UNNAMED", // rocksdbjni loads its native library
                // The kill skips deleteOnExit, so the victim's native-lib extraction must land in
                // the build-dir tmpdir (see build.gradle.kts), never the system /tmp.
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp",
                System.getProperty("java.class.path"),
                WriteStormMain.class.getName(),
                dir.toString())
                .redirectErrorStream(true)
                .start();
        BufferedReader output = new BufferedReader(new InputStreamReader(victim.getInputStream()));
        // Skip any JVM warnings; the victim prints READY once its first append is durable.
        String line;
        int guard = 0;
        while ((line = output.readLine()) != null && !line.equals("READY")) {
            assertThat(++guard).as("victim produced noise without READY: %s", line).isLessThan(50);
        }
        assertThat(line).isEqualTo("READY");
        Thread.sleep(400); // let the storm run: thousands of WAL-backed appends in flight
        victim.destroyForcibly();
        assertThat(victim.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(victim.exitValue()).isNotZero();

        try (RocksWorldStore recovered = RocksWorldStore.open(
                dir, RocksFixtures.GENESIS, RocksFixtures.HASHES, false)) {
            long lastId = recovered.events().lastEventId(RocksFixtures.REGION);
            assertThat(lastId).isGreaterThanOrEqualTo(0); // READY was printed after event 0

            List<CommittedEventEnvelope> log = recovered.events().readFrom(RocksFixtures.REGION, 0);
            assertThat(log).hasSize((int) lastId + 1);
            for (int i = 0; i < log.size(); i++) {
                CommittedEventEnvelope event = log.get(i);
                assertThat(event.eventId()).isEqualTo(i);
                assertThat(event.prevRoot()).isEqualTo(RocksFixtures.chainRoot(i - 1));
                assertThat(event.resultingRoot()).isEqualTo(RocksFixtures.chainRoot(i));
            }
            assertThat(recovered.events().headRoot(RocksFixtures.REGION))
                    .contains(RocksFixtures.chainRoot(lastId));

            // The recovered store is live: the next chained append lands on the recovered head.
            recovered.events().append(RocksFixtures.chainedEvent(RocksFixtures.REGION, lastId + 1));
            assertThat(recovered.events().lastEventId(RocksFixtures.REGION)).isEqualTo(lastId + 1);
        }
    }

    /** The victim process: appends chained events as fast as possible until it is killed. */
    public static final class WriteStormMain {
        private WriteStormMain() {}

        public static void main(String[] args) {
            Path dir = Path.of(args[0]);
            RocksWorldStore store = RocksWorldStore.open(
                    dir, RocksFixtures.GENESIS, RocksFixtures.HASHES, false);
            long id = 0;
            store.events().append(RocksFixtures.chainedEvent(RocksFixtures.REGION, id++));
            System.out.println("READY");
            System.out.flush();
            while (true) {
                store.events().append(RocksFixtures.chainedEvent(RocksFixtures.REGION, id++));
            }
        }
    }
}
