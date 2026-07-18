plugins {
    id("nodera.java-library")
}

// distribution: the Minecraft-free torrent data plane (Task 19, Phase 5-6). Splits a region's
// canonical blob into addressable, individually-hashed pieces (PieceManifest), selects pieces
// deterministically rarest-first across a holder set (PieceSelector), fetches them from multiple
// seeders in parallel with hash-validate-before-accept (PieceDownloader/PieceReassembler), and
// locks un-arrived chunk sections against render/edit (ChunkLockMap). ContentTransferService is the
// serve+fetch seam over the PeerTransport.
//
// Depends on core (canonical encoding, hashing, region/state types), storage-api (ContentId/
// ContentStore — the blob tier the pieces are sliced from), protocol (the ContentRequest/
// ContentChunk/ContentAvailability wire messages) and transport-api (the PeerTransport seam only,
// never a concrete transport). No NeoForge: the whole swarm runs headlessly under JUnit
// (DistributionIT).
dependencies {
    api(project(":core"))
    api(project(":storage-api"))
    api(project(":protocol"))
    api(project(":transport-api"))

    testImplementation(project(":simulation"))
    testImplementation(project(":testkit"))
    // Test-only: DistributionIT rebuilds the post-batch snapshot with the REAL Phase 1
    // SnapshotDeltaApplier, so the state it splits is the state a replica would actually hold.
    testImplementation(project(":shadow-validation"))
}
