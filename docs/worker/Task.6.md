# Worker Task 6 — World Ownership and the Durable World Registry

<!-- AI-AGENT-INSTRUCTION: Two invariants here, and both are load-bearing. (1) A world key is ONLY
     ever generated locally, in `mintWorldIdentity` — there is no code path that mints one for a
     world id that arrived from the network, and adding one would let a peer be talked into claiming
     somebody else's world. (2) The FIRST verifying ownership claim for a world wins; a later,
     different one is refused, never preferred for being newer. Liveness (mcRoute, players) must
     never be restored from the registry — it describes a running game. Keep this header accurate. -->

**Status:** ✅ COMPLETED
**Category:** worker · **Owns:** — · **Last audit:** 2026-07-26
**Depends on:** [worker 2](Task.2.md), [worker 3](Task.3.md), [network 3](../network/Task.3.md)
**Consumed by:** [app 2](../app/Task.2.md), [minecraft 4](../minecraft/Task.4.md)

---

## Goal

Two things the always-on peer could not do: remember what it was keeping on the network across its own
restart, and say — provably — which of those worlds it administers.

## Status detail

Complete and green. The persistence half is `WorldRegistryStore` + `WorldHostingService` restore; the
ownership half is a per-world Ed25519 key pair, a doubly-signed `WorldOwnership` claim, a
challenge-response `WorldAdminProof`, and the gossip lane that moves claims to the peers serving a
world.

Verified live against the built worker distribution on 2026-07-26: host a world, `kill` the worker,
start it again with the same identity — the world is still listed, still administered, its
`world_public_key` unchanged, and its game endpoint correctly empty.

## The defect this closes

`WorldHostingService` held its world set in a `ConcurrentHashMap` and nothing else. Every worker
restart — a crash, a settings change, the companion app respawning its supervised worker, a reboot —
dropped **every** world the node hosted or seeded. The node stopped announcing them, stopped
advertising the pieces still sitting in its own content store, and answered `NODERA-STATE` with
`"connected_worlds": []`.

Reproduced before the fix, with nothing but the shipped launcher:

```
$ printf 'NODERA-HOST 2 aabbccdd11223344 <nameB64> {"mc":"127.0.0.1:25599","players":2}\n' | nc 127.0.0.1 25610
NODERA-OK
$ kill <worker>; <restart with the same identity and archive dir>
$ printf 'NODERA-STATE 2\n' | nc 127.0.0.1 25610 | jq .connected_worlds
[]
```

This is what "the app cannot retrieve data from the worker" actually was. The app was reading the
worker faithfully; the worker had forgotten. An always-on peer that cannot survive its own restart is
not one.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `WorldRegistry` on disk (`~/.nodera/worlds.dat`), written through on every change | ✅ |
| 2 | `WorldHostingService` restores and re-announces its worlds at construction | ✅ |
| 3 | Per-world Ed25519 key pair (`PersistedWorldKey`), owner-only, one file per world | ✅ |
| 4 | `WorldOwnership` — the world key bound to its creating node, signed by both | ✅ |
| 5 | `WorldAdminProof` — a verifier's challenge signed by the world's private key | ✅ |
| 6 | `NODERA-WORLDS` and `NODERA-PROVE` control verbs | ✅ |
| 7 | `owned` + `world_public_key` in the `NODERA-STATE` world rows | ✅ |
| 8 | `WorldOwnershipGossip` (tag 62) — claims reach every peer serving the world | ✅ |

## Design

**A world's key is not its author's node key.** Before this, "who administers this world" was answered
by the creating *node's* identity, because `WorldIdentity` is signed with it. That conflates two
different facts: a node key says which machine this is and is used in every transport handshake; a
world key says who runs this world. Separating them means a peer can prove authority over one world —
and only that world — with a key that exists on exactly one machine and is used for nothing else.

**Authorship is a derivation, not a decision.** The world key is minted inside `mintWorldIdentity`,
because `worldId = SHA-256(genesisRoot ‖ authorPublicKey ‖ createdAt)` binds the minting node's key
into the id itself. No other node can derive that id, so that call is *by construction* the moment of
authorship, and there is deliberately no separate "claim this world" verb for a peer to be talked into
calling.

**Two signatures, not one.** `WorldOwnership` carries a signature from the world key *and* one from
the owner's node key, both over bytes naming the world, both public keys and the owner. One alone
proves the wrong thing: the world signature alone shows somebody holds the key without saying who; the
node signature alone is just an assertion. Neither can be lifted onto a different world, key or owner.

**First verifying claim wins.** A world has one administrative key. A second, internally-valid claim
for the same world means somebody other than the author minted one — so it is refused and logged,
never merged and never preferred for being newer. Any other rule would let a peer take a world over by
publishing later than its owner.

**A proof needs the verifier's nonce.** The ownership record is public and replayable, so presenting
one proves nothing about the presenter. `WorldAdminProof` signs a verifier-chosen challenge together
with the claimant's node id: good for exactly one challenge from exactly one node. An empty challenge
is refused rather than signed, because it would make every proof for a world byte-identical.

**Ownership is orthogonal to what the node serves.** A peer can host a world it did not create (it
fetched somebody else's and re-shared it) and administer one it currently only seeds. `seeding` says
what this node does with a world; `owned` says whether it may speak for it. The companion app renders
them as two separate lists, which is only possible because they are two separate facts on the wire.

**Liveness is never restored from disk.** A world's Minecraft endpoint and player count describe a
*running game*. Restoring them would advertise a world as joinable when the thing that made it
joinable is gone. A restored world reads as "shared, game closed", which is what it is.

**Write-through, not write-behind.** The world set changes when a player shares or stops sharing and
when the replication lane adopts a world — a handful of events per session, each of which is exactly
the one a crash must not lose.

**A key file is never overwritten to recover from it.** An unreadable key file makes the world read as
unadministered and is left in place; minting over it would destroy the only copy of an administrator's
authority. A corrupt *registry*, by contrast, starts empty and is also left in place — a node that
refuses to boot serves nothing, and one that boots having forgotten still serves what a game re-shares.

## Files

- `java/storage/src/main/java/dev/nodera/storage/{PersistedWorldKey,WorldOwnership,WorldAdminProof,WorldRegistry}.java`
- `java/peer/src/main/java/dev/nodera/headless/{WorldKeyStore,WorldRegistryStore,WorldOwnershipService,LocalFiles}.java`
- `java/peer/src/main/java/dev/nodera/headless/WorldHostingService.java` (restore + write-through + `bindOwnership`)
- `java/peer/src/main/java/dev/nodera/headless/WorkerControlHandler.java` (`mintOwnership`, `worldsJson`, `proveAdmin`)
- `java/peer/src/main/java/dev/nodera/peer/control/{ControlProtocol,ControlHandler,ControlServer}.java`
- `java/transport/src/main/java/dev/nodera/protocol/membership/WorldOwnershipGossip.java` (tag 62)
- `rust/nodera-app/src/metrics.rs`, `rust/nodera-app/ui/src/{ipc.ts,App.tsx,World.tsx}`

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `NODERA_STATE_DIR` | `~/.nodera` | Base for the registry and key directory |
| `NODERA_WORLDS_FILE` | `<state>/worlds.dat` | The persisted world registry |
| `NODERA_WORLD_KEYS_DIR` | `<state>/world-keys` | One `<worldId>.worldkey` per administered world |

## Testing

- `WorldHostingPersistenceTest` — the regression itself: a **second** hosting service over the same
  registry file finds the world, keeps its `addedAt`, does **not** restore liveness, honours a stop,
  and keeps ownership. Also pins that hosting a world is not owning it.
- `WorldRegistryStoreTest` — durability, hosting-beats-seeding, ownership not erased by a re-share, a
  corrupt file starting empty rather than stopping the node, owner-only permissions.
- `WorldKeyStoreTest` — one key per world, stable across restarts, owner-only, a non-hex world id
  never becoming a path, a corrupt key file never overwritten.
- `WorldOwnershipTest` / `WorldAdminProofTest` / `PersistedWorldKeyTest` — the forgeries that must
  fail: republishing another peer's world key, lifting a signature onto a different world, replaying a
  proof, forwarding somebody else's proof, and signing an empty challenge.
- `OwnershipGossipIT` — over a real loopback mesh: every peer learns and independently verifies the
  owner; a later rival claim does not displace it; a tampered claim is refused and not relayed; an
  envelope naming a different world than its claim is dropped; the flood terminates.
- `WorldOwnershipVerbIT` — over the real control socket: minting a world identity mints its key and
  its claim, `NODERA-WORLDS` shows it, `NODERA-PROVE` produces a proof verifiable with nothing but the
  world's public key, and a world this node did not create is refused.

## Acceptance criteria

1. ✅ A hosted or supported world is still hosted or supported after the worker process restarts.
2. ✅ A restored world reports no game endpoint and no players until a live game says otherwise.
3. ✅ Creating a world makes that peer, and only that peer, able to answer `NODERA-PROVE` for it.
4. ✅ A proof verifies against the world's public key alone, and does not answer a second challenge.
5. ✅ A peer cannot acquire a world by publishing a claim after its owner.
6. ✅ The companion app can separate the worlds a player administers from the ones they support.
