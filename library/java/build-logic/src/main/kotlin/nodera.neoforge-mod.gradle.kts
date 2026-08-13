// Nodera NeoForge mod conventions (Task 1 §1/§3, Task 0 §3).
// Applies to the NeoForge-bound module: neoforge-mod.
// Uses ModDevGradle (net.neoforged.moddev) to provision the mojang-mapped NeoForge 21.1.x
// compile classpath. NeoForge runs on Java 21; the host JDK is 25 (see AGENTS.md), so we
// compile against the host JDK and target the SAME jvm.version as the pure-Java modules
// (host) so Gradle's org.gradle.jvm.version attribute matches across module boundaries —
// pinning 21 here is what previously broke dependency resolution against :core.
import net.neoforged.moddevgradle.dsl.NeoForgeExtension

plugins {
    id("net.neoforged.moddev")
    `java-library`
    `jvm-test-suite`
}

// NeoForge 21.1.x (MC 1.21.1, LTS). Single source of truth for the mod toolchain.
// 21.1.238 matches the real test client at ~/.minecraft (Task 0 §6 pin reconciliation; was 21.1.77).
the<NeoForgeExtension>().version = "21.1.238"

// JVM arguments every dev run gets, whatever its role.
//
// -XX:+EnableDynamicAgentLoading is for the spark profiler (docs/minecraft/spark/):
// spark resolves a sampled stack frame's class name to a Class<?> through a Java agent it
// loads at runtime, and on Java 21+ that is deprecated-by-default — it warns, and where
// disallowed it silently falls back to a weaker lookup, which shows up as OUR frames being
// attributed to nobody. Allowing it explicitly keeps source attribution honest. It is
// unconditional because it changes nothing when spark is absent.
//
// -Dnodera.spark.profile=<seconds> is the client-side capture bridge (SparkProfileBridge):
// the integrated server has no RCON, so the mod itself dispatches the sparkc commands. Read
// from the environment so the shell harness can set it per run without editing this file;
// absent means the bridge stays inert.
val noderaRunJvmArgs: List<String> = buildList {
    add("-XX:+EnableDynamicAgentLoading")
    // A Gradle run IS a development run, and that is now load-bearing rather than cosmetic: the
    // compiled default tracker/rendezvous lists are the project's official services unless this
    // property says otherwise (`dev.nodera.core.services.DefaultServices`). Without it a
    // `./gradlew runClient` with no config would announce to the production tracker instead of to
    // the localhost stack `scripts/dev.sh` starts. `NODERA_DEV` still wins, so a run deliberately
    // pointed at the real network (`dev.sh --official`) can say so.
    add("-Dnodera.dev=" + (providers.environmentVariable("NODERA_DEV").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { it == "1" || it.equals("true", ignoreCase = true) || it.equals("yes", ignoreCase = true) }
        ?: true))
    providers.environmentVariable("NODERA_SPARK_PROFILE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { add("-Dnodera.spark.profile=$it") }
    providers.environmentVariable("NODERA_SPARK_TICKS_OVER").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { add("-Dnodera.spark.ticksOver=$it") }
}

// Where a quick-play client connects. The default is the conventional dev port, which is what an
// interactive `scripts/dev.sh --play` still gets; the live harness overrides it, because it no
// longer runs on the product's ports.
//
// This was a literal "127.0.0.1:25599" in four run tasks. 25599 is the PRODUCT's game port
// (Topology.PRODUCTION_PORT_BASE + 99), and the harness used to bind exactly that — so one literal
// happened to be right for both audiences at once. Moving the harness to its own 26500 block (#266),
// so a developer with the companion app installed could run a suite at all, made it right for only
// one of them: the harness's server came up on 26599 and every scripted joiner went on dialling
// 25599, took "Connection refused", and sat on a DisconnectedScreen until the stage timed out. The
// scenario then reports "player B never joined" — a sentence about the product, for a stale address
// in a build script.
//
// An environment variable rather than a Gradle property because the harness starts these tasks as a
// `gradlew` subprocess and already controls its environment; defaulted here rather than required so
// the interactive path keeps working with nothing passed.
val noderaQuickPlayAddress: String = providers.environmentVariable("NODERA_E2E_GAME_ADDRESS").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "127.0.0.1:25599"

// The L-45 exit's first half: `runClient`/`runServer` launch from Gradle. The mod under test is
// this module's main source set; the harness (Xvfb log/screenshot assertions) drives these tasks.
the<NeoForgeExtension>().apply {
    mods.register("nodera") {
        sourceSet(project.the<SourceSetContainer>()["main"])
    }
    runs.configureEach {
        jvmArguments.addAll(noderaRunJvmArgs)
    }
    runs.register("client") {
        client()
    }
    runs.register("server") {
        server()
    }
    // Scripted join smoke (the L-45 acceptance's join half): boots a client straight into the
    // dev server via quick play, no GUI interaction needed. Own game dir so it does not fight
    // the interactive client run over locks/options.
    runs.register("clientJoin") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-join"))
        // Distinct offline username: with the continuity series running two dev clients at once, a
        // duplicate name/UUID login would kick the host player the moment the joiner connects.
        programArguments.addAll("--quickPlayMultiplayer", noderaQuickPlayAddress,
                "--username", "JoinerDev")
    }
    // Scripted HOST half of the continuity series (scripts/e2e-continuity.sh): boots a client
    // straight into the pre-baked shared world "NoderaE2E" — the Task 33 auto-re-share puts it on
    // the network with no GUI interaction. Own game dir for the same lock/options isolation.
    runs.register("clientHost") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-host"))
        programArguments.addAll("--quickPlaySingleplayer", "NoderaE2E",
                "--username", "HostDev")
    }
    // Player 1's NETWORK re-join (live Test 1, step 3): same game dir/identity as the host run,
    // but connecting as a client to whoever now hosts the world on the conventional dev port.
    runs.register("clientRejoin") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-host"))
        programArguments.addAll("--quickPlayMultiplayer", noderaQuickPlayAddress,
                "--username", "HostDev")
    }
    // Second scripted joiner (scripts/e2e-commands.sh / e2e-farlands.sh): a THIRD player slot so
    // two network clients can sit on one dedicated server simultaneously. Own game dir + username
    // — the run-join dir/name would collide with the first joiner's lock and login.
    runs.register("clientJoinTwo") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-join2"))
        programArguments.addAll("--quickPlayMultiplayer", noderaQuickPlayAddress,
                "--username", "JoinerTwo")
    }
    // Third scripted joiner (scripts/e2e-determinism.sh): the Phase-1 exit gate asks for THREE
    // dev clients playing at once, which is one more independent node re-executing the same
    // regions — the whole point of the determinism soak is disagreement between nodes, and two
    // nodes can only ever disagree pairwise. Own game dir + username, same as the others.
    runs.register("clientJoinThree") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-join3"))
        programArguments.addAll("--quickPlayMultiplayer", noderaQuickPlayAddress,
                "--username", "JoinerThree")
    }
    // Second interactive client for manual two-player testing (scripts/dev.sh --play): own game
    // dir + username, no quick play — the player drives the Nodera multiplayer UI by hand.
    runs.register("clientTwo") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-join"))
        programArguments.addAll("--username", "JoinerDev")
    }
}

// ModDevGradle forces a Java 21 toolchain onto the module (NeoForge runs on Java 21). Target
// release 21 to match the pure-Java modules (nodera.java-library) and keep the
// org.gradle.jvm.version attribute consistent across project dependencies.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:-options"))
    options.isDeprecation = false
}

// Same forwarding as nodera.java-library; see NoderaServiceBinaries.kt. These modules host no
// real-binary suite today, but the flag has to mean the same thing in every `Test` task or it
// means nothing.
val requireServiceBinaries = serviceBinaryRequirement()

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("FAILED", "SKIPPED", "STANDARD_ERROR")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "1g"
    requireServiceBinaries?.let { systemProperty(REQUIRE_SERVICE_BINARIES_PROPERTY, it) }
}

// Reproducible archives
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Shared test dependencies. Versions come from the build-logic-local `libs` catalog (same file
// as the root catalog; see build-logic settings).
val libs = the<VersionCatalogsExtension>().named("libs")
dependencies {
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testImplementation(libs.findLibrary("jqwik").get())

    compileOnlyApi(libs.findLibrary("jetbrains-annotations").get())
}

// NeoForge mod resources configuration: expand gradle.properties into the descriptor.
tasks.named<ProcessResources>("processResources") {
    val modId: String by project
    val modVersion: String by project
    val modName: String by project
    val modDescription: String by project

    inputs.property("modId", modId)
    inputs.property("modVersion", modVersion)
    inputs.property("modName", modName)
    inputs.property("modDescription", modDescription)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mapOf(
            "modId" to modId,
            "modVersion" to modVersion,
            "modName" to modName,
            "modDescription" to modDescription
        ))
    }

    filesMatching("*.mixins.json") {
        expand(mapOf("modId" to modId))
    }
}
