# Worker Task 8 — One world, one identity

<!-- AI-AGENT-INSTRUCTION: The rule this task exists to preserve: a save that has ever been named on
     the network keeps that name for life. Any change that lets a world id be re-derived when a
     persisted id already exists re-opens the duplication this task closes, and must be refused.
     Second rule: a world id is normalised (trim + lowercase) at exactly one place before it is used
     as a key. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS — every deliverable is closed; the task stays open only while its
world-continuity rows (W-FETCH-1/2, W-REPL-1/4) wait on a live run
**Category:** worker · **Owns:** W-DUP-1…4, W-FETCH-1, W-REPL-1 (W-REPL-2 and W-REPL-3 retired
2026-07-28; W-DUP-1/2/4 the same day; **W-DUP-3 retired 2026-07-29**) · **Last audit:** 2026-07-29
**Depends on:** [worker 3](Task.3.md), [minecraft 6](../minecraft/Task.6.md)
**Consumed by:** [app 10](../app/Task.10.md), [minecraft 10](../minecraft/Task.10.md)

---

## Goal

The same Minecraft save can be shared, closed, re-shared, opened to LAN, re-opened on another port,
restarted and rehosted, and the Nodera network holds **exactly one** entry for it throughout. A
player cannot produce a second copy of their own world by ordinary use of the software, and a world
whose identity cannot be recorded says so loudly instead of silently forking on the next launch.

## Status detail

Opened 2026-07-27 after an audit found **ten registry rows for four saves** on the author's own node
— `~/.nodera/worlds.dat` held `world` four times under four different ids, three of them minted
within eleven minutes, each with its own `.worldkey`. Five distinct mechanisms were found; three are
fixed in this task's first pass, two remain.

Landed so far:

- `WorldIdentity.createPinned` + the optional `pinnedWorldIdHex` argument on `NODERA-WORLDID`, so a
  re-share **signs the id the save already carries** instead of re-deriving it.
- `NoderaHost.ensureIdentity` passes the persisted id and no longer plays the "which seed reproduces
  the stored id" guessing game; the failure to persist an identity is now logged at `error` with its
  consequence stated.
- `NoderaHost.activate` falls back to the persisted record rather than to the raw genesis seed when
  the worker is unreachable.
- `LanSessionService.sessionId` no longer mixes in the port, and a close beacon for a world that is
  open again elsewhere no longer withdraws the live session.
- `WorldHostingService` normalises every world-id map key through one `key(String)` helper.
- Worker identity, registry, key and tombstone writes share
  `storage.io.AtomicFileWriter.writeOwnerOnly`: it checks the destination directory's `FileStore`,
  creates POSIX temporary files as `0600` before content, fails closed if advertised POSIX
  permissions are rejected, and uses default creation only when the store has no POSIX view. Failed
  writes and moves remove the secret-bearing temporary file; cleanup failures are suppressed on the
  primary exception.

## Dependencies

- [worker 3](Task.3.md) owns `WorldHostingService`, the registry and the announce lane.
- [minecraft 6](../minecraft/Task.6.md) owns `NoderaHost` and the world-identity file in the save.
- No wire change on the tracker side: `genesis_hash` is already the sole identity, and the tracker
  correctly refuses to treat names as identifying (`tracker/src/registry.rs:50-52`).

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | A persisted world id is pinned, never re-derived (`WORLDID` takes `pinnedWorldIdHex`) | ✅ |
| 2 | Identity-persist failure is loud and names its consequence | ✅ |
| 3 | The worker-unreachable path uses the persisted id, not the genesis seed | ✅ |
| 4 | A LAN session id survives a re-open on a different ephemeral port | ✅ |
| 5 | One normalisation for world-id keys across `host` / `seed` / `stop` / `administers` | ✅ |
| 6 | Registry reconciliation: a row this node can no longer serve stops being announced | ✅ |
| 7 | Ownership is not lost across `stop` → `seed` (re-bind from `WorldKeyStore` at boot) | ✅ |
| 8 | Worker identity and `LocalFiles.writeAtomically` survive a non-POSIX filesystem | ✅ |
| 9 | A one-shot merge tool for registries that already hold duplicates | ✅ |
| 10 | An in-flight archive download is immune to the retention policy | ✅ |
| 11 | `HOST` gets a work-sized control timeout, so a slow worker is not read as a refusal | ✅ |

## Design

### Why derivation was the bug

`worldId = SHA256(genesisRoot ‖ authorPublicKey ‖ createdAtEpoch)`. That is a fine *first* name and a
terrible *recurring* one, because `genesisRoot` is not stable:

- `WorldGenesisService.ensure` re-certifies from whichever chunks are loaded when `nodera-genesis.dat`
  is missing or fails signature verification, producing a different root;
- `NoderaHost.activate` falls back to `SHA256("nodera.dev-world.v1:" + levelName)` when certification
  throws, so a transient failure on one launch and a success on the next give two ids — and renaming
  the world changes that fallback too;
- with no `nodera-world.dat`, `createdAtEpoch` is `System.currentTimeMillis()`, so every launch of a
  save whose identity file cannot be written mints a brand-new world.

Each of those minted a second id, and **both ids stayed announced**: `restoreFromRegistry` re-loads
and re-announces every row forever, with no expiry and no reconciliation. Derivation is kept for the
one moment it is correct — a world that has never been named — and pinning takes over immediately
after.

Pinning is safe against theft because authorship is carried by the signature, not by the derivation:
a peer that is not the author cannot produce a record any other peer will accept, so being able to
name an id is not being able to claim it.

### Why the LAN lane duplicated on its own

`sessionId = SHA256(nodeId ‖ motd ‖ port)`, and vanilla's Open-to-LAN allocates a **random ephemeral
port every time**. The code comment asserted the id was "deterministic so the same open world keeps
one identity across a worker restart"; it was deterministic in the port, which is the one input that
does not survive. One world opened on five evenings was five worlds on the network. The MOTD carries
the player and the level name, which is what actually identifies the session, so the port is dropped.

Dropping it makes two live sessions able to share an id (a world re-opened before the old beacon
expires), so `onClosed` now withdraws only when no other session carries the same id.

### Why the archive-fetch fixes live in this task

Deliverables 10 and 11 are not about identity, but they are about the same thing going wrong: a rule
that is right in isolation applied to a case it was not written for. `supersedeOlderVersions` is a
correct security rule — a superseded ciphertext must stop being served — and it ran against a version
a joiner was in the middle of downloading, destroying the transfer. `CompanionClient`'s 1.5 s budget
is a correct *probe* timeout and was applied to `HOST`, a verb that announces to every tracker; the
mod then logged a refusal for a call that had succeeded.

Both were found in the same live run, both are worker-owned, and both produce the symptom this task
already exists to remove: a world the network cannot give you a clean single copy of.

### What is deliberately *not* done

Deduplicating by **name** anywhere. Names decorate a world; two players may legitimately share
"New World", and a tracker that merged them would merge two strangers' saves. Identity stays
cryptographic.

## Files

| Path | Role |
|---|---|
| `library/java/storage/src/main/java/dev/nodera/storage/WorldIdentity.java` | `createPinned` |
| `peer/src/main/java/dev/nodera/peer/control/ControlProtocol.java` | `NODERA-WORLDID` grammar |
| `peer/src/main/java/dev/nodera/peer/control/ControlServer.java` | optional 8th argument |
| `peer/src/main/java/dev/nodera/peer/control/ControlHandler.java` | verb signature |
| `peer/src/main/java/dev/nodera/headless/WorkerControlHandler.java` | pin honoured on mint |
| `peer/src/main/java/dev/nodera/headless/WorldHostingService.java` | key normalisation |
| `peer/src/main/java/dev/nodera/headless/LanSessionService.java` | port-free session id |
| `library/java/storage/src/main/java/dev/nodera/storage/io/AtomicFileWriter.java` | shared owner-only creation, atomic replacement and failure cleanup |
| `peer/src/main/java/dev/nodera/headless/LocalFiles.java` | worker-state wrapper over the shared writer |
| `peer/src/main/java/dev/nodera/peer/discovery/PersistentIdentityStore.java` | worker identity wrapper over the shared writer |
| `peer/src/main/java/dev/nodera/headless/HeadlessPeerMain.java` | production `openLocalState` startup seam |
| `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaHost.java` | pin sent, persisted-id fallback |
| `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/CompanionClient.java` | pin on the wire |

## Testing

| Test | State | Proves |
|---|---|---|
| `WorldIdentityTest#aPinnedIdentityKeepsItsIdWhenTheGenesisRootMoves` | ✅ green | deliverable 1 |
| `WorldIdentityTest#pinningDoesNotLetOneNodeSignAnotherNodesWorld` | ✅ green | pinning is not a claiming primitive |
| `LanSessionServiceTest#thePortIsNotTheIdentity` ("re-opening a world on a new port is the same session") | ✅ green | deliverable 4 |
| `LanSessionServiceTest#aSharedWorldFollowsItsPort` | ✅ green | deliverable 4 — the close beacon does not withdraw a world open elsewhere, and the route follows |
| `WorldHostingServiceTest#aPaddedUpperCaseWorldIdCanStillBeStopped` | ✅ green | deliverable 5 |
| `WorldHostingServiceTest#oneWorldIsOneEntryHoweverItIsSpelled` | ✅ green | deliverable 5 |
| `HeadlessPeerMainStateTest#startupStateSurvivesANonPosixFileSystem` | ✅ green (closest production seam) | deliverable 8 mechanism — `HeadlessPeerMain.openLocalState` runs on zipfs with no POSIX `FileStore` view; identity reload and registry replacement succeed |
| A launched `nodera-headless` on a FAT32 loop mount: boot → `NODERA-JOIN` → kill → boot | ✅ run 2026-07-29 | deliverable 8's exit — same node id and the pre-restart `worlds.dat` row recovered; a second pass wrote a `.worldkey` there too. Recorded in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md); not automatable in CI (needs a mount) |
| `AtomicFileWriterTest` (3) | ✅ green | owner-only POSIX result, failed-move temp deletion, cleanup error suppression |
| `FetchSurvivesSupersessionTest#aNewerVersionLearnedMidFetchDoesNotEvictTheOneBeingDownloaded` | ✅ green (verified failing without the guard) | deliverable 10 |
| `FetchSurvivesSupersessionTest#aVersionNobodyIsFetchingIsStillSuperseded` | ✅ green | deliverable 10 keeps L-55 intact |
| A control-socket round trip of `NODERA-WORLDID` with and without the 8th argument | ⬜ not written | the verb stayed backward compatible |
| A live re-share with `nodera-genesis.dat` deleted | ⬜ not written | deliverable 1, end to end |

Gate after this pass: **2,059 Java** (`:worker` 173, `:storage` 157, all green). Rust workspaces were
unaffected and not re-run for this Java-only fix.

## Acceptance criteria

- [ ] Sharing a world, closing it and re-sharing it produces one tracker entry, proven by
      `worlds.dat` holding one row and the tracker catalog one entry.
- [ ] Deleting `nodera-genesis.dat` and re-sharing does **not** change the world id.
- [ ] Open-to-LAN twice in one session produces one network entry.
- [ ] A save directory made read-only produces an `error` log naming the consequence, and no second
      id.
- [ ] Deliverables 6–9 closed or explicitly deferred with an owner.

## Limitations

| Id | Statement | Exit test |
|---|---|---|
| W-DUP-1 | Registry rows are immortal: nothing reconciles a row against the world still existing | deliverable 6 |
| W-DUP-2 | `stop` → `seed` loses the ownership record, so an owned world reads as merely supported | deliverable 7 |
| ~~W-DUP-3~~ | **RETIRED 2026-07-29.** The launched distribution was driven through boot, a registry write and a restart on a FAT32 mount | Was: launch the worker distribution with identity and registry on a non-POSIX filesystem, write the registry, restart, recover both records — [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) holds the run |
| W-DUP-4 | Registries that already contain duplicates are not repaired by the fix, only stopped from growing | deliverable 9 |
| W-FETCH-1 | An archive download could be evicted mid-transfer by the retention policy (fixed; live re-run is the exit) | deliverable 10 |
| W-REPL-1 | A world this node claimed but held nothing of was never repaired (fixed; live 0%→100% is the exit) | [`LIMITATIONS.md`](LIMITATIONS.md) |

**Retired on 2026-07-28** (headless exit tests confirmed green): **W-REPL-2** (supersede-eviction
destroyed the only servable plaintext copy) and **W-REPL-3** (peers offered manifests for content
they held nothing of) — moved to [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

**W-DUP-3 retired on 2026-07-29.** What had been missing was never the code — it was that zipfs is an
in-process provider `NODERA_STATE_DIR` cannot name, so no *launched* worker had ever written its
identity and registry to a filesystem without POSIX attributes. A FAT32 image on a loop mount can be
named, and the built distribution was run against one: fresh identity, `NODERA-JOIN` registry write,
kill, re-launch, same node id and the same row. `NODERA-WORLDID` on a second pass put a world's
private key there as well, so all three secret-bearing writers are covered. Evidence in
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

With that, **all eleven deliverables are closed.** The task remains IN PROGRESS because the four
world-continuity rows it also owns (W-FETCH-1, W-FETCH-2, W-REPL-1, W-REPL-4) are RETIRING on live
clauses — two machines, one world, one host playing — and no amount of further code closes those.
