plugins {
    id("nodera.java-library")
    // The benchmark harness (`src/jmh/java`, task `:peer:jmh`). See the JMH section at the bottom
    // of this file for why the peer module owns the measurement lane.
    alias(libs.plugins.jmh)
}

// peer: the unified Minecraft-free peer API (Java API unification, issue #30). One module, four
// layers, one runnable worker:
//
//  - dev.nodera.distribution.*  — the torrent data plane (piece split/select/download/reassemble,
//                                 encrypted manifests, ActivePlayerStream/EmergencyFlush).
//  - dev.nodera.peer.*          — the peer runtime: membership/gossip, gateway election, TickSync,
//                                 discovery (TrackerClient/BootstrapClient/PeerDirectory),
//                                 archival placement/audit/repair, the loopback control endpoint
//                                 (ControlProtocol v2 — the single wire the mod's CompanionClient
//                                 and rust/nodera-app's control.rs mirror).
//  - dev.nodera.diagnostics.*   — Minecraft-free telemetry + view models (consumed by the worker,
//                                 the mod's HUD/screens, and the Tauri dashboard's STATE JSON).
//  - dev.nodera.peer.tunnel.*   — carrying somebody else's TCP connection across the network, so
//                                 an UNMODIFIED Minecraft opened to LAN can be joined from
//                                 anywhere. Detection (LanWatcher/LanBeacon) lives here too.
//
// The always-on worker executable used to be a package in this module (`dev.nodera.headless`). It
// is now `:worker`, because compiling it here put the worker inside anything that depends on the
// peer library — including the NeoForge mod's fat jar. A library and an application have different
// jobs; see `java/worker/build.gradle.kts`.
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
    // Test-only: DistributionIT rebuilds the post-batch snapshot with the REAL Phase 1
    // SnapshotDeltaApplier, so the state it splits is the state a replica would actually hold.

    // The benchmark source set measures the REAL classes: it compiles against this module's main
    // output (the plugin wires that) plus the deterministic engine fixtures it needs to build a
    // region snapshot that looks like one the engine actually produces.
    "jmhImplementation"(project(":core"))
    "jmhImplementation"(project(":transport"))
    "jmhImplementation"(project(":storage"))
    "jmhImplementation"(project(":engine"))
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
