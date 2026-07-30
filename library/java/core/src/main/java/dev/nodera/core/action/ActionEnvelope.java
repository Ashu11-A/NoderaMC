package dev.nodera.core.action;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;

/**
 * A signed wrapper around a single {@link GameAction} (Task 2 action/). Carries the acting
 * {@link NodeId}, monotonic per-player and per-server sequence numbers, the target tick, and the
 * target {@link RegionId}. The {@link Bytes signature} covers exactly the bytes returned by
 * {@link #signedPortion()} (the typeTag + version + every field below except the signature).
 *
 * <p>Wire form: {@code signedPortion()} || {@code [u32 len][signature bytes]}.
 *
 * <p>{@code encode()} writes the full wire form (signed fields + signature). The signature itself
 * is never signed, and is stored as an immutable {@link Bytes}.
 *
 * @Thread-context immutable, any thread.
 */
public record ActionEnvelope(
        NodeId actor,
        long playerSeq,
        long serverSeq,
        long targetTick,
        RegionId region,
        GameAction action,
        Bytes signature
) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any of {@code actor}, {@code region}, {@code action},
     *                                  or {@code signature} is null.
     */
    public ActionEnvelope {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (signature == null) {
            throw new IllegalArgumentException("signature must not be null");
        }
    }

    /**
     * The canonical bytes that the signature covers: the {@code ACTION_ENVELOPE} typeTag +
     * version + actor + sequences + targetTick + region + action. This is a strict prefix of
     * {@link #encode(CanonicalWriter)} and excludes the signature bytes by construction.
     *
     * @Thread-context deterministic; safe from any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        writeSignedFields(w);
        return w.toBytes();
    }

    private void writeSignedFields(CanonicalWriter w) {
        w.writeU16(TypeTags.ACTION_ENVELOPE).writeU16(ENCODING_VERSION);
        actor.encode(w);
        w.writeU64(playerSeq);
        w.writeU64(serverSeq);
        w.writeU64(targetTick);
        region.encode(w);
        action.encode(w);
    }

    @Override
    public void encode(CanonicalWriter w) {
        writeSignedFields(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode (signed fields + signature).
     *
     * @throws IllegalStateException if the next tag is not {@code ACTION_ENVELOPE}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ActionEnvelope decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.ACTION_ENVELOPE) {
            throw new IllegalStateException("expected ACTION_ENVELOPE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        NodeId actor = NodeId.decode(r);
        long playerSeq = r.readU64();
        long serverSeq = r.readU64();
        long targetTick = r.readU64();
        RegionId region = RegionId.decode(r);
        GameAction action = GameAction.decode(r);
        Bytes signature = r.readBytesValue();
        return new ActionEnvelope(actor, playerSeq, serverSeq, targetTick, region, action, signature);
    }
}
