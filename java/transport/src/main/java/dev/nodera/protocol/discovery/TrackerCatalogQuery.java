package dev.nodera.protocol.discovery;

import dev.nodera.protocol.NoderaMessage;

/**
 * "List the worlds you know" — the tracker <b>directory</b> query (browse), the complement of the
 * per-world {@link TrackerQuery}. A {@link TrackerQuery} needs a genesis hash you already have; this
 * asks the tracker to enumerate every listed world so a client can populate the multiplayer "Worlds"
 * tab with worlds it has never seen. The tracker answers with a {@link TrackerCatalogResponse}.
 *
 * <p>Only worlds a host announced as <em>listed</em> appear (invite-only worlds are never in the
 * directory). The tracker verifies — never trusts — everything it serves (Task 0 rule 7).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param limit the maximum number of worlds to return; {@code 0} means the tracker's default page.
 */
public record TrackerCatalogQuery(int limit) implements NoderaMessage {

    public TrackerCatalogQuery {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative, got " + limit);
        }
    }
}
