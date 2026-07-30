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

// --- Monorepo layout: read from `layout.properties`, never spelled out here ---
//
// Which directory holds which module is a property of the tree, not of Gradle, and five other
// consumers in four other languages need the same answer (the live harness, the structural report,
// the shell suites, the release scripts). They all read `layout.properties`; so does this file.
// A module relocation is therefore one edit there plus a `git mv`, and `LayoutManifestTest` fails
// the build if this file and that one ever disagree.
//
// Module NAMES come from the key suffix and are unchanged by any move, so `./gradlew :core:test`
// and every `build.gradle.kts` project reference keep working untouched. Each module's rationale
// lives next to its key in the manifest.
val layout: java.util.Properties = java.util.Properties().apply {
    val manifest = file("layout.properties")
    if (!manifest.isFile) {
        error("layout.properties is missing from the repository root — it is the layout table")
    }
    manifest.inputStream().use { load(it) }
}

fun layoutDir(key: String): File =
    file(layout.getProperty(key) ?: error("layout.properties has no key '$key'"))

includeBuild(layoutDir("buildLogic"))

layout.stringPropertyNames()
    .filter { it.startsWith("module.") }
    .sorted()
    .forEach { key ->
        val name = key.removePrefix("module.")
        include(name)
        project(":$name").projectDir = layoutDir(key)
    }

// What Gradle ACTUALLY configured, handed to `LayoutManifestTest` so it can prove the manifest and
// the build agree rather than assuming it.
//
// Read from `rootProject.children` rather than from the loop above, and that distinction is the
// whole point: a list built from the loop would be derived from the manifest and could never
// disagree with it, so the test would pass vacuously forever. `children` is every project this
// build really has — so a module re-added the old way, with a hand-written `include()` and a
// hardcoded `projectDir`, shows up here, is absent from the manifest, and fails the build. That is
// the regression this manifest exists to prevent.
//
// Injected here, and never from `java/build-logic`: any edit to a precompiled script plugin on this
// toolchain re-triggers the kotlin-dsl accessor breakage documented in gradle.properties, and an
// extra property is read by the same delegate.
// The descriptor is captured here but READ inside `beforeProject`, and the distinction matters
// twice. Reading it at this line would miss anything `include`d further down — the settings script
// is still being evaluated — which is exactly the shape this check exists to catch, and a probe
// proved it silently passed. Capturing it is also required: inside the lambda the receiver is a
// `Project`, whose `rootProject` is another `Project` and has no children to enumerate.
val settingsRoot = rootProject
val repoRoot = rootDir.toPath()

gradle.beforeProject {
    project.extra["noderaConfiguredModules"] = settingsRoot.children
        .map { "${it.name}=" + repoRoot.relativize(it.projectDir.toPath()).joinToString("/") }
        .sorted()
        .joinToString(";")
}

// Version catalog: gradle/libs.versions.toml is auto-imported as `libs` by default.
