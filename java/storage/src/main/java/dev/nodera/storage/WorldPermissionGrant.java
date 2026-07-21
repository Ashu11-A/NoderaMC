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
 * Task 33: an author/operator-signed role grant for a Nodera-shared world — the unit of the P2P
 * permission model. A grant binds a {@code subject} {@link NodeId} to a {@link WorldRole} for a
 * given {@code worldId}, carries a monotonically increasing {@code version} (a newer grant for the
 * same subject supersedes and can revoke — e.g. downgrade to {@link WorldRole#BANNED}), and is
 * signed by the granter (verified against {@code granterPublicKey}, which must be the world author
 * for the OWNER-signed grants that bootstrap the model).
 *
 * <p>Distribution is content-addressed and gossiped: peers verify each grant's signature before
 * honouring it, so the permission set is authenticated end-to-end without a trusted server. The
 * world author is the {@link WorldRole#OWNER} implicitly; the first grants are author-signed.
 *
 * <p>Wire form: {@code [u16 WORLD_PERMISSION_GRANT][u16 version][Bytes worldId][NodeId subject]
 * [u8 role][u64 grantVersion][NodeId granter][Bytes granterPublicKey][Bytes signature]}. The
 * signature covers {@link #signedPortion()} — everything except the signature.
 *
 * @Thread-context immutable, any thread.
 */
public record WorldPermissionGrant(
        Bytes worldId,
        NodeId subject,
        WorldRole role,
        long grantVersion,
        NodeId granter,
        Bytes granterPublicKey,
        Bytes signature) implements Encodable {

    public WorldPermissionGrant {
        if (worldId == null || subject == null || role == null || granter == null
                || granterPublicKey == null || signature == null) {
            throw new IllegalArgumentException("WorldPermissionGrant fields must not be null");
        }
        if (grantVersion < 0) {
            throw new IllegalArgumentException("grantVersion must be >= 0");
        }
    }

    /**
     * Build + sign a grant.
     *
     * @param granter      the signing granter (author or an operator).
     * @param worldId      the world.
     * @param subject      the peer receiving the role.
     * @param role         the role granted.
     * @param grantVersion monotonic version (newer supersedes for the same subject).
     * @return a signed grant.
     */
    public static WorldPermissionGrant create(NodeIdentity granter, Bytes worldId, NodeId subject,
                                              WorldRole role, long grantVersion) {
        WorldPermissionGrant unsigned = new WorldPermissionGrant(worldId, subject, role, grantVersion,
                granter.nodeId(), granter.publicKeyBytes(), Bytes.empty());
        Bytes sig = granter.sign(unsigned.signedPortion());
        return new WorldPermissionGrant(worldId, subject, role, grantVersion, granter.nodeId(),
                granter.publicKeyBytes(), sig);
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
        w.writeU16(TypeTags.WORLD_PERMISSION_GRANT).writeU16(ENCODING_VERSION);
        w.writeBytes(worldId);
        subject.encode(w);
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
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code WORLD_PERMISSION_GRANT}.
     */
    public static WorldPermissionGrant decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_PERMISSION_GRANT) {
            throw new IllegalStateException("expected WORLD_PERMISSION_GRANT tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        NodeId subject = NodeId.decode(r);
        WorldRole role = WorldRole.fromOrdinal(r.readU8());
        long grantVersion = r.readU64();
        NodeId granter = NodeId.decode(r);
        Bytes granterPublicKey = r.readBytesValue();
        Bytes signature = r.readBytesValue();
        return new WorldPermissionGrant(worldId, subject, role, grantVersion, granter,
                granterPublicKey, signature);
    }
}
