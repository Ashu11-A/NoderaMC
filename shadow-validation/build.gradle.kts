plugins {
    id("nodera.java-library")
}

// shadow-validation: the Minecraft-free Phase 1 shadow-lane brain (Task 5). Client WorkerRuntime,
// replica store, the shadow worker/coordinator, server-side reference recompute, and the divergence
// + interference trackers. Depends only on core (types) + simulation (the engine); it never touches
// NeoForge, so the whole determinism-proof pipeline runs headlessly under JUnit — the executable
// stand-in for the deliverable's manual multi-client soak (ShadowValidationIT).
dependencies {
    api(project(":core"))
    api(project(":simulation"))
}
