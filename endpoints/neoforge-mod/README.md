# `java/neoforge-mod`

<!-- AI-AGENT-INSTRUCTION: This module WIRES; it never re-implements. Every behaviour has a green
     headless twin in engine/network — if you are writing validation, consensus, or storage logic
     here, stop and put it in the owning module. Mixins are a LAST RESORT: events first, and every
     mixin carries a "why an event was not enough" header plus a COMPATIBILITY.md note. NeoForge's
     event bus does NOT isolate listener exceptions — code reachable from server events MUST
     self-catch or it kills the integrated server. Update this file when a package is added. -->

**The mod — the playable product.** One jar, identical on client and (optional) dedicated server,
that turns the proven headless stacks into a game.

- **Depends on:** `core`, `engine`, `transport`, `storage`, `peer` — and Minecraft/NeoForge.
- **Depended on by:** players.
- **Docs:** [`docs/minecraft/`](../../docs/minecraft/Task.0.md) · normative mod-compat contract:
  [`COMPATIBILITY.md`](../../COMPATIBILITY.md)

---

## Architecture

```
dev.nodera.mod
├── NoderaMod / NoderaClientMod   both-dist and Dist.CLIENT entrypoints
├── common/    NoderaConfig, ModNetworking, ModAttachments, adapters,
│   └── entity/ NoderaPeerService, NoderaHost, NoderaWorldStore, WorldArchiver,
│              NoderaContinuity, OperatorBridge, CompanionGate/Client/Link/Protocol
├── server/    ServerBootstrap + the live adapters that consume engine/network seams
│   ├── entity/    entity capture, projection, LiveEntityLaneSession
│   └── redstone/  scheduled-tick suppression registry (Minecraft-type-free semantics)
├── client/    ClientBootstrap, ClientStallReporter
│   ├── share/       "Open to Nodera" — the pause-menu share screen
│   ├── multiplayer/ the tabbed Nodera-only screen, world list, piece map, join flow
│   ├── create/      create-world torrent toggle + password
│   ├── worldlist/   single-player public-world badge
│   ├── title/       "Nodera Network" in the Realms slot
│   └── entity/      client-side entity lane
├── debug/     Palette, renderers (tab list, boss bar, action bar), ZoneWatcher,
│   ├── render/  DiagnosticsService
│   └── command/ the declarative /nodera + /noderac tree, incl. selftest
└── mixin/     LevelChunkMixin (the single write choke point), tick suppression,
               world-list entry
```

## Why it is shaped this way

**This module wires; it never re-implements.** Every behaviour has a green Minecraft-free twin. A
capability proven headlessly and then *reimplemented* live is a capability with two behaviours and no
guarantee they agree. If a seam is missing, it is added in the owning module, headless-tested, and
consumed here.

**Events first, mixins last — and only three.** Minecraft and NeoForge version churn breaks mixins, so
the load-bearing set is minimal and each one justifies itself in its own header and in the
compatibility contract. `LevelChunkMixin` is the **only** write choke point.

**Crash containment is mandatory on game-thread paths.** NeoForge's event bus does not isolate
listener exceptions: an uncaught exception in a handler reachable from a server event kills the
integrated server. Two live crashes came from exactly this — a P2P bind failure and a per-entity lane
failure — and both are now contained and degrade instead.

**Nothing that can block runs on a thread that has to paint or tick.** A payload handler and an event
listener are frames of the client's loop and of the server's; a socket connect, a relay reservation or
a companion exchange inside one is a frozen game, not a slow one. The joiner's peer bring-up is the
worked example (MC-JOIN-2): `ModNetworking.handleSessionOnClient` keeps the thread-affine work only,
`NoderaPeerService.onServerSessionInfo` records what other code reads immediately and hands the rest
to `nodera-client-bringup`, and the one step that needs the finished runtime — the node announce —
is posted from that thread through `PacketDistributor` rather than the handler's own `reply`.
Cancelling such work goes through a generation counter, never the monitor the shutdown path also
wants, or the stall is moved rather than removed.

**Cancel vanilla only where the commit is synchronous and local.** Cancelling against an *unconfirmed*
network commit makes the player's action vanish while the network is still deciding. That rule is
pinned by a Minecraft-free gate class, so it is testable rather than merely observed in a session.

**Dist discipline is non-negotiable.** `net.minecraft.client.*` lives only under
`dev.nodera.mod.client`, guarded by `Dist.CLIENT`, with a guard test proving a dedicated server never
classloads it.

**The gate fails closed with an actionable message.** Minecraft refuses to start without the peer
worker, and says what to install — never a stack trace, never a silent no-network degrade.

## Rules

- Passwords are never serialized, never in `toString`, never sent beyond the loopback trust boundary.
  A password change is a full re-manifest, and the UI says so.
- Version pins (Minecraft 1.21.1, NeoForge 21.1.238, Java 21) change in a single dedicated commit,
  never mid-task.
- Run the live suites whenever a change touches host, join, lane, or continuity surfaces — the
  headless gate cannot see configuration-gated lifecycle paths.

## Tests

85 unit tests plus **8 scripted live suites** that drive real clients against the real service
binaries. See [`docs/minecraft/TESTING.md`](../../docs/minecraft/TESTING.md).

```bash
./gradlew :neoforge-mod:test
./gradlew :neoforge-mod:runClient      # dev run
scripts/run-tests.sh                   # the live suites (needs a GUI session)
```
