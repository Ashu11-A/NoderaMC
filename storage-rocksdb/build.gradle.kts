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
