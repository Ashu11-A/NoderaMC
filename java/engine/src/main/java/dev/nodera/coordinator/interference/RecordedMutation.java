package dev.nodera.coordinator.interference;

import dev.nodera.core.state.NBlockPos;

/**
 * One foreign write recorded by the {@link MutationGuard} in CONVERT mode (Task 11): the position,
 * the state the world held immediately before the write (the CAS guard the resulting
 * {@code BlockMutation} will carry), the state the write left behind, and the vanilla phase it was
 * observed in.
 *
 * @Thread-context immutable, any thread.
 */
public record RecordedMutation(NBlockPos pos, int prevStateId, int newStateId, MutationSource source) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code pos} or {@code source} is null.
     */
    public RecordedMutation {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
    }
}
