plugins {
    id("nodera.java-library")
}

// committee: the Minecraft-free Phase 3 committee-validation brain (Task 7 — the MVP gate). Wires
// the existing consensus primitives (VoteCollector, MajorityQuorumPolicy, EquivocationDetector,
// SpotCheckPolicy) around real engine re-execution: every committee member re-executes the batch and
// casts a signed ACCEPT vote on its own root; a 2-of-3 quorum on one root commits the delta through
// the coordinator's WorldMutationApplier. Depends on core + simulation + consensus + coordinator; no
// NeoForge — the whole propose/vote/quorum/commit/failover loop runs headlessly (CommitteeMvpIT,
// ByzantineWorkerTest).
dependencies {
    api(project(":core"))
    api(project(":simulation"))
    api(project(":consensus"))
    api(project(":coordinator"))

    // Task-24 CrashRecoveryIT crosses the real piece/repair/event-replay seams headlessly.
    testImplementation(project(":distribution"))
    testImplementation(project(":peer-runtime"))
    testImplementation(project(":storage-eventsourced"))
}
