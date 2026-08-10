plugins {
    id("nodera.java-library")
}

// paper-plugin: `nodera-paper.jar` — the Paper/Folia endpoint (server task 1, L-61).
//
// The Paper API is `compileOnly` and lives on PaperMC's maven: the server provides it at runtime,
// and bundling it would shade a second copy of the platform into the plugin. The repository is
// declared here rather than in the root `allprojects` block so a network outage can only ever fail
// THIS module — every Minecraft-free module keeps building.
repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    // EngineHub, for WorldEdit's API. Same isolation argument as PaperMC's above, and for the same
    // reason: both are `compileOnly` platform APIs that only THIS module ever needs.
    maven("https://maven.enginehub.org/repo/") {
        name = "enginehub"
    }
}

dependencies {
    compileOnly(libs.paper.api)
    // WorldEdit, and only WorldEdit, gets a compile-time dependency — because it is the only member
    // of the corpus whose writes are INVISIBLE to Bukkit. `//set` fires no BlockPlaceEvent; it goes
    // straight down WorldEdit's own extent pipeline into the world. A foreign-write bridge built on
    // Bukkit events alone would report "certified" for a player placing one block by hand and see
    // NOTHING AT ALL for the operation L-65's exit clause actually names, which is a green test
    // that asserts nothing. `compat/WorldEditBulkWrites` is the answer and this is what it compiles
    // against; `worldedit-core` is enough, since the extent pipeline, the event bus and the block
    // states all live in it and `worldedit-bukkit` would only add adapters Bukkit already gives us.
    //
    // compileOnly and never bundled: a server with WorldEdit provides it, and a server WITHOUT it
    // must still load this plugin — which is why every reference to these types sits behind the
    // guarded install in `NoderaEndpointPlugin`.
    compileOnly(libs.worldedit.core)
    implementation(project(":core"))
    // The peer stack. This plugin is how an UNMODIFIED Paper/Folia server joins the network: the
    // server has no companion app supervising a worker beside it, so the peer runs in-process here.
    // That is the opposite trade from the NeoForge mod — see java/worker/build.gradle.kts — and it
    // is deliberate: a server operator installs one jar, a player installs an app.
    implementation(project(":peer"))
    implementation(project(":endpoint"))

    testImplementation(project(":core"))
    // CrossFoliaRegionCommitIT drives the REAL engine primitives — WorldMutationApplier,
    // RegionPipeline, EntityTransferCoordinator, JointTransferApprover — over the real durable
    // TransferStore. Both arrive transitively as `api` from `:peer`; naming them here is what says
    // the suite depends on them directly rather than by accident. `:testing` supplies
    // EngineFixtures, so the snapshots the suite commits are the same ones every other engine
    // suite commits.
    testImplementation(project(":engine"))
    testImplementation(project(":storage"))
    testImplementation(project(":testing"))
    // ForeignWriteBridgeTest asserts the payload a plugin subscribing to NoderaRegionDeniedEvent
    // receives, so the Bukkit event types have to be on the TEST classpath even though they are
    // compileOnly for main. No server is started: `Location`'s world is nullable and Bukkit's
    // HandlerList is a plain object, which is the whole reason deliverable 3 is testable at all.
    testCompileOnly(libs.paper.api)
    testRuntimeOnly(libs.paper.api)
}

// The harness stages `endpoints/paper-plugin/build/libs/nodera-paper.jar` (TestPaths), so the
// artifact name is part of the contract rather than a convenience. It is also the deliverable's
// name minus its version token — see the note in `endpoints/neoforge-mod/build.gradle.kts` for why
// the token belongs to the release lane and not to `archiveVersion`.
tasks.jar {
    archiveBaseName.set("nodera-paper")
    archiveVersion.set("")
    // The plugin's classes need the Nodera stack at runtime and the server provides none of it, so
    // our own modules ride inside the jar. Ours only — never Paper, never a third-party library the
    // server already has, which would shade a second copy of the platform into the plugin.
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { artifact ->
                listOf("core", "transport", "engine", "storage", "peer", "endpoint")
                    .any { artifact.name.startsWith("$it-") || artifact.name == "$it.jar" }
            }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// `paper-plugin.yml` carries ${version}; expand it from the root VERSION like every other artifact.
tasks.processResources {
    // settings.gradle.kts injects the root VERSION into every project as `noderaVersion`;
    // `project.version` is only set on the root, so reading it here would stamp
    // "unspecified" into the descriptor an operator reads in /plugins.
    val pluginVersion = project.extra["noderaVersion"].toString()
    inputs.property("version", pluginVersion)
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}
