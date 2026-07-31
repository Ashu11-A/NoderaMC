plugins {
    id("nodera.java-library")
}

// storage: the unified Minecraft-free storage API (Java API unification, issue #30). One module,
// five layers, one seam:
//
//  - dev.nodera.storage          — the WorldStore seam + value types (ContentId, Checkpoint,
//                                  GenesisManifest, WorldIdentity, WorldPermissions) and the
//                                  shared support types (EventChainGuard, RegionOrder,
//                                  io.AtomicFileWriter).
//  - dev.nodera.storage.event    — in-memory event-sourced WorldStore + EventReplayer +
//                                  PeerSyncFlow (the certified prevRoot→resultingRoot chain).
//  - dev.nodera.storage.rocksdb  — the durable full-archive tier (RocksDB column families;
//                                  replay-on-boot head recovery; proven by
//                                  RocksCrashRecoveryIT's forcibly-killed writer). The ONLY
//                                  package here that needs a native library.
//  - dev.nodera.storage.fs       — the content-addressed filesystem blob tier (FsContentStore:
//                                  atomic writes, hash-verified reads, java.nio.file only). Split
//                                  out of `rocksdb` on 2026-07-31 — see its package-info.
//  - dev.nodera.storage.client   — the bounded/quota'd client-side content store (never evicts
//                                  an assigned region's current state).
//
// core types appear throughout the public API, so core is an `api` dependency.
//
// rocksdbjni is `compileOnly`, NOT `implementation`, and the difference is 70 MB of shipped bytes.
//
// `implementation` keeps the library off a CONSUMER'S COMPILE classpath but still puts it on their
// RUNTIME classpath, so every consumer of :storage inherited rocksdbjni whether or not it ever
// touched the rocksdb tier. :peer does not: its only `dev.nodera.storage.rocksdb` use is
// FsContentStore, which is pure java.nio.file. So the headless peer was carrying rocksdbjni's
// fourteen platform natives — 67 MB compressed, 62% of `nodera-peer.jar` and the single largest
// item in every desktop installer — for a class it never loads.
//
// The one production caller of the rocksdb tier is the NeoForge mod, and it does not get the
// library from here either: `endpoints/neoforge-mod/build.gradle.kts` puts rocksdbjni on its own
// `additionalRuntimeClasspath` explicitly. So the tier's real runtime users declare it, and
// nobody pays for it by accident. `PeerJarPayloadTest` weighs the built artefact and holds the
// line — a re-declaration three modules away fails there.
dependencies {
    api(project(":core"))
    compileOnly(libs.rocksdbjni)

    // The tier's own tests DO load it: RocksWorldStoreTest and the forcibly-killed
    // RocksCrashRecoveryIT (whose victim JVM inherits this test runtime classpath).
    testImplementation(libs.rocksdbjni)
    testImplementation(project(":peer"))
    testImplementation(project(":testing"))
}

// rocksdbjni extracts its ~50 MB native library into java.io.tmpdir once per JVM, and the
// forcibly-killed RocksCrashRecoveryIT victim never reaches its deleteOnExit hook. Point the test
// tmpdir (inherited by the victim via the IT's ProcessBuilder) into the build directory so leaked
// extractions and JUnit @TempDir data are bounded and removed by `gradlew clean` instead of
// filling the system /tmp.
tasks.test {
    val testTmp = layout.buildDirectory.dir("test-tmp").get().asFile
    doFirst { testTmp.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.absolutePath)
}
