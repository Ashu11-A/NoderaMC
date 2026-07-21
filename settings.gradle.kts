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
// Unified storage API (issue #30): absorbs the former storage-api / storage-eventsourced /
// storage-rocksdb / storage-client modules — packages unchanged (dev.nodera.storage.*).
module("storage")
module("testkit")
module("peer-runtime")
module("diagnostics")
module("shadow-validation")
module("coordinator")
module("committee")
module("fallback")
module("distribution")

// Task 32 — the headless peer worker: a Minecraft-free, always-on network node (the "peer worker"
// the Tauri companion app supervises and the Minecraft mod requires). Owns the loopback control
// endpoint the mod probes. Runnable via the `application` plugin (installDist / run).
module("nodera-headless")

// --- NeoForge-bound modules (Task 1 declares; enabled when the NeoForge toolchain is onboarded) ---
module("transport-neoforge")
module("neoforge-mod")

// --- Rust-services transport (Task 29) ---
// `transport-rendezvous` is the third PeerTransport: direct-first, punch-upgrade, E2E-encrypted
// relay fallback over the standalone `nodera-rendezvous` service. It replaced the never-built
// `transport-libp2p` placeholder (deleted by Task 27; superseded per LEGACY.md).
module("transport-rendezvous")

// --- Later-phase modules (Tasks 12-16) ---
// include("integration-tests")

// Version catalog: gradle/libs.versions.toml is auto-imported as `libs` by default.
