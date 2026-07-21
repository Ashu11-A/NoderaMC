package dev.nodera.mod.common;

import java.util.Optional;

/**
 * Task 32: a presence probe for the Nodera companion daemon. Abstracted so {@link CompanionGate} can
 * be unit-tested with a fake, while the production {@link CompanionClient} probes a real loopback
 * socket.
 *
 * @Thread-context called on the mod-loading/setup thread; implementations must return promptly (use a
 *                short connect timeout) — the game start is blocked on this.
 */
@FunctionalInterface
public interface CompanionProbe {

    /** @return the daemon's {@link CompanionInfo} if it answered, or empty if absent/unreachable. */
    Optional<CompanionInfo> probe();
}
