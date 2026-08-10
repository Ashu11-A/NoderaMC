package dev.nodera.testkit.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Who is holding a TCP port, said in words a reader can act on.
 *
 * <h2>Why a diagnosis and not a port number</h2>
 *
 * <p>The preflight used to fail with {@code port 25610 is still held after 60s — stop the other
 * stack first (scripts/dev.sh? a stale client JVM?)}. Every one of those hints was wrong on the
 * machine where it mattered most: the process holding the port was the developer's own installed
 * companion app, doing exactly what it is supposed to do. "Quit the product you are developing" and
 * "kill a leftover from the last run" are opposite actions, and the message pointed at the second
 * one for a year, which is how a live report of 17 identical port failures was filed as stale-stack
 * noise rather than as the harness colliding with the product.
 *
 * <p>So this class answers three questions instead of one: which process, what is it, and is it
 * inside this checkout. A binary under the repository root is this harness's own leftover and can be
 * killed. A binary under {@code /usr}, {@code /opt} or the user's home is an installation, and the
 * right advice is to quit it — or, better, to leave it alone and move the harness's block, which is
 * what {@link Topology#chosenBase()} now does automatically.
 *
 * <h2>How it looks</h2>
 *
 * <p>{@code /proc/net/tcp} and {@code /proc/net/tcp6} give the socket inode of every listener; a
 * scan of {@code /proc/<pid>/fd} maps that inode to a process. No {@code ss}, no {@code lsof}, no
 * shelling out to a tool that may not be installed — and no failure if the kernel does not offer
 * {@code /proc}, in which case the caller still gets the port number it always had.
 *
 * <p>Thread-context: stateless; every method reads {@code /proc} on the calling thread.
 *
 * @param pid     the process holding the port.
 * @param command its command line, or its {@code comm} when the command line is unreadable.
 * @param exe     the resolved executable path, or empty when it cannot be read.
 */
public record PortHolder(long pid, String command, String exe) {

    private static final Path PROC = Path.of("/proc");
    /** {@code /proc/net/tcp}'s connection state for LISTEN. */
    private static final String LISTEN = "0A";

    /**
     * The full sentence for a port that would not come free.
     *
     * @param port    the port that stayed busy.
     * @param timeout how long it was waited for; {@link Duration#ZERO} drops the "after Ns" clause.
     */
    public static String describeHeldPort(int port, Duration timeout) {
        String waited = timeout.isZero() ? "port " + port + " is held"
                : "port " + port + " is still held after " + timeout.toSeconds() + "s";
        return waited + " by " + of(port).map(PortHolder::describe)
                .orElse("a process this JVM cannot identify (no readable /proc entry — "
                        + "try `ss -lntp | grep " + port + "`)");
    }

    /** Who is listening on {@code port} on this machine, if that can be determined. */
    public static Optional<PortHolder> of(int port) {
        for (long inode : listeningInodes(port)) {
            Optional<PortHolder> holder = processHolding(inode);
            if (holder.isPresent()) {
                return holder;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this looks like an installed Nodera rather than something this harness started.
     *
     * <p>Deliberately two conditions and not one: a leftover worker from the previous run is also a
     * Nodera binary, and telling somebody to quit their app when the real answer is to kill a
     * zombie is the same class of wrong message this class exists to remove. So "installed" means
     * Nodera <b>and</b> outside the working directory the harness was launched from.
     */
    public boolean looksLikeAnInstall() {
        return isNodera() && !isInsideThisCheckout();
    }

    /** Whether the holder is any Nodera binary at all, installed or built here. */
    public boolean isNodera() {
        return !noderaEvidence().isBlank();
    }

    /**
     * The token that made this a Nodera process, or blank.
     *
     * <p>Quoted in the message rather than merely asserted, because on a JVM-hosted install the
     * executable is {@code /usr/lib/jvm/.../java} and a reader told "this is an installed Nodera"
     * with that path in front of them has every reason not to believe it. The evidence is the
     * classpath entry or script path that carries the name.
     */
    public String noderaEvidence() {
        for (String token : (exe + " " + command).split("[\\s:]+")) {
            if (token.toLowerCase(Locale.ROOT).contains("nodera")) {
                return token;
            }
        }
        return "";
    }

    /**
     * Whether the holder's executable — or the Nodera artefact on its command line — lives under
     * the directory this JVM was started in, i.e. this checkout.
     */
    public boolean isInsideThisCheckout() {
        String root = System.getProperty("user.dir", "");
        if (root.isBlank()) {
            return false;
        }
        return exe.startsWith(root) || noderaEvidence().startsWith(root);
    }

    /** One clause naming the process and what it means for the reader. */
    public String describe() {
        String raw = exe.isBlank() ? command : exe;
        String what = raw.length() > 120 ? raw.substring(0, 117) + "..." : raw;
        String verdict;
        if (looksLikeAnInstall()) {
            verdict = "an INSTALLED Nodera (" + noderaEvidence() + "), not a leftover test stack "
                    + "— quit the app, or leave it running and let the harness move its own block";
        } else if (isNodera()) {
            verdict = "a Nodera binary from inside this checkout (" + noderaEvidence()
                    + ") — a leftover run; kill " + pid;
        } else {
            verdict = "not a Nodera process; stop it or move the harness's block";
        }
        return "pid " + pid + " → " + what + " (" + verdict + ")";
    }

    // ---------------------------------------------------------------------------------------

    /** Socket inodes of every LISTEN entry on {@code port}, from both the v4 and v6 tables. */
    private static List<Long> listeningInodes(int port) {
        List<Long> inodes = new ArrayList<>();
        for (String table : List.of("net/tcp", "net/tcp6")) {
            Path file = PROC.resolve(table);
            if (!Files.isReadable(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file)) {
                    // sl  local_address rem_address st tx_queue:rx_queue tr:when retrnsmt uid …
                    // inode is field 9 (0-based) once the header line is skipped.
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length < 10 || !fields[3].equalsIgnoreCase(LISTEN)) {
                        continue;
                    }
                    int colon = fields[1].lastIndexOf(':');
                    if (colon < 0 || Integer.parseInt(fields[1].substring(colon + 1), 16) != port) {
                        continue;
                    }
                    inodes.add(Long.parseLong(fields[9]));
                }
            } catch (IOException | NumberFormatException unreadable) {
                // A table that cannot be read or parsed contributes nothing; the caller falls back
                // to the port number, which is what it always had.
            }
        }
        return inodes;
    }

    /** The process owning a socket inode, found by scanning every readable {@code /proc/<pid>/fd}. */
    private static Optional<PortHolder> processHolding(long inode) {
        String socket = "socket:[" + inode + "]";
        try (Stream<Path> entries = Files.list(PROC)) {
            return entries.filter(PortHolder::isPidDirectory)
                    .filter(dir -> ownsSocket(dir, socket))
                    .findFirst()
                    .map(PortHolder::read);
        } catch (IOException noProc) {
            return Optional.empty();
        }
    }

    private static boolean isPidDirectory(Path path) {
        String name = path.getFileName().toString();
        return !name.isEmpty() && name.chars().allMatch(Character::isDigit);
    }

    private static boolean ownsSocket(Path pidDir, String socket) {
        try (Stream<Path> fds = Files.list(pidDir.resolve("fd"))) {
            return fds.anyMatch(fd -> {
                try {
                    return Files.readSymbolicLink(fd).toString().equals(socket);
                } catch (IOException goneOrForbidden) {
                    return false;
                }
            });
        } catch (IOException notOurs) {
            // Another user's process, or one that exited mid-scan. Either way it is not something
            // this run can be told to stop by pid, so it is simply not a match.
            return false;
        }
    }

    private static PortHolder read(Path pidDir) {
        long pid = Long.parseLong(pidDir.getFileName().toString());
        return new PortHolder(pid, commandLine(pidDir), executable(pidDir));
    }

    /**
     * The holder's command line, truncated for a message.
     *
     * <p>{@code /proc/<pid>/cmdline} is NUL-separated and the kernel truncates it at 4096 bytes —
     * the same truncation that once hid every Minecraft client JVM from this harness's process
     * matching. It is kept whole here and shortened only where it is printed: the token that
     * identifies a JVM-hosted install is a classpath entry, which is routinely past the first
     * hundred characters, so truncating on read would classify the process by the part of its
     * command line that says nothing.
     */
    private static String commandLine(Path pidDir) {
        try {
            String raw = Files.readString(pidDir.resolve("cmdline")).replace('\0', ' ').trim();
            if (!raw.isBlank()) {
                return raw;
            }
        } catch (IOException unreadable) {
            // Kernel threads and processes owned by another user have no readable cmdline.
        }
        try {
            return Files.readString(pidDir.resolve("comm")).trim();
        } catch (IOException stillUnreadable) {
            return "unknown";
        }
    }

    private static String executable(Path pidDir) {
        try {
            return Files.readSymbolicLink(pidDir.resolve("exe")).toString();
        } catch (IOException forbidden) {
            return "";
        }
    }
}
