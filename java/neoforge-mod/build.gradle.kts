plugins {
    id("nodera.neoforge-mod")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":simulation"))
    implementation(project(":consensus"))
    implementation(project(":transport-api"))
    implementation(project(":transport-socket"))
    implementation(project(":transport-rendezvous"))
    implementation(project(":transport-neoforge"))
    implementation(project(":peer-runtime"))
    implementation(project(":diagnostics"))
    implementation(project(":storage-api"))
}

// Produce a *runnable* mod jar. The Minecraft-free Nodera modules are pure-Java project deps and
// are NOT nested jars, so a bare `neoforge-mod.jar` would be missing every `dev.nodera.*` class at
// runtime and fail to load on a real server. Fold their compiled classes into the mod jar (a fat
// jar of our own code only — never Minecraft/NeoForge, which the loader provides).
val noderaBundled = listOf(
    ":core", ":protocol", ":simulation", ":consensus",
    ":transport-api", ":transport-socket", ":transport-rendezvous",
    ":storage-api", ":peer-runtime", ":diagnostics")

tasks.named<Jar>("jar") {
    dependsOn(noderaBundled.map { "$it:jar" })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ noderaBundled.map { zipTree(project(it).tasks.named<Jar>("jar").get().archiveFile) } })
}
