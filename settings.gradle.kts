rootProject.name = "nodera"

// --- The single source of truth for the product version: the root `VERSION` file ---
//
// Every toolchain reads that one file, so a release is one edit (`scripts/version.sh --set X.Y.Z`)
// instead of a hunt through Gradle, Cargo, Tauri, and a Java constant that always ends up one
// bump behind. Read HERE rather than in the root build script because the value has to be
// available to EVERY project (including the NeoForge convention plugin, which stamps it into
// `neoforge.mods.toml`) before any build script is evaluated.
//
// Parsing is deliberately tolerant of comments and blank lines and deliberately intolerant of an
// empty file: a version silently defaulting to something plausible is exactly how a build ships
// mislabelled artifacts.
val noderaVersion: String = file("VERSION").readLines()
    .map { it.substringBefore('#').trim() }
    .firstOrNull { it.isNotEmpty() }
    ?: error("VERSION is empty — it must contain one line like 0.1.0 (see AGENTS.md § Versioning)")

gradle.beforeProject {
    project.extra["noderaVersion"] = noderaVersion
    // `modVersion` is what the NeoForge convention plugin expands into neoforge.mods.toml via
    // `val modVersion: String by project`. Injecting it here rather than editing that precompiled
    // script plugin is deliberate: any edit to build-logic's .gradle.kts files on this toolchain
    // re-triggers the kotlin-dsl accessor breakage documented in gradle.properties, and a property
    // delegate reads extra properties and gradle.properties alike — so the plugin keeps working,
    // untouched, and the number it stamps now comes from VERSION.
    project.extra["modVersion"] = noderaVersion
}

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
// Shared test library (issue #30): LoopbackTransport, FakeRegion, wire-fixture IO. The planned
// multi-peer scenario suite (old integration-tests) lands here when Task 5's live lane needs it.
module("testing")

// --- NeoForge-bound module (the only place Minecraft types may appear besides its tests) ---
module("neoforge-mod")

// --- Paper/Folia endpoint plugin (server task 1) ---
// Produces `nodera-endpoint.jar`, which scripts/lib/e2e-server.sh stages. The Paper API is
// compileOnly and resolved from PaperMC's maven, declared inside the module so an outage there
// cannot break the Minecraft-free build.
module("paper-plugin")

// --- Later-phase modules (Tasks 12-16) ---
// include("integration-tests")

// Version catalog: gradle/libs.versions.toml is auto-imported as `libs` by default.
