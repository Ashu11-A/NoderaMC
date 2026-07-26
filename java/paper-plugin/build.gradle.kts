plugins {
    id("nodera.java-library")
}

// paper-plugin: `nodera-endpoint.jar` — the Paper/Folia endpoint (server task 1, L-61).
//
// The Paper API is `compileOnly` and lives on PaperMC's maven: the server provides it at runtime,
// and bundling it would shade a second copy of the platform into the plugin. The repository is
// declared here rather than in the root `allprojects` block so a network outage can only ever fail
// THIS module — every Minecraft-free module keeps building.
repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":core"))

    testImplementation(project(":core"))
}

// The harness stages `java/paper-plugin/build/libs/nodera-endpoint.jar` (scripts/lib/e2e-server.sh),
// so the artifact name is part of the contract rather than a convenience.
tasks.jar {
    archiveBaseName.set("nodera-endpoint")
    archiveVersion.set("")
    // The plugin's classes need `dev.nodera.core` at runtime and the server provides no such
    // library, so core rides inside the jar. Only core: the endpoint deliberately does not carry
    // the whole peer stack yet — task 2 decides how the worker is reached.
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("core") }
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
