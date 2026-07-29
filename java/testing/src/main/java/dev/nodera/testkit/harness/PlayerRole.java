package dev.nodera.testkit.harness;

import java.util.Locale;

/**
 * Which player a worker belongs to in an integration run.
 *
 * <h2>Why a worker needs to know this at all</h2>
 *
 * <p>Outside a test, a worker is anonymous: it is one player's always-on node and has no opinion
 * about who else is on the network. Inside an integration run, every assertion is of the form
 * "player one's node should now hold what player two's node published" — and until the run could
 * name the two nodes, that assertion was made by remembering which control port was started first.
 * A port number is not an identity: renumber the plan, or start the spare peer first, and every
 * such assertion silently swaps its subjects.
 *
 * <p>So the role is passed to the worker on its command line
 * ({@code nodera-headless --test-mode --role player1}), reported back in {@code NODERA-STATE}, and
 * used by the harness to address the right node. The same name identifies the player that worker
 * drives in the game, so a scenario can say "player one breaks a block, player two's node must
 * validate it" and have both halves resolve to the same run.
 *
 * <p>Thread-context: enum constant, safe everywhere.
 */
public enum PlayerRole {

    /** The hosting player in host-client scenarios; the first joiner against a dedicated server. */
    PLAYER_ONE("player1", "HostDev", "run-host"),

    /** The second player — the one whose node must independently validate what player one does. */
    PLAYER_TWO("player2", "JoinerDev", "run-join"),

    /** A third player: the smallest arrangement in which two nodes can disagree and a third decide. */
    PLAYER_THREE("player3", "JoinerTwo", "run-join2"),

    /**
     * A worker with no client. Not a player: it holds the swarm above the quorum floor and keeps
     * serving archives after a player's worker dies with its game.
     */
    SPARE("spare", "", "");

    private final String cliName;
    private final String playerName;
    private final String gameDirectory;

    PlayerRole(String cliName, String playerName, String gameDirectory) {
        this.cliName = cliName;
        this.playerName = playerName;
        this.gameDirectory = gameDirectory;
    }

    /** The spelling accepted by {@code nodera-headless --role} and reported in the state JSON. */
    public String cliName() {
        return cliName;
    }

    /** The in-game player name this role drives; empty for {@link #SPARE}. */
    public String playerName() {
        return playerName;
    }

    /** The NeoForge dev run directory this role's client uses; empty for {@link #SPARE}. */
    public String gameDirectory() {
        return gameDirectory;
    }

    /** @return {@code true} if this role drives a player in the game. */
    public boolean drivesAPlayer() {
        return this != SPARE;
    }

    /** The role of the {@code index}-th player (0-based). */
    public static PlayerRole forIndex(int index) {
        return switch (index) {
            case 0 -> PLAYER_ONE;
            case 1 -> PLAYER_TWO;
            case 2 -> PLAYER_THREE;
            default -> SPARE;
        };
    }

    /**
     * Parse a {@code --role} value.
     *
     * @param value {@code player1} / {@code player-one} / {@code PLAYER_ONE} / {@code spare}.
     * @return the role.
     * @throws IllegalArgumentException naming every accepted spelling, because a typo here starts a
     *                                  worker that looks healthy and answers as the wrong player.
     */
    public static PlayerRole parse(String value) {
        String normalised = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        for (PlayerRole role : values()) {
            if (normalised.equals(role.cliName)
                    || normalised.equals(role.name().toLowerCase(Locale.ROOT).replace("_", ""))) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown role '" + value
                + "' — expected one of player1, player2, player3, spare");
    }
}
