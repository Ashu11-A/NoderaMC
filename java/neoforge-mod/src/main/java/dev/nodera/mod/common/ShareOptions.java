package dev.nodera.mod.common;

import java.util.Objects;

/**
 * The options a player chooses when sharing a world to the Nodera network (Task 30) — the payload of
 * the pause-menu "Share" action, and the config a dedicated auto-host starts with. Deliberately
 * Minecraft-free so it is unit-testable and can cross the client→server boundary as a plain value.
 *
 * <p>Encryption is implied by the password (Task 23): a non-empty password turns on AES-GCM content
 * encryption; an empty password is plaintext torrent hosting — the two are independent exactly as on
 * the Task 26 create-world screen. Changing the password on an already-shared world is a full
 * re-manifest (content addressing is over ciphertext, and the key is convergent), so callers must
 * treat a password change as re-genesis of the world's content, not an in-place edit.
 *
 * @param password       the encryption password; empty/blank means plaintext hosting.
 * @param delegateRegions whether this world's regions may be delegated to player committees.
 * @param listedOnTracker whether the world is announced to the tracker (false = invite-only join).
 * @param replicationHint the desired snapshot replication factor hint (Task 21 uses ×5 by default).
 */
public record ShareOptions(
        String password,
        boolean delegateRegions,
        boolean listedOnTracker,
        int replicationHint) {

    public ShareOptions {
        password = password == null ? "" : password;
        if (replicationHint < 1) {
            throw new IllegalArgumentException("replicationHint must be >= 1, got " + replicationHint);
        }
    }

    /** @return whether the world's content is encrypted (a non-blank password was set, Task 23). */
    public boolean encryptionEnabled() {
        return !password.isBlank();
    }

    /**
     * The defaults a dedicated server auto-hosts with: plaintext, delegable, listed, ×5 replication.
     * A dedicated host is a public seeder, so it lists on the tracker and allows delegation by
     * default; a player sharing from the pause menu chooses explicitly.
     *
     * @return the dedicated-server default options.
     */
    public static ShareOptions dedicatedDefault() {
        return new ShareOptions("", true, true, 5);
    }

    /**
     * The defaults the pause-menu "Share" screen opens with for a player-hosted world: no password
     * yet, delegable, listed, ×5 replication. The player edits from here.
     *
     * @return the player-host default options.
     */
    public static ShareOptions playerDefault() {
        return new ShareOptions("", true, true, 5);
    }

    /** @return a copy with the password replaced (a change on a live world = re-manifest; see class doc). */
    public ShareOptions withPassword(String newPassword) {
        return new ShareOptions(newPassword, delegateRegions, listedOnTracker, replicationHint);
    }

    /** @return a copy with the tracker-visibility flag replaced. */
    public ShareOptions withListedOnTracker(boolean listed) {
        return new ShareOptions(password, delegateRegions, listed, replicationHint);
    }

    /** @return a copy with the region-delegation flag replaced. */
    public ShareOptions withDelegateRegions(boolean delegate) {
        return new ShareOptions(password, delegate, listedOnTracker, replicationHint);
    }

    @Override
    public String toString() {
        // Never log the password.
        return "ShareOptions[encryption=" + encryptionEnabled()
                + ", delegateRegions=" + delegateRegions
                + ", listedOnTracker=" + listedOnTracker
                + ", replicationHint=" + replicationHint + ']';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ShareOptions other
                && delegateRegions == other.delegateRegions
                && listedOnTracker == other.listedOnTracker
                && replicationHint == other.replicationHint
                && password.equals(other.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(password, delegateRegions, listedOnTracker, replicationHint);
    }
}
