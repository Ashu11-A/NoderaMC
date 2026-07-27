plugins {
    id("nodera.java-library")
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
}
