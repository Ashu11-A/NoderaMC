// build-logic: convention plugins shared across Nodera modules.
plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // jmh convention plugin (used by simulation benchmarks; resolved via portal).
    implementation("me.champeau.jmh:jmh-gradle-plugin:0.7.2")
}
