plugins {
    id("nodera.java-library")
    // The benchmark harness (`src/jmh/java`, task `:peer:jmh`). See the JMH section at the bottom
    // of this file for why the peer module owns the measurement lane.
    alias(libs.plugins.jmh)
    // `nodera-headless` — the always-on node. See the HEADLESS section at the bottom for why the
    // entry point compiles in its own source set rather than in main.
    application
}

// peer: the unified Minecraft-free peer API (Java API unification, issue #30) AND the always-on
// node built from it. One module, five layers:
//
//  - dev.nodera.distribution.*  — the torrent data plane (piece split/select/download/reassemble,
//                                 encrypted manifests, ActivePlayerStream/EmergencyFlush).
//  - dev.nodera.peer.*          — the peer runtime: membership/gossip, gateway election, TickSync,
//                                 discovery (TrackerClient/BootstrapClient/PeerDirectory),
//                                 archival placement/audit/repair, the loopback control endpoint
//                                 (ControlProtocol v2 — the single wire the mod's CompanionClient
//                                 and rust/nodera-app's control.rs mirror).
//  - dev.nodera.diagnostics.*   — Minecraft-free telemetry + view models (consumed by the node,
//                                 the mod's HUD/screens, and the Tauri dashboard's STATE JSON).
//  - dev.nodera.peer.tunnel.*   — carrying somebody else's TCP connection across the network, so
//                                 an UNMODIFIED Minecraft opened to LAN can be joined from
//                                 anywhere. Detection (LanWatcher/LanBeacon) lives here too.
//  - dev.nodera.headless.*      — the always-on services: hosting, archive seeding, world registry
//                                 and key stores, ownership, replication, LAN sessions, the control
//                                 handler. A PEER IS A WORKER; these are not optional extras.
//
// # Why `dev.nodera.headless` came back
//
// It was split out to `:worker` on 2026-07-26 for one good reason: compiling the executable here
// put it inside everything that depends on the peer library, including the NeoForge mod's fat jar.
// The cost was that a peer WITHOUT the always-on services stayed constructible, so "every peer
// serves" was a convention somebody had to remember rather than something the code enforced.
//
// Both are now true at once. The services live in main, so there is no way to depend on the peer
// stack and not get them. The entry point lives in `src/headless`, which `tasks.jar` does not
// carry, so the fat jar still cannot contain a `main`. See the HEADLESS section at the bottom.
dependencies {
    api(project(":core"))
    api(project(":transport"))
    api(project(":storage"))
    // The validation lane (dev.nodera.peer.validation): the worker runs committee re-execution
    // out-of-game — CommitteeMember/VoteCollector re-execute over the engine and vote over the
    // PeerTransport (L-48/L-30). engine's test suite depends back on :peer at TEST scope only,
    // so the main-configuration graph stays acyclic.
    api(project(":engine"))
    // Argon2id (Task 23 bounded KDF) — the only BouncyCastle use in the codebase.
    implementation(libs.bouncycastle)

    // SLF4J API only. The BINDING belongs to whatever application embeds this — the worker picks
    // slf4j-simple, NeoForge provides log4j, and a library that chose for them would fight the host
    // for the binding (which is exactly why the mod had to exclude slf4j-simple from its runtime).
    implementation("org.slf4j:slf4j-api:2.0.9")

    testImplementation(project(":engine"))
    testImplementation(project(":testing"))
    // Test-only, and test-only ON PURPOSE: EntityTransferCrashRecoveryIT opens a real
    // RocksWorldStore and kills the writer. :storage stopped exporting rocksdbjni transitively
    // (see the note there — it was 67 MB of natives in a jar that never classloads the tier), so
    // the IT declares what it actually needs. Production code in this module must NOT import
    // org.rocksdb; `PeerJarPayloadTest` fails the build if the natives reappear in the artefact.
    testImplementation(libs.rocksdbjni)
    // Test-only: DistributionIT rebuilds the post-batch snapshot with the REAL Phase 1
    // SnapshotDeltaApplier, so the state it splits is the state a replica would actually hold.
    // The structural report reads the whole tree's bytecode; ASM is how.
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
    // SnapshotVersionScopeTest fences the chain height out of the freshness plane. A rule is what
    // holds that line; a comment saying "do not compare versions across peers" is what it replaced.
    testImplementation(libs.archunit)

    // The benchmark source set measures the REAL classes: it compiles against this module's main
    // output (the plugin wires that) plus the deterministic engine fixtures it needs to build a
    // region snapshot that looks like one the engine actually produces.
    "jmhImplementation"(project(":core"))
    "jmhImplementation"(project(":transport"))
    "jmhImplementation"(project(":storage"))
    "jmhImplementation"(project(":engine"))
}

// ---------------------------------------------------------------------------------------------
// HEADLESS — the always-on node (`src/headless/java`, launcher `nodera-headless`)
// ---------------------------------------------------------------------------------------------
//
// # Why the entry point gets its own source set
//
// `:peer` is bundled into the NeoForge mod's fat jar (neoforge-mod/build.gradle.kts zips this
// module's `jar` output into the mod's). Putting `HeadlessPeerMain` in main would therefore ship a
// launchable `main` inside every player's mods/ folder. `:worker` existed to make that impossible
// STRUCTURALLY rather than by remembering — and that is worth keeping, so the guarantee moved here
// instead of being dropped.
//
// `src/headless` compiles against main's output and is packaged only into the distribution.
// `tasks.jar` carries main alone, so the fat jar physically cannot contain the entry point — see
// `theModJarCarriesNoEntryPoint` in :neoforge-mod, which asserts exactly that on the built artifact.
//
// # What is in it
//
// One class. `HeadlessPeerMain` reads the environment, hands it to `PeerNode`, and waits. Every
// service it composes lives in main, because a peer that does not run them is not a peer.
val headless: SourceSet = sourceSets.create("headless") {
    compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

// The tests exercise the entry point's own state machine (HeadlessPeerMainStateTest,
// WorldContinuityIT, the structural probe), so the test source set sees it too.
sourceSets["test"].apply {
    compileClasspath += headless.output
    runtimeClasspath += headless.output
}

// Declared here rather than in the block above because `sourceSets.create` is what CREATES these
// configurations, and that call is three lines up.
dependencies {
    // The SLF4J BINDING, for the entry point only. A library must not choose a binding for its host
    // — NeoForge provides log4j and would fight it — so this is scoped to the source set that IS an
    // application. That scoping is what makes neoforge-mod's `exclude(slf4j-simple)` redundant
    // rather than load-bearing.
    "headlessRuntimeOnly"("org.slf4j:slf4j-simple:2.0.9")
}

val headlessJar = tasks.register<Jar>("headlessJar") {
    description = "The always-on entry point, packaged apart from the peer library."
    archiveClassifier.set("headless")
    from(headless.output)
}

application {
    mainClass.set("dev.nodera.headless.HeadlessPeerMain")
    // CONTRACT. `rust/nodera-app`'s daemon.rs resolves `resources/nodera-headless/bin/nodera-headless`,
    // tauri.{linux,macos,windows}.conf.json bundle `build/nodera-headless/**`, and CI plus every
    // script stages that exact name. The Gradle task path changed with this merge
    // (`:worker:installDist` → `:peer:installDist`); the ARTEFACT name did not, and must not.
    applicationName = "nodera-headless"
}

// The `application` plugin's defaults are the peer LIBRARY's jar and runtime classpath, which is
// everything except the entry point itself and its logging binding. Both halves are added here, to
// the launcher and to the distribution, and they must stay in step: a start script whose classpath
// disagrees with what `installDist` copied fails at run time with a NoClassDefFoundError and
// nothing catches it at build time.
val headlessDistFiles = files(headlessJar) + configurations["headlessRuntimeClasspath"]

tasks.named<CreateStartScripts>("startScripts") {
    classpath = headlessDistFiles + files(tasks.named<Jar>("jar")) + configurations["runtimeClasspath"]
}

distributions.named("main") {
    contents {
        // The peer library's own jar arrives from the plugin's default contents AND from the
        // headless runtime classpath, which resolves it too. Same file, twice.
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        into("lib") { from(headlessDistFiles) }
    }
}

// `application` attaches distTar/distZip to `assemble`; nothing consumes the archives (every
// consumer stages `build/install/nodera-headless`), and building two of them on each `check` is
// pure CI minutes.
tasks.named("distTar") { enabled = false }
tasks.named("distZip") { enabled = false }

// ---------------------------------------------------------------------------------------------
// `nodera-peer.jar` — the headless node as ONE file
// ---------------------------------------------------------------------------------------------
//
// `installDist` produces a directory: a launcher script plus ~30 jars in `lib/`. That is what the
// companion app bundles (tauri.*.conf.json stage `build/nodera-headless/**`) and it is the right
// shape there, because the app already owns a directory of resources.
//
// It is the wrong shape for a RELEASE asset. Somebody running a peer on a VPS, in a container, or
// beside a Paper server downloads one file and runs `java -jar`; handing them a tarball of a
// directory whose launcher script resolves a 30-entry relative classpath is a support burden with
// no upside. So the release deliverable is a fat jar, and both shapes are built from the same
// source sets rather than one being reconstructed from the other.
//
// Three things make an assembled jar runnable that a naive `zipTree` of the classpath does not:
//
//   * `Main-Class`, or `java -jar` refuses to start;
//   * dropping `META-INF/*.SF|*.DSA|*.RSA|*.EC` — BouncyCastle ships SIGNED, and a signed jar's
//     digests do not describe a jar it was merged into, so the JVM throws `SecurityException:
//     Invalid signature file digest` on the first class load. This is silent at build time;
//   * merging `META-INF/services/*` rather than letting one provider file win. `INCLUDE` appends
//     the entries, which is what a ServiceLoader registry needs (slf4j-simple's provider is the one
//     that matters here; `PeerJarIsRunnableTest` asserts it survives).
//
// The version token is deliberately absent from the file name — see the note in
// `endpoints/neoforge-mod/build.gradle.kts`.
val peerJar = tasks.register<Jar>("peerJar") {
    group = "distribution"
    description = "The headless node as a single runnable jar — the `nodera-peer` release asset."
    archiveBaseName.set("nodera-peer")
    archiveVersion.set("")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    manifest {
        attributes(
            "Main-Class" to "dev.nodera.headless.HeadlessPeerMain",
            "Implementation-Title" to "Nodera headless peer",
            "Implementation-Version" to project.extra["noderaVersion"].toString(),
            // RocksDB and zstd-jni load native code out of the jar; both use the context
            // classloader, so nothing else is needed here. Stated because the next person to see a
            // fat jar with native deps will wonder.
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }

    from(sourceSets["main"].output)
    from(headless.output)
    from({
        (configurations["runtimeClasspath"] + configurations["headlessRuntimeClasspath"])
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("META-INF/services/*") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
}

// `assemble` builds it, so `./gradlew build` and the release lane cannot disagree about whether the
// deliverable compiles. The check that it RUNS is `PeerJarIsRunnableTest`, which launches it.
tasks.named("assemble") { dependsOn(peerJar) }

tasks.named<Test>("test") {
    dependsOn(peerJar)
    // Same contract as `:neoforge-mod`'s `nodera.modJar`: the test inspects and executes the
    // artefact, because what ships is the file and not the task that made it.
    systemProperty("nodera.peerJar", peerJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------
// JMH — the structural benchmark lane (`src/jmh/java`, package `dev.nodera.bench`)
// ---------------------------------------------------------------------------------------------
//
// # Why the benchmarks live in `:peer` and not in a module of their own
//
// The three costs this project actually pays at runtime — how long a peer takes to find other
// peers, how long a region takes to move across the swarm, and how much CPU the always-on runtime
// burns per tick — are all paid by classes in THIS module. A separate `:benchmarks` module would
// have to depend on `:peer` anyway, would drift out of step with it, and would tempt benchmarks to
// re-implement the code under measurement. A `jmh` source set sits next to the code, compiles
// against the same output the worker ships, and is invisible to every consumer of the library.
//
// # The lanes (one class per lane, `Lane` in the report groups them)
//
//   DiscoveryBenchmark   — route parse → directory ingest → candidate ranking → service selection
//   ChunkSyncBenchmark   — snapshot → blob → pieces → selection → reassembly → root verification
//   WireBenchmark        — canonical encode/decode of the messages those two lanes send
//   RuntimeBenchmark     — the per-tick/per-event work the worker does while nothing is wrong
//
// # Running
//
//   ./gradlew :peer:jmh                          # full run (minutes)
//   ./gradlew :peer:jmh -Pbench.quick            # CI/pre-commit shape (~2 min, wider error bars)
//   ./gradlew :peer:jmh -Pbench.include=Discovery
//   ./gradlew :peer:benchmarkReport              # jmh + the Markdown report + baseline comparison
//
// Results are written as JSON because that is the only format `scripts/bench-report.py` can diff
// against `fixtures/bench/baseline.json` — a benchmark nobody compares to anything is a number, not
// a gate.
val benchQuick = providers.gradleProperty("bench.quick").isPresent
val benchInclude = providers.gradleProperty("bench.include").orNull

jmh {
    jmhVersion.set(libs.versions.jmhCore.get())
    // One forked JVM per benchmark: JIT state from a previous benchmark is exactly the kind of
    // contamination that makes a "5% regression" untraceable.
    fork.set(if (benchQuick) 1 else 2)
    warmupIterations.set(if (benchQuick) 2 else 5)
    iterations.set(if (benchQuick) 3 else 8)
    warmup.set(if (benchQuick) "500ms" else "1s")
    timeOnIteration.set(if (benchQuick) "500ms" else "1s")
    failOnError.set(true)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    if (benchInclude != null) {
        includes.set(listOf(benchInclude))
    }
}

// Benchmarks are not run by `check` (they take minutes and prove nothing about correctness), but
// they are COMPILED by it. A benchmark source set that nobody compiles rots against the API it
// measures, and the first person to need a number discovers that months later.
tasks.named("check") {
    dependsOn(tasks.named("jmhClasses"))
}

// The report is the deliverable; the JSON is an intermediate. This task turns one run into
// `build/reports/nodera/BENCHMARKS.md` and compares every result against the committed baseline,
// which is what turns "we measured it once" into "we would notice if it got slower".
tasks.register<Exec>("benchmarkReport") {
    group = "verification"
    description = "Run the peer benchmarks and render build/reports/nodera/BENCHMARKS.md."
    dependsOn(tasks.named("jmh"))
    val root = rootProject.layout.projectDirectory
    workingDir = root.asFile
    commandLine(
        "python3", root.file("scripts/bench-report.py").asFile.absolutePath,
        "--results", layout.buildDirectory.file("results/jmh/results.json").get().asFile.absolutePath,
        "--out", root.file("build/reports/nodera/BENCHMARKS.md").asFile.absolutePath,
    )
}

// ---------------------------------------------------------------------------------------------
// STRUCTURE — the whole-tree code report (`src/test/java/dev/nodera/structure`, tag `structure`)
// ---------------------------------------------------------------------------------------------
//
//   ./gradlew :peer:structureReport                          # full: analysis + debugger probe
//   ./gradlew :peer:structureReport -Pstructure.debug=false  # static half only (seconds)
//
// Outputs land in `build/reports/nodera/` (STRUCTURE.md, structure.json, runtime-profile.json).
// Moved here from `:worker` with that module; the task path changed, nothing else did.
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
    // see, so a module that is not compiled is a module whose callers vanish. `:peer`'s own source
    // sets are named rather than listed as project paths — they are this project.
    dependsOn(
        ":core:classes", ":core:testClasses",
        ":engine:classes", ":engine:testClasses",
        ":transport:classes", ":transport:testClasses",
        ":storage:classes", ":storage:testClasses",
        ":testing:classes",
        ":endpoint:classes", ":endpoint:testClasses",
        ":neoforge-mod:classes", ":neoforge-mod:testClasses",
        ":paper-plugin:classes",
        tasks.named("classes"), tasks.named("testClasses"),
        tasks.named("jmhClasses"), tasks.named("headlessClasses"),
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
