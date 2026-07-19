plugins {
    id("nodera.java-library")
}

// storage-client: the bounded/quota'd client-side content store (Task 22, Phase 6 — owns L-37).
// A client peer's partial archive must not grow unbounded (Plan §3.13: "no unbounded maps keyed by
// remote input"). BoundedClientWorldStore wraps a ContentStore with a StorageQuotaManager byte
// budget; ArchiveEvictionPolicy evicts oldest cold shards first and NEVER an assigned region's
// current state, signalling Task 21's repair so replication factors are re-met elsewhere.
//
// Depends on core + storage-api (the ContentStore/ContentId seam). In-memory now; the filesystem
// tier lands with the RocksDB archival store.
dependencies {
    api(project(":core"))
    api(project(":storage-api"))

    testImplementation(project(":distribution"))
    testImplementation(project(":testkit"))
}
