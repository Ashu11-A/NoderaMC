package dev.nodera.endpoint.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What a save last <i>proved</i> about whether this installation authored it: a fallback for when
 * the authoritative worker check cannot run right now, not a substitute for it. Full rationale (why
 * "cannot check" must not collapse to "no", and the incident that motivated this class):
 * {@code docs/peer/Task.8.md} §"Why 'I cannot check' must not mean 'no'". This is a <b>memo, not a
 * credential</b> — it confers nothing on the network, which still verifies every signature against
 * the world's author key.
 *
 * @Thread-context static helpers over a small file; safe for any thread. Concurrent writers race to
 *                 write the same answer, which is harmless.
 */
public final class AuthorshipMemo {

    /** The memo's file name, inside the save's {@code nodera/} directory. */
    static final String FILE_NAME = "authorship";

    private AuthorshipMemo() {
    }

    private static Path fileIn(Path saveRoot) {
        return saveRoot.resolve("nodera").resolve(FILE_NAME);
    }

    /**
     * Record what an authoritative check established.
     *
     * @param saveRoot the world's save root.
     * @param isAuthor whether this installation's worker is the world's author.
     * @Thread-context any thread.
     */
    public static void remember(Path saveRoot, boolean isAuthor) {
        if (saveRoot == null) {
            return;
        }
        Path file = fileIn(saveRoot);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.write(temp, (isAuthor ? "author" : "not-author").getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException unwritable) {
            // A memo that cannot be written costs nothing today; the authoritative check still ran
            // and answered. It only costs the NEXT check that cannot reach the worker, which then
            // behaves as it did before this file existed.
        }
    }

    /**
     * The last authoritative answer for this save.
     *
     * @param saveRoot the world's save root.
     * @return what was last proven; {@code false} when nothing ever was, which is the conservative
     *         answer for a save this installation has no evidence of having authored.
     * @Thread-context any thread.
     */
    public static boolean lastProven(Path saveRoot) {
        if (saveRoot == null) {
            return false;
        }
        try {
            Path file = fileIn(saveRoot);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            return "author".equals(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
