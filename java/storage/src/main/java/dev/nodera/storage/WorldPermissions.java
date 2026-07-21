package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.WorldRole;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 33: the authenticated permission set for one Nodera-shared world — the P2P operator-permission
 * model's evaluator. It ingests signed {@link WorldPermissionGrant}s (verifying each signature and
 * that the granter is allowed to grant), keeps the highest-version grant per subject (newer
 * supersedes, so a downgrade to {@link WorldRole#BANNED} revokes), and answers "what role does this
 * peer have / may they join / are they an operator".
 *
 * <p>The world <b>author is {@link WorldRole#OWNER}</b> implicitly and unconditionally — it needs no
 * grant and cannot be demoted by anyone else. Only the OWNER (or, by policy, an existing OPERATOR)
 * may sign grants that this set will accept; a grant signed by anyone else is rejected, so the
 * permission set is authenticated end-to-end with no trusted server.
 *
 * @Thread-context thread-safe (grants + roles held in concurrent maps).
 */
public final class WorldPermissions {

    private final Bytes worldId;
    private final NodeId author;

    /** subject → the highest-version accepted grant. */
    private final Map<NodeId, WorldPermissionGrant> grants = new ConcurrentHashMap<>();

    /**
     * @param worldId the world these permissions govern.
     * @param author  the world author (implicit OWNER).
     */
    public WorldPermissions(Bytes worldId, NodeId author) {
        if (worldId == null || author == null) {
            throw new IllegalArgumentException("worldId and author must not be null");
        }
        this.worldId = worldId;
        this.author = author;
    }

    /**
     * Ingest a signed grant. Rejected (returns {@code false}, no state change) when: the signature is
     * invalid, it is for a different world, the granter is not permitted to grant (must be the author
     * or an existing operator), it targets the author (who cannot be demoted), or it is not newer
     * than the grant already held for that subject.
     *
     * @param grant the signed grant.
     * @return whether it was accepted and applied.
     */
    public boolean apply(WorldPermissionGrant grant) {
        if (grant == null || !grant.worldId().equals(worldId) || !grant.verifySignature()) {
            return false;
        }
        // The author cannot be demoted/banned by a grant — OWNER is intrinsic.
        if (grant.subject().equals(author)) {
            return false;
        }
        // Granter authority: the author always may; an existing operator may (policy: operators can
        // manage members but the ingest here accepts operator-signed grants — finer policy can layer
        // on top). Anyone else is rejected.
        if (!grant.granter().equals(author) && !roleOf(grant.granter()).isOperator()) {
            return false;
        }
        WorldPermissionGrant existing = grants.get(grant.subject());
        if (existing != null && grant.grantVersion() <= existing.grantVersion()) {
            return false; // not newer — ignore replays / stale grants
        }
        grants.put(grant.subject(), grant);
        return true;
    }

    /** @return the effective role of {@code peer} (author ⇒ OWNER; ungranted ⇒ MEMBER default? no — see below). */
    public WorldRole roleOf(NodeId peer) {
        if (peer.equals(author)) {
            return WorldRole.OWNER;
        }
        WorldPermissionGrant g = grants.get(peer);
        return g == null ? WorldRole.MEMBER : g.role();
    }

    /** @return whether {@code peer} may join this world (not BANNED). */
    public boolean canJoin(NodeId peer) {
        return roleOf(peer).canJoin();
    }

    /** @return whether {@code peer} should get in-game operator powers. */
    public boolean isOperator(NodeId peer) {
        return roleOf(peer).isOperator();
    }

    /** @return the accepted grant for a subject, if any. */
    public Optional<WorldPermissionGrant> grant(NodeId subject) {
        return Optional.ofNullable(grants.get(subject));
    }

    /** @return the world author (implicit OWNER). */
    public NodeId author() {
        return author;
    }
}
