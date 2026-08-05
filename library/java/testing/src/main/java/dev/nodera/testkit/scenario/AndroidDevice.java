package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A phone, addressed over {@code adb} — the only way a scenario is allowed to learn anything about
 * a device.
 *
 * <h2>Never the app's own UI</h2>
 *
 * <p>Everything here reads the worker's own control socket or its own logcat. A screen that says
 * "connected" is the app's opinion; {@code NODERA-STATE} is the worker's account of itself, and the
 * two disagree exactly when it matters. Every call is bounded — a phone that stops answering must
 * fail a step, never hang the run.
 *
 * <p>Extracted from {@code AndroidMeshScenario}, which held the only copy while it was the only
 * device scenario. The second one ({@code MobileContinuityScenario}) needs every one of these, and a
 * second copy of "how to talk to the phone" is how the two scenarios end up disagreeing about what
 * the phone said.
 *
 * <p>Thread-context: one instance per scenario run, used from the scenario's own thread. The serial
 * is instance state, so a runner executing the same scenario twice starts from nothing.
 */
public final class AndroidDevice {

    /** The Nodera app's package and its launcher activity. */
    public static final String PACKAGE = "dev.nodera.app";

    /** The activity to start; the worker lives inside this app's process. */
    public static final String ACTIVITY = PACKAGE + "/.MainActivity";

    /**
     * The phone's control endpoint.
     *
     * <p>Same port as every other worker, but inside the device — reachable only through adb, which
     * is the point: the control socket is unauthenticated by design and must never be on the LAN.
     */
    public static final int CONTROL_PORT = 25610;

    /** How long the control pipe is held open for a reply; see {@link #verb}. */
    private static final int REPLY_SECONDS = 8;

    /** How many times a blank state read is retried before it is believed; see {@link #state}. */
    private static final int STATE_ATTEMPTS = 3;

    private static final Pattern IPV4 = Pattern.compile("^\\d+\\.\\d+\\.\\d+\\.\\d+$");
    private static final Pattern SRC_ADDRESS = Pattern.compile("src (\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern GLOBAL_ADDRESS = Pattern.compile("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern DOCKER_RANGE =
            Pattern.compile("^172\\.(1[7-9]|2[0-9]|3[01])\\.");

    private String serial = "";

    /** The device this instance is bound to, or blank before {@link #resolveSerial()} has run. */
    public String serial() {
        return serial;
    }

    /** @return the phone's own IPv4 address, taken from the wireless serial. */
    public String ip() {
        return serial.split(":")[0];
    }

    /** @return whether the resolved serial names a device reachable BY IP. */
    public boolean isWireless() {
        return IPV4.matcher(ip()).matches();
    }

    // ---------------------------------------------------------------------------------------
    // adb
    // ---------------------------------------------------------------------------------------

    /** Run an adb command against this device and return its merged output. */
    public String adb(List<String> arguments) {
        return capture(adbCommand(arguments));
    }

    /** Run an adb command against this device and return its exit status. */
    public int adbExit(List<String> arguments) {
        return ServerDedicatedDrive.run(adbCommand(arguments));
    }

    private List<String> adbCommand(List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("adb"));
        if (!serial.isBlank()) {
            command.addAll(List.of("-s", serial));
        }
        command.addAll(arguments);
        return command;
    }

    /**
     * One control line to the worker ON THE PHONE, answered through adb.
     *
     * <h2>Why the pipe is held open for seconds and not for one</h2>
     *
     * <p>{@code nc} has no idea when a reply is finished, so the shell holds stdin open for a fixed
     * time and then closes the socket. At one second that was long enough for {@code NODERA-PROBE},
     * which answers from memory, and NOT long enough for {@code NODERA-STATE}: building the state
     * document reads every tracker's health, and a phone configured with a remote tracker spends
     * longer than that waiting on the network. The read then came back with <b>zero bytes</b> —
     * not a truncated document, nothing at all — and a scenario asking for {@code node_id} got an
     * empty optional forever while the worker was healthy and answering probes the whole time.
     *
     * <p>Measured on a Xiaomi 2210129SG: {@code sleep 1} → 0 bytes every time, {@code sleep 3} →
     * the full 2031-byte document sometimes, {@code sleep 12} → it reliably. The spread is
     * contention, not latency: the worker serves its control socket one connection at a time and
     * the app in the same process polls the state on its own cadence, so a read can spend most of
     * its window waiting for a turn. Hence a window with real margin AND {@link #state}'s retry —
     * an empty read here means "did not get a turn", and believing it means reporting a healthy
     * phone as silent.
     */
    public String verb(String verb) {
        String piped = "(printf '%s\\n' '" + verb + "'; sleep " + REPLY_SECONDS
                + ") | timeout " + (REPLY_SECONDS + 15) + " toybox nc 127.0.0.1 " + CONTROL_PORT;
        String reply = adb(List.of("shell", piped)).replace("\r", "");
        int newline = reply.indexOf('\n');
        return (newline < 0 ? reply : reply.substring(0, newline)).trim();
    }

    /**
     * One field out of the phone's {@code NODERA-STATE}.
     *
     * <p>Parsed, not grepped: the state is JSON and a regex over it would read {@code peers} out of
     * a nested object.
     *
     * <p>Costs a whole round trip. A caller reading several fields of the same moment should take
     * one {@link #stateDocument()} instead — not only for speed, but because two reads a dozen
     * seconds apart are two different moments and comparing them is how a counter appears to move
     * when nothing happened.
     *
     * @param path a dotted path, e.g. {@code total_received_bytes} or {@code connected_worlds.0.world_id}.
     */
    public Optional<String> stateField(String path) {
        return ServerJson.tryParse(state())
                .flatMap(document -> ServerJson.text(document, path));
    }

    /**
     * The phone's whole state document, as it answered it.
     *
     * <p>Retried while it comes back blank: see {@link #verb} — an empty reply is a read that never
     * got a turn on the control socket, and the difference between that and a dead worker is the
     * whole question a device scenario exists to answer.
     */
    public String state() {
        String reply = "";
        for (int attempt = 0; attempt < STATE_ATTEMPTS && reply.isBlank(); attempt++) {
            reply = verb("NODERA-STATE 2");
        }
        return reply;
    }

    /** The phone's state, parsed once, for a caller that reads more than one field of it. */
    public Object stateDocument() {
        return ServerJson.tryParse(state()).orElse(java.util.Map.of());
    }

    /** {@code adb logcat -d -s NoderaMC:V} — the worker's own account of itself. */
    public String logcat() {
        return adb(List.of("logcat", "-d", "-s", "NoderaMC:V"));
    }

    /** Restart the app, which is also what restarts the worker inside it. */
    public int restartApp() {
        adb(List.of("logcat", "-c"));
        adb(List.of("shell", "am", "force-stop", PACKAGE));
        return adbExit(List.of("shell", "am", "start", "-n", ACTIVITY));
    }

    /**
     * Whether the app's process is running right now.
     *
     * <p>Worth asking separately from anything else, because when it is not, every other question
     * answers wrongly in a way that points somewhere else. A run reported "the phone lost every
     * peer" and it was true — Android had killed the app 25 minutes in, and with it the worker,
     * which is a different finding entirely from a peer that dropped out of a mesh.
     */
    public boolean appIsRunning() {
        return !adb(List.of("shell", "pidof", PACKAGE)).trim().isEmpty();
    }

    /** The installed {@code versionName} of the Nodera app, or blank when it is not installed. */
    public String versionOnDevice() {
        for (String line : adb(List.of("shell", "dumpsys", "package", PACKAGE)).split("\n")) {
            int at = line.indexOf("versionName=");
            if (at >= 0) {
                return line.substring(at + "versionName=".length()).replace("\r", "").trim();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------------------------------
    // Addressing
    // ---------------------------------------------------------------------------------------

    /**
     * The device to drive.
     *
     * <p>Prefer a wireless entry: a device scenario needs the phone reachable BY IP, and a USB-only
     * device cannot be dialled by the Linux peers at all.
     *
     * @return the serial, which is also stored on this instance; blank when there is no device.
     */
    public String resolveSerial() {
        String configured = System.getenv("ANDROID_SERIAL");
        if (configured != null && !configured.isBlank()) {
            serial = configured.trim();
            return serial;
        }
        for (String line : capture(List.of("adb", "devices")).split("\n")) {
            if (line.contains(":5555") && line.trim().endsWith("device")) {
                serial = line.trim().split("\\s+")[0];
                return serial;
            }
        }
        serial = "";
        return serial;
    }

    /**
     * This machine's LAN address — what the phone must be told to dial.
     *
     * <p>Derived from the route the phone's own address is reached by, so a host with several
     * interfaces (docker bridges, VPNs) still advertises the right one.
     */
    public static String hostLanAddress(String phoneIp) {
        Matcher viaRoute = SRC_ADDRESS.matcher(
                capture(List.of("ip", "-4", "route", "get", phoneIp)));
        if (viaRoute.find()) {
            return viaRoute.group(1);
        }
        Matcher global = GLOBAL_ADDRESS.matcher(
                capture(List.of("ip", "-4", "addr", "show", "scope", "global")));
        while (global.find()) {
            String address = global.group(1);
            if (!DOCKER_RANGE.matcher(address).find()) {
                return address;
            }
        }
        return "";
    }

    /** Whether {@code host:port} accepts a connection within two seconds. */
    public static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException unreachable) {
            return false;
        }
    }

    /** Whether a command is on {@code PATH}. */
    public static boolean hasCommand(String command) {
        return ServerDedicatedDrive.run(List.of("which", command)) == 0;
    }

    /** Whether an environment variable is set to exactly {@code 1}. */
    public static boolean flag(String variable) {
        String value = System.getenv(variable);
        return value != null && value.trim().equals("1");
    }

    /** A JSON number field read as a long; anything unparseable reads as zero. */
    public static long asLong(String value) {
        try {
            return (long) Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    /** Run a command with a bound of one minute and return its output. */
    public static String capture(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            return output;
        } catch (IOException notInstalled) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while running "
                    + String.join(" ", command), e);
        }
    }
}
