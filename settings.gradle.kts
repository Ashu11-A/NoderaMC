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
// Unified deterministic-engine + validation API (issue #30): absorbs the former simulation /
// consensus / coordinator / committee / shadow-validation / fallback modules — packages
// unchanged, so the ArchUnit determinism ban on dev.nodera.simulation.. still bites.
module("engine")
// Unified network API (issue #30): absorbs the former protocol / transport-api /
// transport-socket / transport-rendezvous modules — packages unchanged. The empty
// transport-neoforge placeholder was deleted; the in-game relay lane lands in neoforge-mod.
module("transport")
// Unified storage API (issue #30): absorbs the former storage-api / storage-eventsourced /
// storage-rocksdb / storage-client modules — packages unchanged (dev.nodera.storage.*).
module("storage")
// Unified peer API (issue #30): absorbs the former peer-runtime / distribution / diagnostics /
// nodera-headless modules — packages unchanged. Carries the `application` plugin: the
// installDist launcher stays `nodera-headless` (rust/nodera-app + scripts/dev.sh depend on it).
module("peer")
module("testkit")

// --- NeoForge-bound module (the only place Minecraft types may appear besides its tests) ---
module("neoforge-mod")

// --- Later-phase modules (Tasks 12-16) ---
// include("integration-tests")

// Version catalog: gradle/libs.versions.toml is auto-imported as `libs` by default.
