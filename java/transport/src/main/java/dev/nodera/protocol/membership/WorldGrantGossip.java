package dev.nodera.protocol.membership;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * One world-permission grant, gossiped to the peers that co-host the world (issue #36 / L-54).
 *
 * <p>Before this, {@code WorldPermissionStore} persisted grants on the author's own machine and
 * nowhere else: a co-hosting peer's permission set stayed author-local until it happened to fetch
 * the world, so an operator promotion or a ban simply did not exist for the rest of the mesh.
 *
 * <p>The grant travels as <b>opaque canonical bytes</b>, exactly like the manifests in
 * {@code WorldManifestAnswer}. That is deliberate: a grant is self-authenticating
 * ({@code WorldPermissionGrant} carries the granter's key and a signature over its own signed
 * portion, and {@code WorldPermissions.apply} re-verifies both the signature and the granter's
 * authority against the world's author key), so the transport has no reason to understand its
 * shape and no ability to weaken it. A forged or re-signed grant is refused by the applier on every
 * honest peer regardless of who relayed it.
 *
 * @param worldId      the world the grant belongs to; not null.
 * @param encodedGrant the canonical encoding of a {@code WorldPermissionGrant}; not null.
 */
public record WorldGrantGossip(Bytes worldId, Bytes encodedGrant) implements NoderaMessage {

    public WorldGrantGossip {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(encodedGrant, "encodedGrant");
    }
}
