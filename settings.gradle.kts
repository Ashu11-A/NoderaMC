rootProject.name = "nodera"

includeBuild("build-logic")

// Phase 0 — pure-Java (Minecraft-free) modules. Built and tested in CI.
include("core")
include("protocol")
include("simulation")
include("consensus")
include("transport-api")
include("storage-api")
include("testkit")

// --- NeoForge-bound modules (Task 1 declares; enabled when the NeoForge toolchain is onboarded) ---
// include("transport-neoforge")
// include("neoforge-mod")

// --- Later-phase modules (Tasks 8-10, 12-16) ---
// include("storage-rocksdb")
// include("storage-client")
// include("peer-runtime")
// include("transport-libp2p")
// include("integration-tests")

// Version catalog: gradle/libs.versions.toml is auto-imported as `libs` by default.
