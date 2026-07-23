// build-logic: convention plugins shared across Nodera modules.
plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    // ModDevGradle (NeoForge toolchain) is published on the NeoForge maven, not the portal.
    maven("https://maven.neoforged.net/releases")
}

dependencies {
    // jmh convention plugin (used by simulation benchmarks; resolved via portal).
    implementation(libs.plugin.jmh)
    // NeoForge toolchain: provides the mojang-mapped compile classpath for neoforge-mod /
    // transport-neoforge. Pinned here (single source, like jmh). Task 0 §3.
    implementation("net.neoforged:moddev-gradle:2.0.142")
}
