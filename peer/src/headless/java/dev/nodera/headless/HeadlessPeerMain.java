package dev.nodera.headless;

/**
 * The {@code nodera-headless} entry point: parse argv, start a {@link PeerNode}, wait.
 *
 * <p>It is this short on purpose, and it is in its own source set for a reason worth stating.
 * {@code :peer} is zipped into the NeoForge mod's fat jar, so anything in {@code src/main} ships to
 * every player's {@code mods/} folder. The always-on SERVICES belong there — a peer that does not
 * run them is not a peer. A launchable {@code main} does not. {@code tasks.jar} carries {@code main}
 * alone, so this class physically cannot reach the mod jar, and
 * {@code ModJarCarriesNoEntryPointTest} asserts that on the built artefact rather than on the build
 * script.
 *
 * <p>Everything that used to be here — the twenty-five environment reads, the twelve services, the
 * shutdown order — moved into {@link PeerNode}, in the library, where every other embedder can and
 * must use it.
 *
 * <p>The Tauri companion app ({@code rust/nodera-app}) supervises this process; {@code scripts/dev.sh}
 * runs it directly for development. The launcher name {@code nodera-headless} is contract with both,
 * with {@code tauri.*.conf.json}, and with CI.
 */
public final class HeadlessPeerMain {

    private HeadlessPeerMain() {
    }

    /**
     * @param args {@code [--test-mode] [--role player1|player2|player3|spare] [--debug]}. Everything
     *             else about a node comes from the environment, because the supervisor that starts
     *             it passes an environment and not an argv.
     */
    public static void main(String[] args) throws Exception {
        TestMode testMode;
        try {
            testMode = TestMode.fromArgs(args);
        } catch (IllegalArgumentException badUsage) {
            System.err.println(badUsage.getMessage());
            System.exit(64);
            return;
        }
        // try-with-resources AND the shutdown hook PeerNode.start installs. Both run on a normal
        // exit; close() is idempotent so that is fine, and either one alone is enough — which is
        // what makes a kill -TERM and a returned main unwind the same way.
        try (PeerNode node = PeerNode.start(testMode)) {
            node.await();
        }
    }
}
