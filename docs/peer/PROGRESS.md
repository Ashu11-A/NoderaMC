# Worker — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the worker category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old note. -->

**Category:** worker · **Last audit:** 2026-07-30 · Tasks completed: **6 / 8**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Boot + presence endpoint | ✅ COMPLETED | Verified live: boots, becomes gateway, answers the probe |
| [2](Task.2.md) | Control protocol v2 + telemetry | ✅ COMPLETED | Real bytes/peers/worlds; verbs grew additively to 10+ |
| [3](Task.3.md) | Host/join delegation + seeding | 🚧 IN PROGRESS | Archive seeding + grant gossip + the announce heartbeat's live holdings + validated-lane region-piece seeding (`NODERA-SEED-REGION`, `RegionSeedSpool`) landed; L-41 retired 2026-07-26. Deliverable 9 — rendezvous registration persisting across game sessions — has the mechanism (hosting restore re-announces + re-registers every persisted world) but no live cross-session evidence yet |
| [4](Task.4.md) | Out-of-game validation | ✅ COMPLETED (headless) | L-48 retired; live region feed rides the mod |
| [5](Task.5.md) | Telemetry emitter | ✅ COMPLETED | `TelemetryVerbIT` + the e2e outage lane; **L-77 RETIRED** |
| [6](Task.6.md) | World ownership + durable registry | ✅ COMPLETED | `WorldHostingPersistenceTest`, `OwnershipGossipIT`, `WorldOwnershipVerbIT`; verified live against the built distribution |
| [7](Task.7.md) | The LAN lane — playing without a mod | ✅ COMPLETED | `TunnelServiceIT`, `LanSessionServiceTest`, `LanBeaconTest`; verified live: two workers, a real tracker, a vanilla beacon, bytes reaching the host's game |
| [8](Task.8.md) | One world, one identity | 🚧 IN PROGRESS | **All eleven deliverables closed** — the last, a launched worker on a non-POSIX filesystem, was run on 2026-07-29 and retired W-DUP-3. Open only while the four world-continuity rows it also owns (W-FETCH-1/2, W-REPL-1/4) wait on a live two-machine run |

---

## 2. Milestone notes (newest first)

### 2026-07-30 — The replication sweep gives the budget back, and the join gate stops answering guesses

**A full node stopped adopting forever.** `WorldReplicationService.sweep` only ever grew:
`withinBounds` stops adoptions once `budgetBytes` is used, and nothing ever freed a byte of it. So a
node's placement was settled once and for all by whichever worlds happened to exist when it first
filled up. Placement is not a one-time fact — it is a deterministic function of the live peer set,
which changes whenever anyone joins or leaves — so a node that filled its budget in a small swarm and
then watched that swarm grow was holding worlds no policy expected of it and refusing every world the
policy did expect, permanently. It even read as correct: a full node's sweep summary says "past the
bounds", which is exactly what a working bound says.

Releasing is now part of the sweep, ordered **before** adoption so freed bytes are spendable in the
same cycle rather than one world per five minutes. Four rules, each removing one way this could
destroy a replica the swarm still needs:

- **Only under pressure.** Nothing is released while the node is inside its budget, so an unfull node
  behaves exactly as before and a wrong placement answer costs it nothing.
- **Only volunteered content.** A world this node hosts is its own; `seeding()` is the whole eligible
  set.
- **Only on a real answer.** `placementFor` became a tri-state, because the two ways of not being
  placed have opposite consequences: `UNKNOWN` (no tracker response, no peers listed, a malformed peer
  set) keeps the world. Without that rule one tracker outage would empty every full node on the
  network simultaneously and have them all re-fetch when it ended.
- **Bounded.** One world per sweep — deliberately below the adoption limit, because a release cannot
  be undone locally — so even a systematically wrong answer drains a node slowly enough to be caught
  in the log it writes on the way.

Evidence: `ReplicationGivesTheBudgetBackTest` (6), on the same pure-decision shape as
`shouldAdopt`. Related and worth knowing before reading a live log: `forWorldArchives(standard())`
derives R = 22 and `factor` caps it at the network size, so **below 22 peers every peer is always an
expected holder** and this rule can never fire.

**The live-join password gate was an unlimited guessing oracle.** Its challenge is single-use per
*connection*, so a client that reconnected drew a fresh nonce indefinitely and the memory-hard KDF
bought nothing online. Failures are now counted per (world, joiner address) and a locked-out joiner is
refused before a challenge is issued. See [`../network/PROGRESS.md`](../network/PROGRESS.md) for the
full note; the worker side of it is that a world this node hosts is no longer brute-forceable at the
rate an attacker can open sockets.

### 2026-07-29 — A launched worker keeps its identity and its registry on a FAT32 drive (W-DUP-3 retired)

The last row of the registry-integrity bundle needed the one thing its component tests could not
supply: a *launched* `nodera-headless` writing its state to a filesystem that advertises no POSIX
attribute view. `HeadlessPeerMainStateTest` had driven the real `openLocalState` seam on zipfs, but
zipfs is an in-process provider and `NODERA_STATE_DIR` cannot name one — so the exit clause stayed
open, and "the worker works from a FAT-formatted drive" remained a claim about a code path.

A FAT32 image on a loop mount can be named. The built distribution was pointed at one
(`Files.getFileStore(...).supportsFileAttributeView("posix") == false`, asserted first) and driven
through the exit clause verbatim: boot, mint an identity, `NODERA-JOIN` to write a registry row, kill,
re-launch from the same paths. The second boot answered `NODERA-IDENTITY` with the same node id and
`NODERA-WORLDS` with the row written before the restart. A second pass drove `NODERA-WORLDID`, so the
third secret-bearing writer — a world's private key — landed there too, as
`<worldId>.worldkey`. Neither log carried a POSIX or permission failure.

Evidence and its limits are recorded in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md): this is a
recorded manual run, not a CI test, because the exit needs a mount. `AtomicFileWriterTest` (3) and
`HeadlessPeerMainStateTest` remain the halves CI can keep honest.

### 2026-07-28 — Registry integrity: stale rows stop announcing, ownership survives stop, duplicates repairable

Three of the four Task 8 registry rows retired together. **W-DUP-1**: a row restored from `worlds.dat`
is now reconciled against the world this node can still *serve* — bound in production to
`WorldArchiveService.newestManifest(...)` — and an unservable world is suppressed from the announce set
within one refresh cycle instead of being re-announced forever. The row is kept, not dropped, so a
repair reinstates it under the same identity; a world with a live game route is never suppressed.
Evidence: `WorldHostingPersistenceTest#anUnservableWorldIsSuppressedWithinOneCycle` and
`#aLiveWorldIsNeverSuppressed`. **W-DUP-2**: `stop` then re-`seed` of a world whose `.worldkey` survives
on disk now reads owned again, because ownership is re-bound from the key rather than re-derived —
`#ownershipSurvivesStopAndReSeed`, with `#aForeignWorldIsNotReAdministered` proving the repair cannot
invent authority. **W-DUP-4**: `WorldRegistryMergeTool` is the one-shot, dry-runnable, backup-first
repair for registries that already hold duplicates; it leaves one row per save with the survivor chosen
by the persisted `nodera-world.dat`, and quarantines rather than guesses when it cannot decide —
`WorldRegistryMergeToolTest` (9), including `#survivorIsChosenByTheOnDiskPin` driven entirely from real
pin and key files on disk. W-DUP-3 remains RETIRING on its launched-process proof.

### 2026-07-28 — Review correction: secure cleanup landed; W-DUP-3 is RETIRING

Review found two security gaps in the first fallback. A failed write or move could leave the
secret-bearing temporary file behind, and catching `UnsupportedOperationException` then creating a
default-permission file did not prove the provider was actually non-POSIX. Both identity and worker
state now delegate to `AtomicFileWriter.writeOwnerOnly`: same-directory `FileStore` capability picks
the creation mode, POSIX uses `0600` before bytes, advertised POSIX that rejects the attribute fails
closed, and all write/move failures attempt deletion with cleanup failures suppressed on the primary
exception. This also completes the `LocalFiles`/`PersistentIdentityStore` duplication refactor.

Evidence: `AtomicFileWriterTest` (3) and
`HeadlessPeerMainStateTest#startupStateSurvivesANonPosixFileSystem`. The latter invokes the same
`HeadlessPeerMain.openLocalState` production seam used by `main`, twice, on zipfs and recovers both
identity and replaced registry. It is still not a launched worker process: environment paths resolve
on the default filesystem and cannot name an already-open zipfs. Therefore the prior retirement was
too strong; **W-DUP-3 moves back to RETIRING** until a non-POSIX mounted/default filesystem drives the
distribution through boot, registry write, and restart.

### 2026-07-28 — Worker state survives filesystems without POSIX attributes

**W-DUP-3 retired.** Both secure write paths reached before a worker can restore its worlds now catch
the provider's `UnsupportedOperationException` from POSIX-at-create and retry without that attribute:
`PersistentIdentityStore` for `worker-identity.bin`, and `LocalFiles` for `worlds.dat`, world keys,
and tombstones. Ordinary `IOException`s still fail the save; POSIX files still receive `0600` at
creation before secret bytes are written; replacement still requests `ATOMIC_MOVE` and falls back
only on `AtomicMoveNotSupportedException`.

Evidence: `WorldRegistryStoreTest#workerStateSurvivesANonPosixFileSystem` uses JDK zipfs, asserts its
attribute views exclude `posix`, creates and reloads one node identity, then writes and replaces one
registry and reloads the updated value. Focused `:peer:test` and full `./gradlew check` green under
the serialized two-worker build gate required by issue #87.

### 2026-07-28 — Documentation sweep: status reconciliation + refactoring register

A category-wide documentation sweep against the current tree. No code changed; this note records the
status reconciliations so the ledger matches reality.

- **Task.0 charter**: the header status moved from the stale "(4 of 5 tasks completed)" to
  **"6 of 8"**, matching the task index that grew to Tasks 1–8. The §6 Files table was corrected for
  the 2026-07-26 module split (`dev.nodera.headless` lives in `peer/`, not `peer/`) and
  the dead `WorkerState.java` row was removed — there is no such file in this category; the live
  `STATE` snapshot is built in `WorkerControlHandler.stateJson` (the `dev.nodera.shadow.WorkerState`
  in `:engine` is unrelated).
- **Tasks 1–7**: every `Last audit` date bumped to 2026-07-28 and file-path prefixes corrected to
  `peer/` where the code now lives. No status changes — the headless evidence for each is
  unchanged and still green.
- **Task 8**: `Owns` refreshed to `W-DUP-1…4, W-FETCH-1, W-REPL-1` after W-REPL-2 and W-REPL-3 retired
  (below).
- **Two limitations retired**: **W-REPL-2** and **W-REPL-3** moved to
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). Both had purely headless exit tests
  (`SupersededManifestEvictionTest` + `ArchiveFetchOverSocketsIT`, including
  `#onlyHeldVersionsAreOffered` and `#aSilentBystanderDoesNotStallTheFetch`), all named methods exist
  in `peer/src/test/java/dev/nodera/headless/`, and the 2026-07-27 PROGRESS notes already
  recorded "1,970 Java tests green" with these as the evidence. The open/retiring count dropped
  8 → 6, and a pre-existing header miscount (it read 7) was corrected.
- **Still RETIRING**: W-FETCH-1 and W-REPL-1 — both state a **live** cross-session bar
  ("a live join to a world whose host is playing", "a live node at 0% reaching 100%") that no headless
  run can satisfy. **Still OPEN**: W-DUP-1…4 (deliverables 6–9 of Task 8).
- **New: [`REFACTORING.md`](REFACTORING.md)** — a refactoring register built from `jscpd` (155 dup
  blocks touch this category) plus manual god-class/long-method findings. Top-3: the archive-fixture
  test cluster (one test is 68% duplicated), the 1,798-line `WorkerControlHandler` god class, and the
  cross-gossip duplication between `WorldOwnershipService` / `WorldGrantGossipService` /
  `WorldDeletionService`.

### 2026-07-27 — Every peer offering a version none of them had

Root cause of the stalled recovery, found by reproducing the live topology over **real sockets**
(`ArchiveFetchOverSocketsIT`) instead of a loopback transport. The first socket test passed at
once — the lane moves a 4 MB archive over TCP fine — so the fault had to be in *what* was being
asked for. The second test reproduced the failure exactly and named it.

The chain:

1. A host archives v1, v2, v3 and closes its game.
2. Every peer learns v3 exists through the manifest exchange. None of them holds it.
3. `supersedeOlderVersions` fires on each of them and **destroys the copies they were serving**.
4. `answerManifestQuery` then offers v3 to anyone who asks, because the node knows of it.
5. The asker requests v3's pieces. `ContentTransferService.serve` looks up the root, finds
   nothing, and `return`s — **no reply, no log, no counter**.
6. `0/73 piece(s)` until the deadline, on every peer, forever.

Three fixes, each closing one link:

- **Eviction is scoped to encrypted versions.** L-55 exists to revoke a password; a plaintext
  archive has none, so evicting it buys no security and costs the world. The L-55 tests documented
  themselves as the re-key case while using plaintext fixtures — that mismatch is how the rule came
  to be applied where it protects nothing. Fixtures are now encrypted, matching their own stated
  intent, and the security property is unchanged for the case it was written for.
- **A peer only offers versions it holds pieces of.** An answer's purpose is to let the asker fetch
  *from this peer*; a manifest for content we do not have serves the opposite.
- **`requestsForUnknownContent` counter.** A request for a root this node lacks is answered with
  silence by design (there is no "I don't have that" on the wire), which is defensible — being
  invisible from the serving side too is not. This single missing counter is why the failure took
  four live rounds to locate.

An earlier attempt to keep the last complete copy unconditionally was reverted: it broke the L-55
tests, and those tests were right. Scoping by encryption resolves the same conflict without
weakening anything.

1,970 Java tests green.

### 2026-07-27 — Asking for a version nobody had

The stall deadline and the wrapped failure text turned the symptom into a sentence:

```
Recovery failed: archive fetch stalled at 0/73 piece(s) after 120s with no progress,
from 2 seeder(s)
```

Two seeders attached, routing healthy, requests going out — and **zero** pieces back. Not slow:
none. Manifests flowed in both directions the whole time, so the lane was alive.

The fetch was asking for a version nobody online held. `requestManifest` chose
`max by version` over the manifests peers answered with, and a manifest answer proves a peer has
*heard of* a version, not that it *holds* one. A host archives every couple of minutes and then
closes its game: the newest version is then known to everyone and held by nobody, and every peer
aims at it and downloads nothing.

The tracker already carries the right signal and it was being ignored — `TrackerResponse.seeders()`
is manifest-root → peers that hold pieces of it. The fetch now prefers the newest version a seeder
is advertised for, falling back to the newest answered version only when the tracker vouches for
nothing (its rows can lag a fresh seed).

Also fixed alongside: the early return asked whether the *newest known* version was complete
locally, not whether *any* version was, so a node holding two complete copies still went to the
network. And a stall now falls back to the newest complete local copy — a world one archive
interval behind that opens beats a current one that does not.

**Not fixed, and deliberately left as a decision:** learning of a newer version evicts complete
older copies (L-55). That rule is correct on its own terms — a superseded ciphertext still opens
under the old password — but it currently pays for that with the world itself. A first attempt to
keep the last complete copy was reverted when it broke `SupersededManifestEvictionTest` and
`FetchSurvivesSupersessionTest`: those tests are right, and quietly weakening a security rule to
make a symptom go away is not a fix. Recorded as W-REPL-2 with the shape of the real answer — the
content store needs to separate "stop serving" from "destroy".

Evidence: `HeldVersionBeatsAnUnreachableNewerOneTest` (2). 1,968 Java tests green.

### 2026-07-27 — The claim that prevented its own repair

"Why aren't the network peers downloading the pieces?" — because one of them had been told it
already had them.

The companion showed a node hosting `MMM` at **0.0% (0 of 73 pieces)** while three other peers held
all 73, and it stayed there. The replication sweep's skip read:

```java
if (holdsCompletely(worldIdHex) || hosts(worldIdHex)) {
    continue; // already this node's problem, one way or the other
}
```

`hosts()` asks the **registry** what this node claims, not the content store what it has, and
`restoreFromRegistry` reloads every row as hosted at boot whether or not a byte survived. So the
state that most needed repairing — a node advertising a world it cannot serve — was the exact state
that disqualified it from repair, and the claim is what caused the skip, so it could never resolve
itself.

`holdsCompletely` already covers a host that genuinely holds its world, so the second clause only
ever fired for an empty claim. It is gone: the skip is about content, and a claim with nothing
behind it is now repaired ahead of both the placement policy and the replica bounds — neither may
excuse a broken advertisement, because no other node can fix this one's.

The sweep's decision was extracted into a pure `shouldAdopt(...)` so it could be tested at all;
`TrackerClient` is final and concrete, so the sweep itself cannot be driven headlessly.
`ReplicationRepairsEmptyClaimsTest` (4), verified failing with the fix disabled.

This is the same root as W-DUP-1: registry rows are restored as authoritative claims and never
reconciled against what the node can actually serve.

### 2026-07-27 — The download the retention policy kept deleting

Second live reproduction of "endless Migrating world…", and a different cause from the first. The
logs name it in two lines from two different workers:

```
peer1 (host):   Seeding world archive 014003ed712c v1 — 106 piece(s), root c8e1d31a6250…
peer1 (host):   Seeding world archive 014003ed712c v2 — 106 piece(s), root fb023eebc673…
peer2 (joiner): Evicted world archive 014003ed712c v1 (root c8e1d31a6250…) — superseded
```

The joiner was downloading v1 of a 27 MB archive. The host's game was open, so it streamed v2. The
joiner's worker learned of v2 through the ordinary manifest exchange and applied L-55 — "only the
newest version is maintained" — which unpinned the blob and unpublished the manifest root its own
`PieceDownloader` was writing into. The dashboard showed the result exactly: **913 KiB downloaded,
0 pieces stored.**

`supersedeOlderVersions` already refuses to run from `seedArchive` for this precise reason, and
says so in a comment about not trading a security fix for a data-availability regression. The same
hazard arrives through the learning path, which that comment did not cover: a rule written for
"this node re-archived" met the case "somebody else did".

Fixed by making a root that is being downloaded outrank the retention policy for the duration, and
applying the deferred policy once the fetch ends. The guard is deliberately narrow — with no fetch
in flight L-55 still evicts in full, which `aVersionNobodyIsFetchingIsStillSuperseded` pins.

The fetch deliberately does **not** re-target the newest version. The copy being downloaded is a
complete, valid world; the joiner opens it and catches up live. Chasing a head that moves faster
than the transfer would never converge.

Evidence: `FetchSurvivesSupersessionTest` (2). Verified to fail with the guard disabled — a test
that cannot fail proves nothing.

Found in the same logs and also fixed: `Nodera worker refused HOST for 'Asd': worker did not answer`
logged by the mod in the same second the worker logged `Now hosting world 'Asd'`. HOST was being
given the 1.5 s *probe* budget while it announces to every tracker and writes the registry on a
worker that may be hashing a multi-megabyte archive. A read timeout is not a refusal; it now gets
10 s.

### 2026-07-27 — One save, one world on the network

An audit of `~/.nodera/worlds.dat` on the author's node found **ten rows for four saves** — `world`
four times under four ids, three minted inside eleven minutes, each with its own `.worldkey`. Five
mechanisms produced them; three are closed.

The structural one: `worldId = SHA256(genesisRoot ‖ authorPublicKey ‖ createdAtEpoch)` was
re-derived on **every** share, and `genesisRoot` is not stable — it is re-certified from whichever
chunks are loaded when `nodera-genesis.dat` is missing. `NODERA-WORLDID` now takes an optional
pinned world id and `WorldIdentity.createPinned` signs it, so a save that has been named keeps that
name for life. The LAN lane mixed the **ephemeral** LAN port into its session id, so every re-open
was a new world; the port is gone from the derivation and a close beacon no longer withdraws a world
that is open again elsewhere. `WorldHostingService` now normalises world-id keys in one place, so a
padded or upper-cased id can no longer create an entry that `stop` cannot remove.

Evidence: see [`Task.8.md`](Task.8.md) §Testing. Not yet closed: registry rows are still immortal,
and existing duplicate registries are not repaired.

### 2026-07-26 — Two people can play together with nothing installed in Minecraft

The worker now hears the multicast beacon vanilla Minecraft sends when a world is opened to LAN, and
can extend that socket across the network. Player A presses **Open to LAN** in an ordinary game; A's
app asks whether to share it; B finds it in the directory and clicks Join; B's own Minecraft
direct-connects to a loopback port that leads to A's running game. **No world data moves** — the
tunnel carries the connection, which is exactly why an unmodified client can use it.

Proven live, two worker processes and a real tracker binary:

```
3. B browses:      "Ashu's Survival World"  players=1  pieces=0  mine=False
4. B clicks Join:  NODERA-OK 127.0.0.1:35461
5. B connects:     B received: [A's world] HELLO FROM PLAYER B
                   A's game actually saw: ['hello from player B']
6. A closes it:    A's sessions: []
```

Two rules are load-bearing and are pinned by tests rather than by discipline. **A guest names a
session, never an address** — `TunnelOpen` has no host field, and `publish` is the only thing that
maps a session to a port, so a peer cannot be talked into proxying into its own loopback or LAN.
**Detection is not consent** — a detected world sits in `offered` and reaches nothing until the
player answers, and "not now" leaves it shareable rather than forgetting it.

### 2026-07-26 — The worker is its own module, and is no longer inside the mod

`dev.nodera.headless` moved from `:peer` to a new `:worker`. It had been a package in the peer
library, which meant the worker executable was compiled into anything depending on that library —
including the NeoForge mod's fat jar, which shipped `HeadlessPeerMain` into every player's `mods/`
folder. A worker inside the mod is a contradiction: the whole point of it is that it outlives the
game.

Verified by counting: `dev/nodera/headless` classes in `neoforge-mod.jar` went 60 → **0**. The mod's
one compile-time reference to the control plane also went: `CompanionProtocol` now holds literals,
with `CompanionProtocolContractTest` comparing them against the worker's definition — a drift alarm
in a test, which fails loudly, rather than in a coupling, which fails silently between two
separately-installed artifacts that talk over a socket. `:paper-plugin` gained `:peer`, which is how
an unmodified Paper/Folia server joins the network with no companion app beside it.

### 2026-07-26 — The worker survives its own restart, and worlds get keys of their own

Two things landed together because they are the same question asked twice: what does this peer keep on
the network, and which of it does it speak for.

**The registry.** `WorldHostingService` held its worlds in memory only, so every worker restart
dropped them all: it stopped announcing, stopped advertising pieces it still had on disk, and answered
`NODERA-STATE` with `"connected_worlds": []`. That is what "the companion app shows no data" turned
out to be — the app was reading the worker correctly and the worker had forgotten. Reproduced against
the shipped launcher before the fix and after: host, `kill`, restart, and the world is now still there
with its original `addedAt`, its ownership intact, and — correctly — no game endpoint.

Evidence: `WorldHostingPersistenceTest`, which asserts against a **second** hosting service reading
the same file, because the only interesting property is the one that crosses a process boundary. It
also pins the negative: liveness is not restored, so a restored world never advertises itself as
joinable.

This also gives worker [Task 3](Task.3.md) deliverable 9 its mechanism — the restore re-announces and
re-registers every persisted world with the rendezvous services on the hosting scheduler — though the
cross-session evidence for that deliverable is still an e2e run nobody has done.

**The keys.** Each world now has its own Ed25519 key pair, minted inside `mintWorldIdentity` because
the world id derivation already binds the minting node's key, which makes that call the moment of
authorship by construction. The private half never leaves the creating machine; the public half
travels in a doubly-signed `WorldOwnership` that every peer verifies for itself, and
`NODERA-PROVE <world> <challenge>` answers a verifier's nonce with a signature only the administrator
can produce.

Evidence: `OwnershipGossipIT` — over a loopback mesh, every peer learns and independently verifies the
owner, a later rival claim does **not** displace it, a tampered claim is refused *and not relayed*,
and an envelope naming a different world than the claim it carries is dropped.

### 2026-07-25 — The worker becomes the node's emitter, and the outage lane is green

`WorkerTelemetryService` + `NODERA-TELEMETRY GET|SET|EVENT` landed, with `TelemetryVerbIT` driving
the real control socket and `scripts/e2e-telemetry.sh` driving the real worker distribution against
the real collector binary.

The evidence that matters is **T6**, the outage lane. With the collector killed mid-run, the suite
compares the worker's whole `NODERA-STATE` answer field by field against the one taken before, and
requires everything except the telemetry block and the clock to be identical — `PROBE` and
`IDENTITY` keep answering, and the send failure surfaces in the telemetry block rather than being
swallowed. That is `Plan.6` D10 turned into a test instead of a promise, and it retires **L-77**.

One decision recorded because it will look conservative later: **absent consent is denied consent.**
A worker started by hand, by a container, or by `scripts/dev.sh` has been told nothing, so it sends
nothing — and T1 proves it by letting two collection windows pass and asserting the collector's
spool is still empty.

### 2026-07-25 — The worker becomes the node's only telemetry emitter

[Task 5](Task.5.md) is scoped, and the decision behind it is the one worth recording: **the consent
record lives with the worker, not with the app or the mod.**

The alternative — each surface emitting its own telemetry — fails three ways at once. Three consent
checks can disagree; the mod's and the app's lifetimes are both shorter than the node's, so a
session's closing events would be lost exactly when they are most interesting; and the process most
likely to be modified by third parties would be the one holding a network path to the telemetry
service. The worker outlives the game, already owns the loopback control endpoint, and is where every
measurement already lives.

Consequence, recorded so it is not rediscovered later: **absent consent is denied consent.** A worker
started by hand, by a container, or by `scripts/dev.sh` has been told nothing and therefore sends
nothing. Registered **L-77**.

### 2026-07-25 — Workers become first-class mesh members

The reported defect was that validators existed only while their player was connected — a region's
committee was whatever players happened to be standing in it, so a logout could collapse it to a
committee of one.

`ResidentQuorumIT` is the exit proof: with two standing workers plus player nodes, a player's logout
leaves the committee at quorum size and it **keeps committing**, with the certificate co-signed by a
peer that has no Minecraft process. The counterfactual — no residents ⇒ committee of one — is asserted
alongside, so the row cannot silently regress to the degraded shape it was reported in.

Grant gossip landed as a live worker consumer in the same period: a co-hosting peer's permission set
had been author-local, so an operator promotion or a ban simply did not exist for the rest of the
network. Every receiver re-verifies the grant against the world's author key — the transport carries
the decision without ever being trusted with it (`GrantGossipIT`, 6).

### 2026-07-23 — World continuity: the worker keeps the world alive

The acceptance was blunt: *the host disconnects — the world must not die with them.*

`WorldArchiveService` seeds the canonical save archive through `SEED`, rides its manifest holdings on
every tracker announce, answers manifest queries from other peers, fetches any world's archive from
the swarm through `ARCHIVE`, and reports real maintained pieces and bytes in `STATE`.

`WorldContinuityIT` proves the whole chain over the **real** tracker and rendezvous binaries, driving
the control verbs exactly as the mod drives them: share → listed → P2P fetch byte-exact → host game
closed → **host worker killed** → the second peer still reproduces the world.

`CompanionCrashSurvivalIT` proves the crash half with real OS processes: the actual worker
distribution runs as a separate daemon, a co-located stand-in game process is SIGKILLed so no shutdown
hook runs, and the daemon keeps answering control verbs with every seeded piece still maintained.

### 2026-07-21 — Out-of-game validation; L-48 RETIRED

Before this, the entire validation stack was runtime-unreferenced outside the mod and the
`simulationmsg` wire family had no live consumer — the code was tested and unused, which is a weaker
position than it looks.

`WorkerValidationService` gave it a host. `WorkerQuorumValidationIT` shows three companion-only
workers forming a committee over the transport, converging on the byte-identical reference-engine
root, persisting co-signed certificates, and failing over to epoch+1 after primary loss. The fallback
lane — also previously orphaned — executes unassigned-region actions through the server lane, and the
soak ratio rides the worker's telemetry.

### 2026-07-21 — Control protocol v2 and live data

The verb table went from a presence probe to a real control plane: `STATE`, `IDENTITY`,
`HOST`/`JOIN`/`STOP`, `PASSWORD`, `STATUS`, `WORLDID`. The dashboard and the multiplayer tabs stopped
showing placeholder zeros, and the "no trackers/rendezvous configured" bug — which was a missing feed,
not a missing configuration — was fixed.

The worker became the **world author**: it holds the signing key, mints signed world identities, and
enforces author-only re-key, which is what turns "only the creator can change the password" into a
cryptographic statement.

### 2026-07-20 — The worker boots

`HeadlessPeerMain` runs a full `PeerRuntime` with persistent identity and serves the loopback control
endpoint the mod's gate probes. Verified live: it boots, becomes gateway, and answers
`NODERA-PROBE 1` → `NODERA-OK`. The decision that shaped everything after it — **Option B**, reuse the
tested Java peer rather than writing a Rust node — was locked here, because the single-engine
determinism rule forbids a second implementation that could re-execute regions.
