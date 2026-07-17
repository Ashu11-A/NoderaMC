plugins {
    id("nodera.neoforge-mod")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":simulation"))
    implementation(project(":consensus"))
    implementation(project(":transport-api"))
    implementation(project(":transport-socket"))
    implementation(project(":transport-neoforge"))
    implementation(project(":peer-runtime"))
    implementation(project(":storage-api"))
}
