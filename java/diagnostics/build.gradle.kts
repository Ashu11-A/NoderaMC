plugins {
    id("nodera.java-library")
}

// diagnostics: the Minecraft-free observability + view-model core (Task 18). All counting,
// rate math, classification (position → region → ownership), snapshot aggregation, and the
// Panel/Row/Cell report model live here as plain Java — unit-testable without a server. Only the
// thin neoforge-mod renderers touch net.minecraft.*; they consume DiagnosticsView/TelemetrySnapshot
// from this module. Depends on core alone (RegionId/RegionBounds/NodeId geometry + identity).
dependencies {
    api(project(":core"))
}
