plugins {
    id("nodera.java-library")
}

// core is Minecraft-free (Task 0 §4.1: depends on nothing but the JDK).
dependencies {
    // fastutil: primitive maps in the hot simulation/encoding paths (shaded later; core only needs APIs here).
    // Keep core dependency-free for now to honour Task 2 acceptance #5 (ArchUnit enforces in testkit).
}
