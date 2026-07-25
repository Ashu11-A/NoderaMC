# `java/build-logic`

<!-- AI-AGENT-INSTRUCTION: Convention plugins live here so no module file hardcodes a version or a
     compiler flag — dependency versions belong in gradle/libs.versions.toml. Two traps are recorded
     below and both have cost real debugging time: JaCoCo inside a convention plugin breaks generated
     accessors, and the Nodera project modules MUST be joined to the mod definition or FML's module
     classloader hides them at dev-run time. Update this file when a convention or a run
     configuration changes. -->

**The Gradle convention plugins.** Every Java module applies one of these instead of repeating
compiler settings, toolchain pins, and test configuration.

- **Docs:** [`docs/minecraft/Task.1.md`](../../docs/minecraft/Task.1.md) ·
  [`docs/README.md`](../../docs/README.md) §4

---

## Architecture

```
java/build-logic/src/main/kotlin/
├── nodera.java-library.gradle.kts    every Minecraft-free module: Java 21 toolchain,
│                                     --release 21, test configuration, common repositories
└── nodera.neoforge-mod.gradle.kts    the mod: ModDevGradle, the NeoForge pin, the mod
                                      definition, and the `runs { }` dev-run block
```

Dependency versions live in `gradle/libs.versions.toml` (the version catalog). No module build file
hardcodes a version.

## Why it is shaped this way

**Conventions, not copies.** Seven modules sharing one plugin means the toolchain, the release target,
and the test setup are decided once. A change to how tests run is one edit, not seven.

**`--release 21` with a newer host JDK.** The host JDK is newer than the target, and compiling with
`--release 21` produces genuine Java 21 bytecode. This is not cosmetic: ArchUnit's bundled ASM could
not parse newer class files, which silently *disabled* the determinism ban — the single most important
mechanical guard in the project. Pinning the release target re-enabled it.

**The dev-run block is a first-class deliverable.** `runClient`, `runServer`, and the scripted
quick-play variants are what make live acceptance possible at all; the scripted suites that drive real
clients depend entirely on them.

## Traps recorded here because they cost real time

**JaCoCo inside a convention plugin breaks generated accessors.** Adding the JaCoCo plugin inside the
convention plugin makes Gradle's Kotlin-DSL generated accessors unavailable, producing compile errors
that look unrelated to coverage. Apply it at the consuming module instead.

**Project modules must join the mod definition.** FML's module classloader hides `dev.nodera.*` from
the mod at dev-run time unless the Nodera project modules are joined to the `nodera` mod definition.
The symptom is a mod that assembles perfectly and then cannot see its own classes when launched.

**Runtime libraries must match Minecraft's strict pins.** Third-party libraries the peer needs go on
the additional runtime classpath, and shared libraries (fastutil, slf4j) must match the versions
Minecraft pins — with a simple logging binding excluded from the mod runtime, or logging initialises
twice.

**The IDE's bundled Gradle can fight the CLI.** A different Gradle version driven by an IDE extension
can corrupt the Kotlin-DSL accessors this module generates. If accessors start failing
inexplicably, stop stray daemons and purge before assuming a code problem.

## Tests

The plugins are exercised by every module build; there are no separate unit tests. The meaningful
check is that both gates stay green:

```bash
./gradlew check
cd rust && cargo test
```
