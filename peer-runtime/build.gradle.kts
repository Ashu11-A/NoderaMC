plugins {
    id("nodera.java-library")
}

// peer-runtime: the Minecraft-free P2P session brain (Plan §4 peer-runtime; Task 9 subset for the
// Phase 6 continuity beta). Membership, gossip, heartbeat failure detection, deterministic gateway
// election, and gateway migration. Depends only on the transport SEAM (transport-api) + protocol
// messages — never on a concrete transport, so it runs identically over LoopbackTransport (fast
// unit tests) and SocketPeerTransport (real-socket integration tests).
dependencies {
    api(project(":core"))
    api(project(":transport-api"))
    implementation(project(":protocol"))

    testImplementation(project(":transport-socket"))
    testImplementation(project(":testkit"))
}
