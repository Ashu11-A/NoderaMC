// Nodera root build. Pure-Java Phase 0 modules share the `nodera.java-library` convention
// (declared in build-logic). Group/version live here; module build files are thin.
group = "dev.nodera"
version = "0.1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
        // NeoForge runtime + ModDevGradle artifacts (Task 1/4 NeoForge toolchain).
        maven("https://maven.neoforged.net/releases")
    }
}
