plugins {
    id("nodera.java-library")
}

dependencies {
    implementation(project(":core"))
    // ChunkedStreams: compress snapshot/delta streams before splitting under payload caps (Task 4).
    implementation(libs.zstd.jni)
}
