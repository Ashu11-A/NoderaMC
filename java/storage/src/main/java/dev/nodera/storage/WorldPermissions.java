package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.WorldRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 33 / issue #36: the authenticated permission set for one Nodera-shared world — the P2P
 * operator-permission model's evaluator. It ingests signed {@link WorldPermissionGrant}s (verifying
 * each signature and that the granter is <i>cryptographically</i> allowed to grant), keeps the
 * highest-version grant per subject (newer supersedes, so a downgrade to {@link WorldRole#BANNED}
 * revokes), and answers "what role does this peer have / may they join / are they an operator".
 *
 * <h2>Key-anchored authority (issue #36, flaws F2 &amp; F2b)</h2>
 * A {@link NodeId} is a spoofable label (a random UUID unrelated to the key), so every
 * privilege-conferring decision is anchored to a <b>public key</b>, not a NodeId:
 * <ul>
 *   <li>The world author is {@link WorldRole#OWNER} only when the announced key equals the
 *       {@code authorPublicKey} embedded in the signed {@code WorldIdentity} — closing F2b, where an
 *       attacker forged {@code granter = authorNodeId} with their own key.</li>
 *   <li>A grant is honoured for a peer only when the peer's key equals the grant's
 *       {@code subjectPublicKey} (v2). v1 grants carry no subject key and can never confer operator
 *       power — closing F2.</li>
 *   <li>An operator-signed grant is accepted only when the granter chains to an existing operator
 *       grant whose {@code subjectPublicKey} matches the granter's signing key; operators may confer
 *       {@link WorldRole#MEMBER}/{@link WorldRole#BANNED} only — minting new operators is the OWNER's
 *       sole authority.</li>
 * </ul>
 *
 * <p>The coarse NodeId-only {@link #canJoin(NodeId)} is retained for the P2P mesh join gate (mesh
 * presence is not a privilege); operator power always flows through the key-checked queries.
 *
 * @Thread-context thread-safe (grants held in a concurrent map).
 */
public final class WorldPermissions {

    private final Bytes worldId;
    private final NodeId author;
    private final Bytes authorPublicKey;

    /** subject NodeId → the highest-version accepted grant. */
    private final Map<NodeId, WorldPermissionGrant> grants = new ConcurrentHashMap<>();

    /**
     * @param worldId         the world these permissions govern.
     * @param author          the world author's {@link NodeId} (implicit OWNER).
     * @param authorPublicKey the world author's public key — the trust root every grant chains to.
     */
    public WorldPermissions(Bytes worldId, NodeId author, Bytes authorPublicKey) {
        if (worldId == null || author == null || authorPublicKey == null || authorPublicKey.isEmpty()) {
            throw new IllegalArgumentException("worldId, author and a non-empty authorPublicKey required");
        }
        this.worldId = worldId;
        this.author = author;
        this.authorPublicKey = authorPublicKey;
    }

    /**
     * Ingest a signed grant. Rejected (returns {@code false}, no state change) when: the signature is
     * invalid; it is for a different world; it targets the author (who cannot be demoted); a v1 grant
     * claims operator power; the granter is not cryptographically permitted (author must sign with the
     * real author key; an operator must chain to a key-matching operator grant and may only confer
     * MEMBER/BANNED); or it is not newer than the grant already held for that subject.
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
        // F2: a v1 (key-less) grant can never carry operator power — the role would apply to a bare
        // NodeId that anyone can announce under their own key.
        if (grant.version() < WorldPermissionGrant.V2 && grant.role().isOperator()) {
            return false;
        }
        // F2b: author authority requires the REAL author key, not merely the author NodeId. The
        // signature already verified against granterPublicKey; here we insist that key IS the author.
        boolean authorSigned = grant.granter().equals(author)
                && grant.granterPublicKey().equals(authorPublicKey);
        if (authorSigned) {
            // OWNER may confer any role.
        } else {
            // Operator-signed: the granter must be a key-matching operator, and may confer only
            // non-operator roles. Minting operators is the OWNER's sole authority.
            WorldPermissionGrant granterGrant = grants.get(grant.granter());
            boolean granterIsKeyedOperator = granterGrant != null
                    && granterGrant.role().isOperator()
                    && granterGrant.subjectPublicKey().equals(grant.granterPublicKey());
            if (!granterIsKeyedOperator || grant.role().isOperator()) {
                return false;
            }
        }
        WorldPermissionGrant existing = grants.get(grant.subject());
        if (existing != null && grant.grantVersion() <= existing.grantVersion()) {
            return false; // not newer — ignore replays / stale grants
        }
        grants.put(grant.subject(), grant);
        return true;
    }

    // --- key-checked queries (security-grade — use these for op decisions) -----------------------

    /**
     * The effective role of a peer proven to hold {@code publicKey}. OWNER only when the key is the
     * author key; a granted role only when the key matches the grant's bound {@code subjectPublicKey};
     * otherwise {@link WorldRole#MEMBER}.
     *
     * @param peer      the announced NodeId.
     * @param publicKey the key the peer proved possession of (via the announce challenge).
     */
    public WorldRole roleOf(NodeId peer, Bytes publicKey) {
        if (peer.equals(author) && authorPublicKey.equals(publicKey)) {
            return WorldRole.OWNER;
        }
        WorldPermissionGrant g = grants.get(peer);
        if (g != null && !g.subjectPublicKey().isEmpty() && g.subjectPublicKey().equals(publicKey)) {
            return g.role();
        }
        return WorldRole.MEMBER;
    }

    /** @return whether the key-proven peer should get in-game operator powers. */
    public boolean isOperator(NodeId peer, Bytes publicKey) {
        return roleOf(peer, publicKey).isOperator();
    }

    // --- coarse NodeId-only queries (mesh gate + diagnostics; NOT for op decisions) --------------

    /**
     * The role bound to a NodeId, ignoring key possession. Coarse — used by the mesh join gate and
     * diagnostics only; never gate operator power on this (see {@link #isOperator(NodeId, Bytes)}).
     */
    public WorldRole roleOf(NodeId peer) {
        if (peer.equals(author)) {
            return WorldRole.OWNER;
        }
        WorldPermissionGrant g = grants.get(peer);
        return g == null ? WorldRole.MEMBER : g.role();
    }

    /** @return whether {@code peer} may join this world (not BANNED). Mesh gate — NodeId-scoped. */
    public boolean canJoin(NodeId peer) {
        return roleOf(peer).canJoin();
    }

    /** @return the accepted grant for a subject, if any. */
    public Optional<WorldPermissionGrant> grant(NodeId subject) {
        return Optional.ofNullable(grants.get(subject));
    }

    /** @return an immutable snapshot of every accepted grant (for persistence / gossip). */
    public List<WorldPermissionGrant> snapshot() {
        return List.copyOf(new ArrayList<>(grants.values()));
    }

    /** @return the world author (implicit OWNER). */
    public NodeId author() {
        return author;
    }

    /** @return the world author's public key (the permission trust root). */
    public Bytes authorPublicKey() {
        return authorPublicKey;
    }
}
