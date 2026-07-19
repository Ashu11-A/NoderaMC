plugins {
    id("nodera.java-library")
}

// storage-api: the Minecraft-free storage seam (Plan §4, Task 9). WorldStore + the event/content/
// checkpoint/certificate interfaces and their value types (ContentId, Checkpoint, GenesisManifest).
// core types (Bytes, RegionId, StateRoot, CommittedEventEnvelope, QuorumCertificate) appear in the
// public API, so core is an `api` dependency. Concrete stores (in-memory event-sourced now,
// RocksDB later) implement this seam.
dependencies {
    api(project(":core"))
}
