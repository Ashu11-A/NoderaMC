plugins {
    id("nodera.java-library")
}

dependencies {
    implementation(project(":core"))
    // DeterministicRandom uses java.util.random RandomGeneratorFactory (JDK only).
    // fastutil for primitive-backed section arrays in the hot path.
    implementation(libs.fastutil)
}
