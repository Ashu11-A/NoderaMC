plugins {
    id("nodera.java-library")
    application
}

// worker: the always-on headless peer — `nodera-headless`, the process the Tauri companion app
// supervises and the Minecraft mod refuses to launch without.
//
// # Why this is its own module
//
// It used to be a package inside `:peer`, which meant the *worker executable* was compiled into
// anything that depended on the peer library — including the NeoForge mod, whose fat jar therefore
// shipped `HeadlessPeerMain` and every service behind it into every player's `mods/` folder. A
// worker inside the mod is a contradiction: the whole point of the worker is that it outlives the
// game, and a copy of it that loads with Minecraft can only ever be the wrong one.
//
// Splitting it out makes that structural rather than a rule somebody has to remember. `:worker`
// depends on `:peer`; nothing depends on `:worker` except the distribution. The mod cannot import
// the worker because the classes are not on its compile path, and cannot bundle it because they are
// not in its dependency graph.
//
// # What lives here
//
//  - dev.nodera.headless.* — HeadlessPeerMain (the entry point), WorkerControlHandler (the control
//    verbs), WorldHostingService (announce/registration), the archive/replication/telemetry
//    services, the world registry and key store, and the LAN session lane.
//
// The `application` plugin keeps the launcher name `nodera-headless`, which is contract: the Tauri
// app resolves `resources/nodera-headless/bin/nodera-headless` (daemon.rs) and every script and CI
// job stages the same installDist layout.
dependencies {
    api(project(":peer"))
    api(project(":core"))
    api(project(":transport"))
    api(project(":storage"))
    api(project(":engine"))

    // Argon2id (Task 23 bounded KDF) — the password lane the re-key verb drives.
    implementation(libs.bouncycastle)

    // SLF4J API for the worker's own logs, plus a tiny binding so they surface when it is run
    // standalone. The binding belongs HERE and not in `:peer`: a library that picks a logging
    // backend fights the host application for it, and this module IS the application.
    implementation("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")

    testImplementation(project(":testing"))
    testImplementation(project(":engine"))
}

application {
    mainClass.set("dev.nodera.headless.HeadlessPeerMain")
    applicationName = "nodera-headless"
}
