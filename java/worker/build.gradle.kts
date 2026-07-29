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

    // The structural code report (`dev.nodera.structure`, task `structureReport`) reads the
    // compiled bytecode of every module. TEST scope only: nothing shipped depends on ASM, and the
    // Android bytecode guard never sees it.
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
}

application {
    mainClass.set("dev.nodera.headless.HeadlessPeerMain")
    applicationName = "nodera-headless"
}

// ---------------------------------------------------------------------------------------------
// The structural code report (`dev.nodera.structure`)
// ---------------------------------------------------------------------------------------------
//
// # Why it lives in `:worker` and why it is not part of `test`
//
// It needs two things at once: the compiled output of EVERY module (to know who calls what), and
// the ability to launch the real `nodera-headless` process under a debugger (to know what actually
// runs). `:worker` is the only module that can have both — it is the application, and nothing
// depends on it, so pointing it at the whole tree creates no cycle.
//
// It is tagged `structure` and excluded from `:worker:test` so that one module's unit-test feedback
// never waits on nine modules compiling. `structureReport` declares those compilations as real task
// dependencies instead, which is also what makes its "module missing" failure impossible in normal
// use.
//
//   ./gradlew :worker:structureReport                        # full: analysis + debugger probe
//   ./gradlew :worker:structureReport -Pstructure.debug=false  # static half only (seconds)
//
// Outputs land in `build/reports/nodera/` (STRUCTURE.md, structure.json, runtime-profile.json).
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("structure")
    }
}

tasks.register<Test>("structureReport") {
    group = "verification"
    description = "Analyse the whole tree's bytecode + a debugger-instrumented worker run."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("structure")
    }
    // Compiled output of everything: the analysis is only as honest as the set of callers it can
    // see, so a module that is not compiled is a module whose callers vanish.
    dependsOn(
        ":core:classes", ":core:testClasses",
        ":engine:classes", ":engine:testClasses",
        ":transport:classes", ":transport:testClasses",
        ":storage:classes", ":storage:testClasses",
        ":peer:classes", ":peer:testClasses", ":peer:jmhClasses",
        ":worker:classes", ":worker:testClasses",
        ":testing:classes",
        ":neoforge-mod:classes", ":neoforge-mod:testClasses",
        ":paper-plugin:classes",
    )
    // The debugger probe runs by default: without it the report can list expensive code but not
    // say whether any of it executes. `-Pstructure.debug=false` skips just that half.
    systemProperty("nodera.structure.debug",
        providers.gradleProperty("structure.debug").getOrElse("true"))
    // The JDI event pump plus a full-tree class graph; the 1g default is not enough.
    maxHeapSize = "2g"
    // The report is the output, and it is regenerated whenever anything it reads changes — which
    // is "the whole tree", so caching it would mean reporting on a tree that no longer exists.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }
}
