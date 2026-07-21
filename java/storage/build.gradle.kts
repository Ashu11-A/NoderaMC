plugins {
    id("nodera.java-library")
}

// storage: the unified Minecraft-free storage API (Java API unification, issue #30). One module,
// four layers, one seam:
//
//  - dev.nodera.storage          — the WorldStore seam + value types (ContentId, Checkpoint,
//                                  GenesisManifest, WorldIdentity, WorldPermissions) and the
//                                  shared support types (EventChainGuard, RegionOrder,
//                                  io.AtomicFileWriter).
//  - dev.nodera.storage.event    — in-memory event-sourced WorldStore + EventReplayer +
//                                  PeerSyncFlow (the certified prevRoot→resultingRoot chain).
//  - dev.nodera.storage.rocksdb  — the durable full-archive tier (RocksDB column families +
//                                  FsContentStore; replay-on-boot head recovery; proven by
//                                  RocksCrashRecoveryIT's forcibly-killed writer).
//  - dev.nodera.storage.client   — the bounded/quota'd client-side content store (never evicts
//                                  an assigned region's current state).
//
// core types appear throughout the public API, so core is an `api` dependency. rocksdbjni stays
// `implementation`-scoped: consumers see only the storage-api seam, and the mod jar (which
// bundles this module's classes but not the native lib) never classloads the rocksdb tier.
dependencies {
    api(project(":core"))
    implementation(libs.rocksdbjni)

    testImplementation(project(":peer"))
    testImplementation(project(":testkit"))
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
