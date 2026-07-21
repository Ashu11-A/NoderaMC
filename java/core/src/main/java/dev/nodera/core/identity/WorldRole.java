package dev.nodera.core.identity;

/**
 * Task 33: a peer's permission role for a specific Nodera-shared world. The world's original author
 * is implicitly {@link #OWNER}; every other role is granted by a signed {@code WorldPermissionGrant}.
 *
 * <p>The ordinal is the frozen wire form (encoded as a {@code u8}); append new roles at the end,
 * never renumber.
 */
public enum WorldRole {

    /** No access — an explicit ban that overrides any prior grant. */
    BANNED,

    /** May join and play, no operator powers. */
    MEMBER,

    /** May join, has in-game operator powers, and may grant/revoke MEMBER (per policy). */
    OPERATOR,

    /** The world author: full control, the sole password authority, may grant any role. */
    OWNER;

    /** @return the role for a frozen wire ordinal. */
    public static WorldRole fromOrdinal(int ordinal) {
        WorldRole[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("unknown WorldRole ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    /** @return whether this role confers in-game operator powers. */
    public boolean isOperator() {
        return this == OPERATOR || this == OWNER;
    }

    /** @return whether this role may join/play at all. */
    public boolean canJoin() {
        return this != BANNED;
    }
}
