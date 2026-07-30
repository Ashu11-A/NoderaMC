package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldRole;

/**
 * Task 33 / issue #36: an author/operator-signed role grant for a Nodera-shared world — the unit of
 * the P2P permission model. A grant binds a {@code subject} to a {@link WorldRole} for a given
 * {@code worldId}, carries a monotonically increasing {@code grantVersion} (a newer grant for the
 * same subject supersedes and can revoke — e.g. downgrade to {@link WorldRole#BANNED}), and is
 * signed by the granter (verified against {@code granterPublicKey}).
 *
 * <h2>Key binding (issue #36, flaw F2)</h2>
 * A {@link NodeId} is a random UUID <b>unrelated to the peer's key</b>, so binding a role to a
 * NodeId alone lets an attacker announce a granted subject's NodeId under <i>their own</i> key and
 * inherit the role. Wire version <b>v2</b> therefore also binds {@code subjectPublicKey}: the grant
 * only confers its role on a peer that proves possession of <i>that</i> key. Operator-conferring
 * grants ({@link WorldRole#OPERATOR}/{@link WorldRole#OWNER}) must be v2 — {@link WorldPermissions}
 * refuses to honour a v1 grant that claims operator power.
 *
 * <h2>Version fidelity</h2>
 * The grant remembers the wire {@link #version} it was created/decoded at; {@link #signedPortion()}
 * re-encodes in <i>that</i> version so a v1 grant minted by an older build keeps a valid signature
 * after this change. v1 grants carry an {@link Bytes#empty() empty} {@code subjectPublicKey}.
 *
 * <p>Wire form (v2): {@code [u16 WORLD_PERMISSION_GRANT][u16 version][Bytes worldId][NodeId subject]
 * [Bytes subjectPublicKey][u8 role][u64 grantVersion][NodeId granter][Bytes granterPublicKey]
 * [Bytes signature]}. v1 omits {@code subjectPublicKey}. The signature covers
 * {@link #signedPortion()} — everything except the signature.
 *
 * @Thread-context immutable, any thread.
 */
public record WorldPermissionGrant(
        int version,
        Bytes worldId,
        NodeId subject,
        Bytes subjectPublicKey,
        WorldRole role,
        long grantVersion,
        NodeId granter,
        Bytes granterPublicKey,
        Bytes signature) implements Encodable {

    /** Original key-less wire form: subject bound by NodeId only. Cannot carry operator power. */
    public static final int V1 = 1;

    /** Key-bound wire form (issue #36): binds {@code subjectPublicKey}. Required for operator roles. */
    public static final int V2 = 2;

    /** The version minted by {@link #create}. */
    public static final int CURRENT = V2;

    public WorldPermissionGrant {
        if (worldId == null || subject == null || subjectPublicKey == null || role == null
                || granter == null || granterPublicKey == null || signature == null) {
            throw new IllegalArgumentException("WorldPermissionGrant fields must not be null");
        }
        if (version != V1 && version != V2) {
            throw new IllegalArgumentException("unsupported grant version " + version);
        }
        if (version == V1 && !subjectPublicKey.isEmpty()) {
            throw new IllegalArgumentException("v1 grants must not carry a subjectPublicKey");
        }
        if (grantVersion < 0) {
            throw new IllegalArgumentException("grantVersion must be >= 0");
        }
    }

    /**
     * Build + sign a key-bound (v2) grant — the form used for all new grants.
     *
     * @param granter          the signing granter (author or an operator).
     * @param worldId          the world.
     * @param subject          the peer receiving the role.
     * @param subjectPublicKey the subject peer's public key the role is bound to (must be non-empty).
     * @param role             the role granted.
     * @param grantVersion     monotonic version (newer supersedes for the same subject).
     * @return a signed v2 grant.
     */
    public static WorldPermissionGrant create(NodeIdentity granter, Bytes worldId, NodeId subject,
                                              Bytes subjectPublicKey, WorldRole role, long grantVersion) {
        if (subjectPublicKey == null || subjectPublicKey.isEmpty()) {
            throw new IllegalArgumentException("v2 grant requires a non-empty subjectPublicKey");
        }
        WorldPermissionGrant unsigned = new WorldPermissionGrant(V2, worldId, subject, subjectPublicKey,
                role, grantVersion, granter.nodeId(), granter.publicKeyBytes(), Bytes.empty());
        Bytes sig = granter.sign(unsigned.signedPortion());
        return new WorldPermissionGrant(V2, worldId, subject, subjectPublicKey, role, grantVersion,
                granter.nodeId(), granter.publicKeyBytes(), sig);
    }

    /**
     * Build + sign a legacy key-less (v1) grant. Retained for back-compat and golden-byte tests;
     * {@link WorldPermissions} will not honour an operator role on a v1 grant.
     */
    public static WorldPermissionGrant createLegacyV1(NodeIdentity granter, Bytes worldId,
                                                      NodeId subject, WorldRole role, long grantVersion) {
        WorldPermissionGrant unsigned = new WorldPermissionGrant(V1, worldId, subject, Bytes.empty(),
                role, grantVersion, granter.nodeId(), granter.publicKeyBytes(), Bytes.empty());
        Bytes sig = granter.sign(unsigned.signedPortion());
        return new WorldPermissionGrant(V1, worldId, subject, Bytes.empty(), role, grantVersion,
                granter.nodeId(), granter.publicKeyBytes(), sig);
    }

    /** The canonical bytes the granter signature covers: everything except the signature. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        encodeSigned(w);
        return w.toBytes();
    }

    /** @return whether the granter signature verifies against {@link #granterPublicKey}. */
    public boolean verifySignature() {
        return new SignatureService().verify(granterPublicKey, signedPortion(), signature);
    }

    private void encodeSigned(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_PERMISSION_GRANT).writeU16(version);
        w.writeBytes(worldId);
        subject.encode(w);
        if (version >= V2) {
            w.writeBytes(subjectPublicKey);
        }
        w.writeU8(role.ordinal());
        w.writeU64(grantVersion);
        granter.encode(w);
        w.writeBytes(granterPublicKey);
    }

    @Override
    public void encode(CanonicalWriter w) {
        encodeSigned(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode; tolerant of both wire versions (v1 grants decode with an empty
     * {@code subjectPublicKey}).
     *
     * @throws IllegalStateException if the next tag is not {@code WORLD_PERMISSION_GRANT} or the
     *                               version is neither v1 nor v2.
     */
    public static WorldPermissionGrant decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_PERMISSION_GRANT) {
            throw new IllegalStateException("expected WORLD_PERMISSION_GRANT tag, got " + tag);
        }
        int version = r.readU16();
        if (version != V1 && version != V2) {
            throw new IllegalStateException("unsupported WorldPermissionGrant version " + version);
        }
        Bytes worldId = r.readBytesValue();
        NodeId subject = NodeId.decode(r);
        Bytes subjectPublicKey = version >= V2 ? r.readBytesValue() : Bytes.empty();
        WorldRole role = WorldRole.fromOrdinal(r.readU8());
        long grantVersion = r.readU64();
        NodeId granter = NodeId.decode(r);
        Bytes granterPublicKey = r.readBytesValue();
        Bytes signature = r.readBytesValue();
        return new WorldPermissionGrant(version, worldId, subject, subjectPublicKey, role, grantVersion,
                granter, granterPublicKey, signature);
    }
}
