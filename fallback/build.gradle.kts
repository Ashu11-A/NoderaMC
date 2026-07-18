plugins {
    id("nodera.java-library")
}

// fallback: the Minecraft-free Phase 4 server-fallback + cross-region router (Task 8). Classifies
// every action into the committee lane or the server fallback lane (unassigned region, cross-region
// action, disputed proposal, collapsed committee), executes the fallback lane server-side through the
// coordinator's WorldMutationApplier, and measures the committee-commit ratio (Phase 4 exit: >90% of
// validated-action batches commit without server re-execution). Depends on core + simulation +
// consensus + coordinator; no NeoForge — the whole router + soak runs headlessly (FallbackRoutingIT).
dependencies {
    api(project(":core"))
    api(project(":simulation"))
    api(project(":consensus"))
    api(project(":coordinator"))
}
