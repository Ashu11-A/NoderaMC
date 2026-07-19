plugins {
    id("nodera.java-library")
}

// storage-eventsourced: the Minecraft-free in-memory event-sourced WorldStore (Task 9, Phase 5).
// Content-addressed blob store, append-only per-region event logs with chain validation, checkpoint
// index, certificate store, the EventReplayer (verify the certified prevRoot→resultingRoot chain →
// final root), and the PeerSyncFlow (a new peer synchronises forward from the network, treating a
// locally-newer-but-uncertified suffix as uncommitted). The archival RocksDB tier will implement the
// same storage-api seam later.
dependencies {
    api(project(":core"))
    api(project(":storage-api"))
}
