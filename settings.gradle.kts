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

includeBuild("java/build-logic")

// --- Monorepo layout (Task 27, see MONOREPO.md) ---
// Every Gradle module lives under `java/`; the Rust service crates live under `rust/`.
// Module NAMES are unchanged (`:core`, `:peer-runtime`, …) — only their directories moved,
// so `./gradlew :core:test` and every `build.gradle.kts` keep working untouched.
fun module(name: String) {
    include(name)
    project(":$name").projectDir = file("java/$name")
}

// Phase 0 — pure-Java (Minecraft-free) modules. Built and tested in CI.
module("core")
module("protocol")
module("simulation")
module("consensus")
module("transport-api")
module("transport-socket")
module("storage-api")
module("testkit")
module("peer-runtime")
module("diagnostics")
module("shadow-validation")
module("coordinator")
module("committee")
module("fallback")
module("storage-eventsourced")
module("distribution")
module("storage-client")
module("storage-rocksdb")

// --- NeoForge-bound modules (Task 1 declares; enabled when the NeoForge toolchain is onboarded) ---
module("transport-neoforge")
module("neoforge-mod")

// --- Later-phase modules (Tasks 12-16, 29) ---
// The `transport-libp2p` placeholder was deleted by Task 27: the NAT/relay plan is superseded by
// the Rust `nodera-rendezvous` service + `transport-rendezvous` (Task 29, see LEGACY.md).
// include("integration-tests")

// Version catalog: gradle/libs.versions.toml is auto-imported as `libs` by default.
