plugins {
    id("nodera.java-library")
    application
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
//  - dev.nodera.headless.*      — the always-on worker executable (HeadlessPeerMain +
//                                 WorkerControlHandler + WorldHostingService). The `application`
//                                 plugin keeps the launcher name `nodera-headless`: the Tauri
//                                 companion app resolves resources/nodera-headless/bin/nodera-headless
//                                 (daemon.rs) and scripts/dev.sh runs the same installDist layout.
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

    // SLF4J API for the worker's own logs, plus a tiny binding so they surface when run
    // standalone (Minecraft provides a binding in-game).
    implementation("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")

    testImplementation(project(":engine"))
    testImplementation(project(":testing"))
    // Test-only: DistributionIT rebuilds the post-batch snapshot with the REAL Phase 1
    // SnapshotDeltaApplier, so the state it splits is the state a replica would actually hold.
}

application {
    mainClass.set("dev.nodera.headless.HeadlessPeerMain")
    applicationName = "nodera-headless"
}
