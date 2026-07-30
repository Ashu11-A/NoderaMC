plugins {
    id("nodera.java-library")
}

// engine: the unified deterministic-engine + validation API (Java API unification, issue #30).
// One module, the whole Minecraft-free proof chain:
//
//  - dev.nodera.simulation.*  — THE region engine (the project's central bet). The determinism
//                               ground rules (Task 0 §9) still bind exactly this package: the
//                               ArchUnit ForbiddenApiTest ban on clocks/entropy/IO/unordered
//                               iteration is package-scoped and unchanged by the merge.
//  - dev.nodera.consensus.*   — quorum/vote/equivocation/spot-check primitives.
//  - dev.nodera.shadow.*      — Phase 1 shadow validation (WorkerRuntime, divergence tracking).
//  - dev.nodera.coordinator.* — Phase 2 coordinator (leases/epochs/pipeline, the one
//                               WorldMutationApplier, the Task 11 interference guard).
//  - dev.nodera.committee.*   — Phase 3 committee validation (the MVP gate).
//  - dev.nodera.fallback.*    — Phase 4 server-fallback lane + cross-region router + soak
//                               metrics (wired live by Task 5's live lane).
dependencies {
    api(project(":core"))
    // DeterministicRandom uses java.util.random RandomGeneratorFactory (JDK only);
    // fastutil backs the primitive section arrays in the engine hot path.
    implementation(libs.fastutil)
    implementation(libs.caffeine)

    // Task-24 CrashRecoveryIT crosses the real piece/repair/event-replay seams headlessly.
    testImplementation(project(":peer"))
    testImplementation(project(":storage"))
}
