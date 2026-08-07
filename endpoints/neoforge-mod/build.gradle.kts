import net.neoforged.moddevgradle.dsl.NeoForgeExtension

plugins {
    id("nodera.neoforge-mod")
}

// The Minecraft-free Nodera modules must be part of the *mod* in dev runs: FML's module
// classloader isolates mod classes, so a plain project dependency on the run classpath is not
// visible to the mod at runtime (runServer died with NoClassDefFoundError on dev.nodera.* until
// these source sets joined the mod definition). Mirrors the production fat-jar bundling below.
val noderaModProjects = listOf(":core", ":transport", ":engine", ":storage", ":peer", ":endpoint")
noderaModProjects.forEach { evaluationDependsOn(it) }

the<NeoForgeExtension>().mods.named("nodera") {
    noderaModProjects.forEach { path ->
        sourceSet(project(path).extensions.getByType<SourceSetContainer>()["main"])
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(project(":engine"))
    implementation(project(":peer"))
    implementation(project(":storage"))
    implementation(project(":endpoint"))

    // Test scope only, and deliberately: `LayoutManifest` is how the source-scanning tests here find
    // this module's own `src/main/java`. They used to guess it — a CWD-relative path with a
    // hardcoded `java/neoforge-mod/...` fallback, duplicated in six files. Nothing from `:testing`
    // reaches the fat jar, which is built from `tasks.jar` plus `noderaBundled` below.
    testImplementation(project(":testing"))

    // `EventHandlerFirewallTest` reads this module's own bytecode: which methods NeoForge's game
    // bus can call, and whether each one's calls sit inside a catch. That is a question about
    // compiled code (try/catch ranges live in the exception table, not in the source text), which
    // is why it is ArchUnit rather than another source scanner.
    testImplementation(libs.archunit)
}

// NeoForge/log4j owns the SLF4J binding inside a Minecraft runtime; peer's slf4j-simple is for
// headless-worker use only and must not compete for the binding in runClient/runServer.
configurations.named("runtimeClasspath") {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

// MDG's dev-run legacy classpath carries only the NeoForge/vanilla libraries — our third-party
// runtime deps never reach the game unless declared here (first hit: RocksDB — the entity lane's
// world store NoClassDefFoundError'd on login). additionalRuntimeClasspath is MDG's seam for
// exactly this; production is unaffected (the fat jar + external-jar story is the dist lane's).
dependencies {
    "additionalRuntimeClasspath"(libs.rocksdbjni)
    "additionalRuntimeClasspath"(libs.caffeine)
    "additionalRuntimeClasspath"(libs.zstd.jni)
    "additionalRuntimeClasspath"(libs.roaringbitmap)
    "additionalRuntimeClasspath"(libs.bouncycastle)
}

// Produce a *runnable* mod jar. The Minecraft-free Nodera modules are pure-Java project deps and
// are NOT nested jars, so a bare mod jar would be missing every `dev.nodera.*` class at runtime and
// fail to load on a real server. Fold their compiled classes into the mod jar (a fat jar of our own
// code only — never Minecraft/NeoForge, which the loader provides).
val noderaBundled = listOf(
    ":core", ":transport", ":engine",
    ":storage", ":peer", ":endpoint")

tasks.named<Jar>("jar") {
    // `nodera-neoforge.jar` — the deliverable's name minus its version token. The token belongs to
    // the RELEASE, not to the build: the rolling prerelease publishes this file as
    // `nodera-neoforge-latest.jar` while `/VERSION` says 0.1.0, so stamping `archiveVersion` here
    // would produce a name that is right in exactly one of the two cases. `scripts/lib/release.sh`
    // owns the token and `scripts/release.sh` applies it when staging. Everything in-repo (dev.sh,
    // the live harness, `--install-mod`) therefore gets one constant file name.
    archiveBaseName.set("nodera-neoforge")
    archiveVersion.set("")
    dependsOn(noderaBundled.map { "$it:jar" })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ noderaBundled.map { zipTree(project(it).tasks.named<Jar>("jar").get().archiveFile) } })
}

// `ModJarCarriesNoEntryPointTest` inspects the jar above rather than this file, because what ships
// to a player is the artefact and not the intent. `:peer` now carries the always-on services (a
// peer IS a worker) AND the `nodera-headless` entry point, in separate source sets; only the first
// belongs in a mods/ folder, and the boundary that used to enforce that was a whole Gradle module.
// A test on the built jar is what replaces it.
tasks.named<Test>("test") {
    dependsOn(tasks.named<Jar>("jar"))
    systemProperty(
        "nodera.modJar",
        tasks.named<Jar>("jar").flatMap { it.archiveFile }.get().asFile.absolutePath,
    )
}
