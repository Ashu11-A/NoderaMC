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
    // DeterministicRandom uses java.util.random RandomGeneratorFactory (JDK only).
    //
    // fastutil was declared here for years to "back the primitive section arrays in the hot path"
    // and was never imported by a single source file — `strings` over engine.jar found zero
    // `it/unimi` references. It cost 19.5 MB compressed in every artefact that transitively
    // depended on this module, which is every desktop installer and the release peer jar. If a
    // primitive-collection need ever arrives, add it back WITH the call site in the same commit.
    implementation(libs.caffeine)

    // Task-24 CrashRecoveryIT crosses the real piece/repair/event-replay seams headlessly.
    testImplementation(project(":peer"))
    testImplementation(project(":storage"))
    // `dev.nodera.testkit.engine.EngineFixtures` — the batch/context/execute preamble that was
    // copied into eighteen files here. TEST scope only, which is what keeps the main graph acyclic:
    // `:testing` depends on this module's main configuration, this module's TESTS depend back on
    // `:testing`, and `:storage`, `:transport`, `:endpoint` and `:peer` already do exactly that.
    testImplementation(project(":testing"))
}
