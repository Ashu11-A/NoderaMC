# 02 — Build System & Project Layout

MultiPaper is a **[paperweight](https://github.com/PaperMC/paperweight) patcher
project** layered on top of [Purpur](https://github.com/PurpurMC/Purpur). The
repository you see does **not** contain the patched `MultiPaper-API/` or
`MultiPaper-Server/` source trees — those are generated on demand by Gradle
from the Purpur source plus the patches in `patches/`. Only the
**`MultiPaper-Master`** and **`MultiPaper-MasterMessagingProtocol`** modules
are real, hand-written Gradle subprojects.

This file describes the directory layout, the paperweight flow, the dev
workflow, and the dependencies of each module.

---

## Top-level layout

```
MultiPaper/
├── build.gradle.kts                    # Root build: paperweight patcher config
├── settings.gradle.kts                 # Declares 4 subprojects
├── gradle.properties                   # Versions, Purpur ref, MC version
├── gradle/                             # Gradle wrapper
├── gradlew, gradlew.bat                # Wrapper scripts
├── build-data/
│   └── dev-imports.txt                 # paperweight dev-bundle helper
│
├── patches/                            # The fork proper
│   ├── api/                            #   9 .patch files for the Bukkit API
│   ├── server/                         #   150 .patch files for NMS/CraftBukkit
│   ├── removed/                        #   47 historical patches, no longer applied
│   └── todo/                           #   3 work-in-progress patches, not applied
│
├── MultiPaper-Master/                  # REAL Gradle subproject (MIT)
│   ├── build.gradle.kts                #   Netty + json + snakeyaml + jo-nbt,
│   │                                   #     compileOnly bungeecord/velocity API
│   └── src/main/java/puregero/multipaper/server/
│       ├── MultiPaperServer.java       #   entry point
│       ├── ServerConnection.java       #   per-server connection + dispatcher
│       ├── ChunkSubscriptionManager.java
│       ├── EntitiesSubscriptionManager.java
│       ├── ChunkLockManager.java
│       ├── EntitiesLockManager.java
│       ├── Player.java
│       ├── CircularTimer.java
│       ├── CommandLineInput.java
│       ├── FileLocker.java
│       ├── handlers/                   #   38 message-handler classes
│       ├── bungee/MultiPaperBungee.java
│       ├── velocity/MultiPaperVelocity.java
│       ├── proxy/                      #   built-in NIO proxy
│       └── util/                       #   RegionFile, RegionFileCache, locks
│
└── MultiPaper-MasterMessagingProtocol/ # REAL Gradle subproject
    ├── build.gradle.kts                #   compileOnly netty-all 4.1.87
    └── src/main/java/puregero/multipaper/mastermessagingprotocol/
        ├── MessageBootstrap.java       #   Netty bootstrap (epoll/nio)
        ├── MessageEncoder.java
        ├── MessageDecoder.java
        ├── MessageLengthEncoder.java   #   VarInt length framing
        ├── MessageLengthDecoder.java
        ├── ExtendedByteBuf.java        #   VarInt/UUID/ChunkKey helpers
        ├── ChunkKey.java               #   (world,x,z) hash key
        ├── messages/                   #   Message, Protocol, MessageHandler
        │   ├── masterbound/            #   42 server→master messages
        │   └── serverbound/            #   23 master→server messages
        └── datastream/                 #   chunked streaming for big payloads
```

---

## `gradle.properties`

```properties
group       = puregero.multipaper
version     = 1.20.1-R0.1-SNAPSHOT
mcVersion   = 1.20.1
purpurRef   = f6fd5f6...                 # git ref into PurpurMC/Purpur
masterVersion = 2.12.3                   # version of Master + protocol lib
```

`mcVersion` and `purpurRef` together pin the exact upstream Purpur commit that
the fork is based on. `masterVersion` is used as the version of the
`MultiPaper-Master` jar and the `...-MessagingProtocol` library.

---

## Root `build.gradle.kts` — paperweight patcher

```kotlin
plugins {
    `java`
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.papermc.paperweight.patcher") version "1.5.7-SNAPSHOT"
}
```

The interesting block is:

```kotlin
paperweight {
    serverProject = project(":multipaper-server")

    useStandardUpstream("Purpur") {
        url = github("PurpurMC", "Purpur")
        ref = providers.gradleProperty("purpurRef")

        withStandardPatcher {
            apiPatchDir    = layout.projectDirectory.dir("patches/api")
            serverPatchDir = layout.projectDirectory.dir("patches/server")

            apiOutputDir    = layout.projectDirectory.dir("MultiPaper-API")
            serverOutputDir = layout.projectDirectory.dir("MultiPaper-Server")
        }
    }
}
```

So paperweight:

1. Clones Purpur at `purpurRef`.
2. Applies `patches/api/*` to Purpur-API → produces `MultiPaper-API/`.
3. Applies `patches/server/*` to Purpur-Server → produces `MultiPaper-Server/`.
4. Wires `MultiPaper-API` and `MultiPaper-Server` as Gradle subprojects (named
   `multipaper-api` and `multipaper-server`, see `settings.gradle.kts`).

There is also a custom task `purpurRefLatest` that queries the GitHub API and
bumps `purpurRef` in `gradle.properties` to the latest Purpur commit
(`build.gradle.kts:80-109`).

### Java toolchain

The build pins **JDK 17**:

```kotlin
java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}
```

JDK 17 is the minimum required to compile and run MultiPaper.

---

## `settings.gradle.kts`

```kotlin
rootProject.name = "MultiPaper"

include(":multipaper-mastermessagingprotocol")
project(":multipaper-mastermessagingprotocol").projectDir = file("MultiPaper-MasterMessagingProtocol")

include(":multipaper-api")
project(":multipaper-api").projectDir = file("MultiPaper-API")

include(":multipaper-server")
project(":multipaper-server").projectDir = file("MultiPaper-Server")

include(":multipaper-master")
project(":multipaper-master").projectDir = file("MultiPaper-Master")
```

The two real subprojects (`MultiPaper-Master*`) are always present. The two
patched subprojects (`MultiPaper-API`, `MultiPaper-Server`) **must be
generated** by `applyPatches` before they exist on disk.

---

## Submodule dependencies

```
        ┌────────────────────────────┐
        │ MultiPaper-Master          │
        │  (master server, MIT)      │
        └─────────────┬──────────────┘
                      │ depends on
                      ▼
        ┌────────────────────────────┐        ┌──────────────────────────┐
        │ MultiPaper-MasterMessaging │        │ Purpur (paperweight)     │
        │ Protocol                   │◄───────┤   │                      │
        │ (Netty wire protocol lib)  │ depends│   ▼                      │
        └────────────────────────────┘        │ MultiPaper-API          │
                                              │   (patches/api on       │
                                              │    Purpur-API)          │
                                              │   ▼                      │
                                              │ MultiPaper-Server       │
                                              │   (patches/server on    │
                                              │    Purpur-Server)       │
                                              └──────────────────────────┘
```

Both the Master and the patched server depend on the **same** messaging
protocol library — that's how both ends share the message class definitions.

---

## `MultiPaper-MasterMessagingProtocol/build.gradle.kts`

Plain Java library, version `"${masterVersion}-${mcVersion}"` (=
`2.12.3-1.20.1`):

```kotlin
dependencies {
    compileOnly("io.netty:netty-all:4.1.87.Final")
}
```

`compileOnly` because the master jar (which `shadow`s Netty) re-exports it; on
the server side Netty already comes bundled with Minecraft.

---

## `MultiPaper-Master/build.gradle.kts`

```kotlin
dependencies {
    implementation(project(":multipaper-mastermessagingprotocol"))
    implementation("org.json:json:20230227")
    implementation("org.yaml:snakeyaml:1.33")
    implementation("io.netty:netty-all:4.1.87.Final")
    implementation("com.github.steveice10:jo-nbt:1.3.0")

    compileOnly("net.md-5:bungeecord-api:1.20-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.1.1")
}
```

BungeeCord and Velocity APIs are `compileOnly` so that the **same jar** can be
dropped as either a standalone process, a BungeeCord plugin, or a Velocity
plugin. The `shadow` plugin relocates Netty, snakeyaml, and NBT into
`puregero.multipaper.master.libs.*` to avoid classpath conflicts when running
inside a proxy.

```kotlin
tasks.shadowJar {
    relocate("io.netty",          "puregero.multipaper.master.libs.io.netty")
    relocate("org.yaml.snakeyaml","puregero.multipaper.master.libs.org.yaml.snakeyaml")
    relocate("com.github.steveice10.nbt", "puregero.multipaper.master.libs.nbt")
}

manifest { attributes("Main-Class" to "puregero.multipaper.server.MultiPaperServer") }
```

---

## The patch files

Patches are standard `git format-patch` (mbox) output. Each file is a single
commit; subject line is the patch title; hunks follow.

### `patches/api/` — 9 patches

Add new Bukkit API surface: `MultiPaperNotificationManager`,
`MultiPaperDataStorage`, and new methods on `Bukkit`, `Server`, `World`,
`Chunk`, `Block`, `Entity`, `Player`, `Location` (see
[13 — Plugin Development](./13-plugin-development.md)).

### `patches/server/` — 150 patches

The actual fork implementation. Applied in numerical order; later patches
build on files created by earlier ones. Key ones:

| # | Patch | Topic |
|---|---|---|
| 0001–0006 | housekeeping | Decompile fixes, build wiring, `multipaper.yml` config. |
| 0007 | Add MultiPaperConnection | Connects to master on startup. |
| 0009 | Add peer-to-peer connection | Hijacks the Minecraft handshake for server-to-server channels. |
| 0012–0014 | ExternalPlayer | Phantom players; packet tunnelling. |
| 0016 | Add chunk syncing | **The biggest patch (~2500 lines).** Chunk IO redirection, ownership, subscriptions, world border. |
| 0024 | Inventory sync | Cross-server container/inventory/teleport. |
| 0027 | Sync entities | Entity ticking decisions, controlling passengers. |
| 0032 | Redstone safety | `MultiPaperExternalBlocksHandler`, atomic group takeover. |
| 0088 | File syncing | `MultiPaperFileSyncer` + watcher. |
| 0092 | Event-based chunk IO | Async event-loop IO replacing Paper's `RegionFileIOThread`. |
| 0127, 0141 | Entity ID sync | Stable IDs across the cluster. |
| 0130 | Pistons | Cross-server piston move notifications. |
| 0013, 0017, 0056, 0068 | Commands | `/servers`, `/mpdebug`, `/mpmap`, `/slist`. |
| 0134, 0139, 0144 | Dupe fixes | Anti-exploit patches. |

### `patches/removed/` — 47 historical patches

Old Airplane/Pufferfish optimisations (e.g. `Airplane-Profiler`,
`Dynamic-Activation-of-Brain`, `Entity-TTL`, multithreaded region IO) that
MultiPaper has dropped. Kept for reference; **not applied**.

### `patches/todo/` — 3 WIP patches

`0130-Sync-game-events`, `0150-Add-a-timeout-when-saving-chunks`,
`0178-Handle-multi-server-chunk-generation`. **Not applied** in this version.

---

## Build & dev workflow

From the README:

```bash
# 1. Materialise the patched source trees:
./gradlew applyPatches

# 2. Build the runnable jars:
./gradlew shadowJar createReobfPaperclipJar
```

Artifacts:

- `build/libs/multipaper.jar` — the runnable Purpur fork (run with `java -jar`).
- `MultiPaper-Master/build/libs/multipaper-master.jar` — the master.

### Publishing to local Maven

```bash
./gradlew publishToMavenLocal
```

Publishes the MultiPaper-API artifact as `com.github.puregero:multipaper-api:1.20.1-R0.1-SNAPSHOT`
— the coordinates used by plugin developers (see
[13 — Plugin Development](./13-plugin-development.md)).

### Editing the patched source

After `applyPatches`, you can edit files directly in `MultiPaper-API/` or
`MultiPaper-Server/`. To turn those edits back into patches you would run
`./gradlew rebuildPatches` (paperweight's standard task). The
`MultiPaper-Master` and `MultiPaper-MasterMessagingProtocol` modules are plain
Gradle projects and are edited in place.

### macOS note

The macOS-bundled `diff` is incompatible with paperweight. Run:

```bash
brew install diffutils
```

…then reopen the terminal. Verify with `diff --version` — it must **not** say
`Apple diff (based on FreeBSD diff)`.

---

## Continuous integration

`.github/workflows/build.yml` builds the project on push/PR.
`.github/workflows/upstream.yml` periodically bumps the Purpur ref and opens a
PR.

---

## Next

Continue to [03 — The MultiPaper-Master Module](./03-master-server.md).
