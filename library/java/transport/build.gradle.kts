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

    // Test scope only: `LayoutManifest` is how WireSchemaGenerator locates the Rust crate it
    // generates into. It used to hardcode `rust/nodera-codec/src/kinds.rs`, which went stale
    // the moment that crate moved. `:testing` depends back on this module at MAIN scope, so
    // this is a test-scope back-edge — the same shape `:peer` and `:storage` already use.
    testImplementation(project(":testing"))
}

// Golden wire fixtures and generated schema artefacts: the regeneration escape hatches documented
// on WireFixtureTest and WireSchemaGeneratorTest only work if the flag reaches the test JVM.
// Forwarded explicitly — Gradle does not pass -D through.
tasks.withType<Test>().configureEach {
    // nodera.wire.previousCorpus: PreviousReleaseCorpusTest reads a previous revision's frames out
    // of the directory this names. Without the forward the test would find nothing and CI would be
    // asserting compatibility it never checked.
    for (flag in listOf("nodera.fixtures.regenerate", "nodera.wire.regenerate",
            "nodera.wire.previousCorpus")) {
        System.getProperty(flag)?.let { systemProperty(flag, it) }
    }
}
