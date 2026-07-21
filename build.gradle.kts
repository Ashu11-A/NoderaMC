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

// Line coverage on every Java module (XML for aggregation, HTML for humans). Applied here
// rather than in the precompiled convention scripts: naming `jacoco` inside a precompiled
// script plugin's plugins{} block breaks kotlin-dsl accessor generation on this toolchain.
subprojects {
    plugins.withId("java") {
        apply(plugin = "jacoco")
        tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        tasks.named("check") {
            dependsOn("jacocoTestReport")
        }
    }
}
