# Task 1 — Build Scaffolding + NeoForge Mod Skeleton

**Phase:** 0 · **Depends on:** — · **Modules:** `build-logic`, root build, `neoforge-mod`

## Goal

A building, CI-green multi-module Gradle workspace with an installable NeoForge mod that
loads on both a dedicated server and a client, with correctly split entrypoints and empty
(but registered) networking/config/attachment scaffolding. No gameplay behaviour yet.

---

## Folder structure to create

```
nodera/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build-logic/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── nodera.java-library.gradle.kts      # plain-Java module conventions
│       └── nodera.neoforge-mod.gradle.kts      # NeoForge module conventions
├── core/                    build.gradle.kts + src/main/java, src/test/java   (empty pkg)
├── protocol/                (same skeleton)
├── simulation/              (same skeleton)
├── consensus/               (same skeleton)
├── transport-api/           (same skeleton)
├── transport-neoforge/      (same skeleton, applies neoforge convention)
├── storage-api/             (same skeleton)
├── testkit/                 (same skeleton)
├── neoforge-mod/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/dev/nodera/mod/
│       │   ├── NoderaMod.java
│       │   ├── common/
│       │   │   ├── NoderaConfig.java
│       │   │   ├── ModNetworking.java
│       │   │   └── ModAttachments.java
│       │   ├── dedicated/
│       │   │   └── ServerBootstrap.java
│       │   └── client/
│       │       ├── NoderaClientMod.java
│       │       └── ClientBootstrap.java
│       └── resources/
│           ├── META-INF/neoforge.mods.toml
│           └── nodera.mixins.json               # registered, empty mixin list
└── .github/workflows/build.yml
```

`storage-rocksdb`, `storage-client`, `peer-runtime`, `transport-rendezvous` (Task 29 —
replaced the planned `transport-libp2p`, see `LEGACY.md`), `integration-tests` are **not**
created yet (Tasks 8–10/29) — keep `settings.gradle.kts` comments marking where they will
be included.

## File-by-file

- **`settings.gradle.kts`** — `rootProject.name = "nodera"`, `includeBuild("build-logic")`,
  `include(...)` for every module above; enable version catalog + typesafe accessors.
- **`gradle/libs.versions.toml`** — pins: neoforge, junit5, assertj, jqwik, mockito,
  caffeine, zstd-jni, roaringbitmap, fastutil, archunit, jmh. Single source of versions
  (Task 0 §3).
- **`build-logic/…/nodera.java-library.gradle.kts`** — `java-library`, Java 21 toolchain,
  `options.release = 21`, UTF-8, JUnit platform, AssertJ + jqwik on `testImplementation`,
  reproducible archives, `-parameters` compiler flag (record component names for codecs).
- **`build-logic/…/nodera.neoforge-mod.gradle.kts`** — the java-library conventions +
  NeoGradle/ModDevGradle plugin application, `runs` for `client`, `server`, `data`; mixin
  config wiring; mod id constant injection.
- **`neoforge-mod/build.gradle.kts`** — applies the neoforge convention; depends on
  `core`, `protocol`, `simulation`, `consensus`, `transport-api`, `transport-neoforge`,
  `storage-api`; `jarJar`/shade rules reserved (empty for now).
- **`neoforge.mods.toml`** — modid `nodera`, loader version range, license, dependency on
  neoforge/minecraft ranges from `gradle.properties`, points at `nodera.mixins.json`.
- **`.github/workflows/build.yml`** — JDK 21 (Temurin), `./gradlew check build`
  on push + PR; cache Gradle; upload the mod jar artifact.

## Class relationships

```
@Mod("nodera")                          @Mod(value="nodera", dist=Dist.CLIENT)
NoderaMod (both dists)                  NoderaClientMod (client only)
 ├─ registers ModNetworking (mod bus)    └─ ClientBootstrap.register(modBus, container)
 ├─ registers ModAttachments (mod bus)
 ├─ registers NoderaConfig (both specs)
 └─ if dist == DEDICATED_SERVER → ServerBootstrap.register()
```

Skeleton code (matches the pattern locked in `Context/Readme.md`):

```java
@Mod(NoderaMod.MOD_ID)
public final class NoderaMod {
    public static final String MOD_ID = "nodera";

    public NoderaMod(IEventBus modBus, ModContainer container, Dist dist) {
        NoderaConfig.register(container);
        ModNetworking.register(modBus);   // subscribes RegisterPayloadHandlersEvent; empty registrar for now
        ModAttachments.register(modBus);  // DeferredRegister<AttachmentType<?>>; empty for now
        if (dist == Dist.DEDICATED_SERVER) {
            ServerBootstrap.register();   // NeoForge.EVENT_BUS game-event subscriptions; empty for now
        }
    }
}

@Mod(value = NoderaMod.MOD_ID, dist = Dist.CLIENT)
public final class NoderaClientMod {
    public NoderaClientMod(IEventBus modBus, ModContainer container) {
        ClientBootstrap.register(modBus, container);   // empty for now
    }
}
```

`ServerBootstrap` / `ClientBootstrap`: static `register()` holders so later tasks add
listeners without touching entrypoints. `NoderaConfig`: two `ModConfigSpec`s (SERVER,
CLIENT) exposing the Task 0 §5 constants as config values (unused until Task 5+).

## Implementation details — NeoForge mod

- Verify **dist isolation**: run `runServer` — the dedicated server must boot to "Done"
  without classloading anything under `dev.nodera.mod.client`. Add a smoke assertion:
  `ServerBootstrap` logs `Nodera server bootstrap (dist=DEDICATED_SERVER)`.
- `nodera.mixins.json` registered with an empty `"mixins": []` — proves the mixin
  pipeline works before Task 5 adds real mixins (`"required": true`,
  `"minVersion": "0.8"`, package `dev.nodera.mod.mixin`).
- `ModNetworking.register` subscribes `RegisterPayloadHandlersEvent` and creates
  `event.registrar("1")` — registrar exists, zero payloads. Task 4 fills it.

## Implementation details — server peer

None yet (server peer = the dedicated-server role of the same jar). Only requirement:
identical jar runs in both `runClient` and `runServer` dev runs.

## Acceptance criteria

1. `./gradlew check build` green locally and in CI; produces `neoforge-mod` jar.
2. `runServer` boots to console prompt; `runClient` reaches title screen; both log the
   Nodera bootstrap lines; no dist-crossing classload errors.
3. All empty modules compile and run an example unit test (one trivial test per module to
   prove the test wiring).
4. Version catalog is the only place versions appear (`git grep` for version literals in
   module build files returns nothing).
