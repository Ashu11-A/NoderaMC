plugins {
    id("nodera.java-library")
}

// coordinator: the Minecraft-free Phase 2 coordinator brain (Task 6). Node registry, reliability
// ledger, deterministic rendezvous placement, region allocator, delegability policy, lease/epoch
// manager, heartbeat monitor, the per-region pipeline state machine, proposal manager, server
// verifier, and the two-pass compare-and-set WorldMutationApplier over a MutableWorldView seam.
// Depends on core (types) + simulation (the reference engine) + consensus (VerificationOutcome,
// ProposalKey). It never touches NeoForge — ServerLevel is behind the MutableWorldView seam — so the
// whole delegate/verify/commit/reassign pipeline runs headlessly under JUnit (CoordinatorIT).
dependencies {
    api(project(":core"))
    api(project(":simulation"))
    api(project(":consensus"))
}
