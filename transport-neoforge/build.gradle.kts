plugins {
    id("nodera.neoforge-mod")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":transport-api"))
}
