plugins {
    id("nodera.java-library")
}

// core is Minecraft-free (Task 0 §4.1: depends on nothing but the JDK).
dependencies {
    // Deliberately empty: Task 2 acceptance #5 (ArchUnit enforces in testkit). core is the JDK and
    // nothing else. A comment here used to describe a fastutil dependency over this empty block;
    // fastutil was never used by core OR by engine, and is now gone from both.
}

// The product version reaches running Java code as a generated resource rather than as a source
// constant: `NoderaConstants.PRODUCT_VERSION` is published on the wire and shown in peer lists, and
// a hand-edited constant is the field that silently disagrees with the jar it ships in. Expansion is
// scoped to the one file so no other resource is exposed to template syntax.
tasks.named<ProcessResources>("processResources") {
    val noderaVersion = project.extra["noderaVersion"] as String
    inputs.property("noderaVersion", noderaVersion)
    filesMatching("dev/nodera/core/nodera-version.properties") {
        expand(mapOf("noderaVersion" to noderaVersion))
    }
}
