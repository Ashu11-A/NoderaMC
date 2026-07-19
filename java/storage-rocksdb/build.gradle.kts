plugins {
    id("nodera.java-library")
}

// storage-rocksdb: the full-archive durable WorldStore tier (Task 9, Phase 5). RocksWorldStore
// implements the same storage-api seam as the in-memory event-sourced store, over RocksDB column
// families (events / checkpoints / certificates / regions / meta) with WAL-backed atomic writes;
// FsContentStore holds content-addressed blobs on the filesystem (<store>/content/ab/cd/<hash>,
// atomic temp+move writes, hash-verified reads). Heads are recovered from the log on open (the
// replay-on-boot window), so an unclean kill can never leave a torn chain: the log is the truth.
// Proven by RocksCrashRecoveryIT (forcibly killed writer JVM ⇒ clean reopen, intact chain).
dependencies {
    api(project(":core"))
    api(project(":storage-api"))
    implementation(libs.rocksdbjni)
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
