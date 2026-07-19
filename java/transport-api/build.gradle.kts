plugins {
    id("nodera.java-library")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.caffeine)
}
