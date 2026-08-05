package dev.nodera.endpoint.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What a save last <i>proved</i> about whether this installation authored it.
 *
 * <h2>Why "I cannot check" must not mean "no"</h2>
 *
 * <p>Authorship is decided by asking the always-on worker for its node id and comparing it with the
 * author recorded in {@code nodera-world.dat}. That is a question to a separate process over a
 * socket, and it has three answers, not two: yes, no, and <i>not right now</i> — the worker is
 * restarting, the control socket timed out, the companion was closed.
 *
 * <p>The third answer used to be folded into "no". The consequence was not a warning or a degraded
 * feature: the author of a world lost the ability to administer it, and could not get it back,
 * because the command that grants operator status is itself gated on holding it. A transient socket
 * failure became a permanent-feeling loss of control over somebody's own world.
 *
 * <p>So the answer is written down the first time it is genuinely established, next to the save it
 * is about, and a check that cannot run falls back to it. This is a <b>memo, not a credential</b>:
 * it is consulted only when the authoritative check is unavailable, it is overwritten by the next
 * real check in either direction, and it confers nothing on the network — every peer still verifies
 * signatures against the world's author key and has never heard of this file.
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
