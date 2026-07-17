package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

import java.util.List;

/**
 * A server-sequence-ordered batch of {@link ActionEnvelope}s for one region over a tick range
 * (Task 2 action/). The execution engine replays the actions in exactly the order carried here:
 * the order is semantically meaningful (later actions observe the effects of earlier ones), so the
 * encoder MUST NOT re-sort the list even though other lists in this module are canonicalised.
 *
 * <p>Wire form: {@code [u16 ACTION_BATCH][u16 ENCODING_VERSION][RegionId][RegionEpoch]
     * [SnapshotVersion baseVersion][u64 tickFrom][u64 tickTo][list ActionEnvelope]}.
 *
 * @Thread-context immutable, any thread.
 */
public record ActionBatch(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion baseVersion,
        long tickFrom,
        long tickTo,
        List<ActionEnvelope> actions
) implements Encodable {

    /**
     * Compact constructor. Defensive-copies {@code actions} into an unmodifiable list; order is
     * preserved exactly as supplied (it encodes server sequence, not a sort key).
     *
     * @throws IllegalArgumentException if {@code region}, {@code epoch}, {@code baseVersion}, or
     *                                  {@code actions} is null.
     */
    public ActionBatch {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (baseVersion == null) {
            throw new IllegalArgumentException("baseVersion must not be null");
        }
        if (actions == null) {
            throw new IllegalArgumentException("actions must not be null");
        }
        actions = List.copyOf(actions);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ACTION_BATCH).writeU16(ENCODING_VERSION);
        region.encode(w);
        epoch.encode(w);
        baseVersion.encode(w);
        w.writeU64(tickFrom);
        w.writeU64(tickTo);
        w.writeList(actions, CanonicalWriter::writeEncodable);
    }

    /**
     * Full-frame decode. The decoded actions list preserves wire order (server sequence).
     *
     * @throws IllegalStateException if the next tag is not {@code ACTION_BATCH}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ActionBatch decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.ACTION_BATCH) {
            throw new IllegalStateException("expected ACTION_BATCH tag, got " + tag);
        }
        r.readU16();
        RegionId region = RegionId.decode(r);
        RegionEpoch epoch = RegionEpoch.decode(r);
        SnapshotVersion baseVersion = SnapshotVersion.decode(r);
        long tickFrom = r.readU64();
        long tickTo = r.readU64();
        List<ActionEnvelope> actions = r.readList(ActionEnvelope::decode);
        return new ActionBatch(region, epoch, baseVersion, tickFrom, tickTo, actions);
    }
}
