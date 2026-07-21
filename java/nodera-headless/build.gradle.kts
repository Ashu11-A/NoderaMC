plugins {
    id("nodera.java-library")
    application
}

// nodera-headless (Task 32): the always-on peer worker — a Minecraft-free `main` over peer-runtime
// that boots a PeerRuntime, connects to the network, and serves the loopback control endpoint the
// Minecraft mod probes (so a player stays a network node even with Minecraft closed). The Tauri
// companion app (rust/nodera-app) supervises this process; `scripts/dev.sh` can also run it directly.
dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":transport-api"))
    implementation(project(":transport-socket"))
    implementation(project(":transport-rendezvous"))
    implementation(project(":peer-runtime"))
    implementation(project(":diagnostics"))
    implementation(project(":storage"))

    // SLF4J API for the worker's own logs, plus a tiny binding so they surface when run standalone
    // (Minecraft provides a binding in-game, but a standalone process needs its own).
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("dev.nodera.headless.HeadlessPeerMain")
    applicationName = "nodera-headless"
}
