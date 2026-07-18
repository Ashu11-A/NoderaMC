rootProject.name = "nodera"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // ModDevGradle (NeoForge toolchain) lives on the NeoForge maven.
        maven("https://maven.neoforged.net/releases")
    }
}

// Auto-provision JDK toolchains (ModDevGradle needs a Java 21 toolchain to assemble the
// NeoForge runtime; the host JDK is 25).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

includeBuild("build-logic")

// Phase 0 — pure-Java (Minecraft-free) modules. Built and tested in CI.
include("core")
include("protocol")
include("simulation")
include("consensus")
include("transport-api")
include("transport-socket")
include("storage-api")
include("testkit")
include("peer-runtime")
include("diagnostics")
include("shadow-validation")
include("coordinator")
include("committee")
include("fallback")

// --- NeoForge-bound modules (Task 1 declares; enabled when the NeoForge toolchain is onboarded) ---
include("transport-neoforge")
include("neoforge-mod")

// --- Later-phase modules (Tasks 8-10, 12-16) ---
// include("storage-rocksdb")
// include("storage-client")
// include("peer-runtime")
// include("transport-libp2p")
// include("integration-tests")

// Version catalog: gradle/libs.versions.toml is auto-imported as `libs` by default.
