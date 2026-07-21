plugins {
    id("nodera.java-library")
}

// transport: the unified Minecraft-free network API (Java API unification, issue #30). One
// module, two planes:
//
//  - dev.nodera.protocol.*            — the frozen wire contract: NoderaMessage records,
//                                       MessageCodec (append-only tags, mirrored byte-exact by
//                                       rust/nodera-codec against fixtures/wire/), ChunkedStreams
//                                       (zstd), and the handshake/membership/discovery/content/
//                                       rendezvous message families.
//  - dev.nodera.transport{,.socket,.rendezvous} — the carriers behind the PeerTransport seam:
//                                       SocketPeerTransport (direct TCP), RendezvousPeerTransport
//                                       (direct-first / punch-upgrade / E2E-encrypted relay
//                                       fallback over the Rust nodera-rendezvous service), plus
//                                       the shared Frames length-prefix framing.
//
// The in-game NeoForge relay lane (the former empty transport-neoforge placeholder) lives with
// the mod when it materializes — Minecraft types stay out of this module (layering rule 3).
dependencies {
    api(project(":core"))
    // ChunkedStreams: compress snapshot/delta streams before splitting under payload caps.
    implementation(libs.zstd.jni)
}

// Golden wire fixtures: the regeneration escape hatch documented on WireFixtureTest only works
// if the flag reaches the test JVM. Forwarded explicitly — Gradle does not pass -D through.
tasks.withType<Test>().configureEach {
    System.getProperty("nodera.fixtures.regenerate")?.let {
        systemProperty("nodera.fixtures.regenerate", it)
    }
}
