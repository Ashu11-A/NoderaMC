package dev.nodera.storage.event;

import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.CertificateStore;
import dev.nodera.storage.CheckpointStore;
import dev.nodera.storage.ContentStore;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.RegionEventStore;
import dev.nodera.storage.WorldStore;

/**
 * The in-memory event-sourced {@link WorldStore} (Task 9, Phase 5): a genesis manifest wired to the
 * four in-memory sub-stores. Canonical state = genesis + certified per-region event logs +
 * checkpoints + certificates + content blobs.
 *
 * @Thread-context confined to the owning thread; not thread-safe.
 */
public final class EventSourcedWorldStore implements WorldStore {

    private final GenesisManifest genesis;
    private final InMemoryContentStore content;
    private final InMemoryRegionEventStore events;
    private final InMemoryCheckpointStore checkpoints;
    private final InMemoryCertificateStore certificates;

    public EventSourcedWorldStore(GenesisManifest genesis, HashService hashes) {
        if (genesis == null) {
            throw new IllegalArgumentException("genesis must not be null");
        }
        this.genesis = genesis;
        this.content = new InMemoryContentStore(hashes);
        this.events = new InMemoryRegionEventStore();
        this.checkpoints = new InMemoryCheckpointStore();
        this.certificates = new InMemoryCertificateStore(hashes);
    }

    @Override
    public GenesisManifest genesis() {
        return genesis;
    }

    @Override
    public ContentStore content() {
        return content;
    }

    @Override
    public RegionEventStore events() {
        return events;
    }

    @Override
    public CheckpointStore checkpoints() {
        return checkpoints;
    }

    @Override
    public CertificateStore certificates() {
        return certificates;
    }

    /** @return the certificate store as its concrete type (for the {@code contentId} helper). */
    public InMemoryCertificateStore certificateStore() {
        return certificates;
    }
}
