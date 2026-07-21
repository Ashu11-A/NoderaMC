package dev.nodera.mod.common;

/**
 * Task 32: thrown by {@link CompanionGate#requireRunning} when the Nodera companion daemon is absent
 * or version-incompatible and the presence gate is enforced. It aborts NeoForge startup with an
 * actionable message (naming the install URL / the specific skew) rather than letting the game start
 * without a peer node.
 */
public final class CompanionUnavailableException extends RuntimeException {

    public CompanionUnavailableException(String message) {
        super(message);
    }
}
