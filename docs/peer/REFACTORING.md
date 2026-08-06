# Worker — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: A living register of duplication and structural debt in the worker
     category. Source: jscpd (build/jscpd/jscpd-report.json, 155 dup blocks touch this category) plus
     manual god-class / long-method findings. Update in the SAME commit as any refactor that lands a
     row, and move retired rows to a "Completed" section with the commit that did it. Never delete a
     row. -->

Built 2026-07-28 from `jscpd` plus manual review. Scope: `peer/` (the `dev.nodera.headless`
package) and `peer/.../dev/nodera/peer/control/` (the loopback control protocol). `build/`
excluded. Line counts from `build/loccount.txt`; `% duplicated` = (sum of this file's duplicated
line-runs ÷ file lines) × 100, to one decimal. A `—` means jscpd did not flag the file and it is
listed here on structural grounds (god class / long method).

## 1. Candidates

> **Path note (2026-08-05).** The `worker/…` prefix in this table predates the `:worker` merge into
> `:peer` (2026-07-30). Every row below reading `worker/.../headless/…` lives at
> `peer/src/{main,test}/java/dev/nodera/headless/…` today. The line counts are as measured on
> 2026-07-28 and several files have grown since; the duplication percentages are the ranking signal,
> not a current measurement.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---:|---|---|
| `worker/.../headless/HeldVersionBeatsAnUnreachableNewerOneTest.java` | 117 | 68.4 | `ArchiveRetentionWindowTest` (38), `FetchSurvivesSupersessionTest` (31) | Extract a shared `ArchiveFixture` (build seeder+joiner `WorldArchiveService` over `InMemoryContentStore`, seed N versions, capture manifests) — this whole cluster rebuilds the same 3-peer harness |
| ~~`worker/.../headless/SeedRegionVerbIT.java`~~ | 215 | 57.2 | `RekeyVerbIT` (38), `ConfigVerbIT` (22), `WorldDeletionVerbIT` (25) | **DONE 2026-08-05** — see §3 |
| `worker/.../headless/FetchSurvivesSupersessionTest.java` | 169 | 47.3 | `SupersededManifestEvictionTest` (33), `HeldVersionBeatsAnUnreachableNewerOneTest` (31) | Same `ArchiveFixture` as above |
| `worker/.../headless/ArchiveRetentionWindowTest.java` | 198 | 45.5 | `HeldVersionBeatsAnUnreachableNewerOneTest` (38), `SupersededManifestEvictionTest` (32) | Same `ArchiveFixture` |
| ~~`worker/.../headless/RekeyVerbIT.java`~~ | 295 | 44.7 | `SeedRegionVerbIT` (38), `ConfigVerbIT` (31), `WorldContinuityIT` (22) | **DONE 2026-08-05** — see §3 |
| `worker/.../headless/SupersededManifestEvictionTest.java` | 150 | 43.3 | `FetchSurvivesSupersessionTest` (33), `ArchiveRetentionWindowTest` (32) | Same `ArchiveFixture` |
| ~~`worker/.../headless/OwnershipGossipIT.java`~~ | 260 | 41.5 | `GrantGossipIT` (62) | **DONE 2026-08-05** — see §3 |
| `worker/.../headless/WorldOwnershipService.java` | 214 | 41.1 | `WorldGrantGossipService` (40), `WorldDeletionService` (30) | Extract a `SignedGossipRelay` (members iteration, `transport.send`, exclude-self + exclude-source, log-and-continue on `TransportException`) — three services carry the same relay loop |
| ~~`worker/.../headless/GrantGossipIT.java`~~ | 287 | 40.4 | `OwnershipGossipIT` (62) | **DONE 2026-08-05** — see §3 |
| `worker/.../headless/WorldGrantGossipService.java` | 196 | 38.8 | `WorldOwnershipService` (40), `WorldDeletionService` (23) | Same `SignedGossipRelay` |
| `worker/.../headless/ArchiveFetchThroughputTest.java` | 169 | 37.3 | `ArchiveFetchOverSocketsIT` (41) | Same `ArchiveFixture`; also share the `SocketPeerTransport` bridge lambda |
| `worker/.../headless/ArchiveFetchOverSocketsIT.java` | 203 | 36.5 | `ArchiveFetchThroughputTest` (41) | Same `ArchiveFixture` + the socket `bridge(transport, service)` helper (currently duplicated) |
| `worker/.../headless/CompanionCrashSurvivalIT.java` | 169 | 35.5 | `WorldContinuityIT` (20), `TelemetryVerbIT` (9) | Extract a `WorkerDaemonFixture` (launch the real worker distribution, SIGKILL a co-located stand-in game) — shared with `WorldContinuityIT` |
| ~~`worker/.../headless/WorldOwnershipVerbIT.java`~~ | 234 | 28.2 | self (20), `ConfigVerbIT` (17), `SeedRegionVerbIT` (15) | **DONE 2026-08-05** — see §3; the self-dup (the per-world JSON assert block) is unchanged and still a candidate |
| ~~`worker/.../headless/ConfigVerbIT.java`~~ | 536 | 27.4 | `RekeyVerbIT` (31), `SeedRegionVerbIT` (22), `WorldOwnershipVerbIT` (17) | **DONE 2026-08-05** — the anchor, as sequenced; see §3 |
| ~~`worker/.../headless/WorldDeletionVerbIT.java`~~ | 235 | 26.8 | `SeedRegionVerbIT` (25), `ConfigVerbIT` (18) | **DONE 2026-08-05** — see §3 |
| `worker/.../headless/WorldDeletionService.java` | 325 | 25.2 | `WorldOwnershipService` (30), `WorldGrantGossipService` (23) | Same `SignedGossipRelay` |
| `worker/.../headless/WorldRegistryStore.java` | 200 | 23.5 | `storage/.../WorldShareLink` (11), `peer/.../distribution/WorldArchive` (10), `peer/.../discovery/PersistentIdentityStore` (10) | The canonical encode/save + lower-case-key normalisation; consider a shared `CanonicalStore` helper across the three stores that use it |
| `peer/.../peer/control/WorkerEvent.java` | 159 | 22.0 | `worker/.../headless/WorkerControlHandler` (23) | The hand-rolled JSON `escape(...)` is copied into `WorkerControlHandler`; extract one `JsonEscape` in the control package |
| `worker/.../headless/OwnershipGossipIT.java` | 260 | 41.5 | `GrantGossipIT` (62) | Extract a `LoopbackMeshHarness` (2–3 `PeerRuntime`s over `LoopbackTransport`, member supplier wiring) — `OwnershipGossipIT` and `GrantGossipIT` mirror each other almost line-for-line |
| `worker/.../headless/WorldOwnershipService.java` | 214 | 41.1 | `WorldGrantGossipService` (40), `WorldDeletionService` (30) | **RETIRED** — `SignedGossipRelay` extracted; see §3 |
| `worker/.../headless/GrantGossipIT.java` | 287 | 40.4 | `OwnershipGossipIT` (62) | Same `LoopbackMeshHarness` |
| `worker/.../headless/WorldGrantGossipService.java` | 196 | 38.8 | `WorldOwnershipService` (40), `WorldDeletionService` (23) | **RETIRED** — `SignedGossipRelay` extracted; see §3 |
| `worker/.../headless/ArchiveFetchThroughputTest.java` | 169 | 37.3 | `ArchiveFetchOverSocketsIT` (41) | Same `ArchiveFixture`; also share the `SocketPeerTransport` bridge lambda |
| `worker/.../headless/ArchiveFetchOverSocketsIT.java` | 203 | 36.5 | `ArchiveFetchThroughputTest` (41) | Same `ArchiveFixture` + the socket `bridge(transport, service)` helper (currently duplicated) |
| `worker/.../headless/CompanionCrashSurvivalIT.java` | 169 | 35.5 | `WorldContinuityIT` (20), `TelemetryVerbIT` (9) | Extract a `WorkerDaemonFixture` (launch the real worker distribution, SIGKILL a co-located stand-in game) — shared with `WorldContinuityIT` |
| `worker/.../headless/WorldOwnershipVerbIT.java` | 234 | 28.2 | self (20), `ConfigVerbIT` (17), `SeedRegionVerbIT` (15) | `ControlSocketHarness`; the self-dup is the per-world JSON assert block |
| `worker/.../headless/ConfigVerbIT.java` | 536 | 27.4 | `RekeyVerbIT` (31), `SeedRegionVerbIT` (22), `WorldOwnershipVerbIT` (17) | `ControlSocketHarness` — this is also the largest IT and the anchor for the harness extraction |
| `worker/.../headless/WorldDeletionVerbIT.java` | 235 | 26.8 | `SeedRegionVerbIT` (25), `ConfigVerbIT` (18) | `ControlSocketHarness` |
| `worker/.../headless/WorldDeletionService.java` | 325 | 25.2 | `WorldOwnershipService` (30), `WorldGrantGossipService` (23) | **RETIRED** — `SignedGossipRelay` extracted (both its relays, deletion and revival); see §3 |
| `worker/.../headless/WorldRegistryStore.java` | 200 | 23.5 | `storage/.../WorldShareLink` (11), `peer/.../distribution/WorldArchive` (10), `peer/.../discovery/PersistentIdentityStore` (10) | The canonical encode/save + lower-case-key normalisation; consider a shared `CanonicalStore` helper across the three stores that use it. **Not folded into `HexKeyedStore` (2026-08-05)**: it is a single-file store, so it shares the normaliser and nothing else — the two files it is duplicated with are in other categories |
| `peer/.../peer/control/WorkerEvent.java` | 159 | 22.0 | `worker/.../headless/WorkerControlHandler` (23) | **HALF DONE.** The `headless` half is retired: `headless/Json` now owns the escape and every reply is built through it, and `WorkerTelemetryService`'s third, *different* escape (it replaced `"` with `'`) is gone with it. `WorkerEvent` still carries its own copy — it lives in `peer/.../peer/control/`, which was another agent's territory this round. Promoting `Json.escape` to the control package, or having `WorkerEvent` call it, closes the row |
| `worker/.../headless/WorldContinuityIT.java` | 392 | 19.1 | `RekeyVerbIT` (22), `CompanionCrashSurvivalIT` (20) | `ControlSocketHarness` + `WorkerDaemonFixture` |
| `peer/.../peer/control/ControlWatchStreamTest.java` | 205 | 19.0 | `ControlServerTest` (12) | Shared "open control socket, assert reply line" scaffolding |
| `worker/.../headless/WorldKeyStore.java` | 209 | 16.7 | `WorldTombstoneStore` (19), `WorldRegistryStore` (10) | **RETIRED for the two per-world stores** — `HexKeyedStore` extracted; see §3. `WorldRegistryStore` deliberately does not hold one: it keeps one file for all worlds, so it has no file-for-id resolution and no traversal surface to guard. Its `key(...)` is normalisation only and now the only copy left |
| `worker/.../headless/RegionPieceSeedingTest.java` | 222 | 12.2 | `engine/.../CrossRegionFluidTest` (11), `neoforge-mod/.../RegionSeedSpoolTest` (8) | Mostly the region-snapshot builder; leave unless the engine test also moves |
| `worker/.../headless/WorkerTelemetryServiceTest.java` | 231 | 11.3 | `neoforge-mod/.../ModTelemetryTest` (10), `peer/.../SnapshotBuilder` (9) | Minor — the telemetry-event builder shape |
| `worker/.../headless/WorldRegistryStoreTest.java` | 204 | 10.8 | `peer/.../DurableCoordinatorStateTest` (11), `WorldKeyStoreTest` (11) | The "second instance over the same file" pattern — fold into a `SecondInstanceFixture` |
| `worker/.../headless/HeadlessPeerMain.java` | 794 | 5.2 | `neoforge-mod/.../NoderaPeerService` (33) | **Long `main(...)`.** ~410 lines of linear wiring. Extract a `WorkerContext` builder (identity + transport + meters + tracker) and per-lane factories so `main` reads as "build context, wire lanes, await stop" |
| `worker/.../headless/WorldHostingService.java` | 1048 | 3.9 | self (22) | **Large file** carrying announce + rendezvous register + restore + ownership binding + the `HostedWorld` inner class. Extract `HostedWorld` to its own file and `RendezvousRegistrar` out of `registerRendezvous` |
| `worker/.../headless/WorldArchiveService.java` | 1413 | 3.7 | `WorldOwnershipService` (12) | **Large file.** Two lanes (archive + region) + fetch + serve + retention. Split: `ArchiveSeedLane`, `RegionSeedLane`, `ArchiveFetchLane` over a shared `ContentTransferService`. Retention/supersede rules already isolated — they are the load-bearing part and should stay one place |
| `worker/.../headless/WorkerControlHandler.java` | 1798 | 3.5 | self (26), `WorkerEvent` (23) | **God class.** 26 control verbs in one file. Split per lane (host/world-lifecycle, telemetry, config, lan/tunnel, deletion, directory/sharelink) into delegates behind `ControlHandler`, keeping `WorkerControlHandler` as the composition root. Highest absolute maintainability cost in the category despite the low dup %. **Partly addressed 2026-08-05**: the *self* (26) duplication was the ten hand-concatenated JSON replies, two of which — the hosted-world row and the archive-only world row — were the same twenty-two fields written twice and had already drifted. They now share `content(...)` over the `Json` builder. The per-lane split is still outstanding and is the rest of this row |
| `worker/.../headless/WorldReplicationService.java` | 435 | — | — (not jscpd-flagged) | **AUDITED 2026-08-05 against `SignedGossipRelay`: it does not carry the shape.** Its one peer loop walks the peers a *tracker* reported for a world and fetches from them; it never iterates session membership and never sends a frame, so there is nothing for the relay to serve. The remaining plan is size, not duplication |

## 2. Sequencing

The top-5 ordered so each makes the next cheaper:

1. **COMPLETED — extract `ControlSocketHarness`** (anchors: `ConfigVerbIT`, `RekeyVerbIT`,
   `SeedRegionVerbIT`, `WorldDeletionVerbIT`, `WorldOwnershipVerbIT`). Landed as
   `dev.nodera.testkit.peer.WorkerNode` over the harness package's existing `ControlClient`. See §3.
2. **Extract `ArchiveFixture` + the socket `bridge(...)` helper** (`ArchiveFetchOverSocketsIT`,
   `ArchiveFetchThroughputTest`, `FetchSurvivesSupersessionTest`, `SupersededManifestEvictionTest`,
   `ArchiveRetentionWindowTest`, `HeldVersionBeatsAnUnreachableNewerOneTest`). The highest-dup
   cluster in the category (one test is 68%). Same rationale: test-only, no behaviour risk.
3. **Split `WorkerControlHandler` (1798-line god class) per lane.** The composition root stays; the
   26 verbs move into lane delegates implementing `ControlHandler` defaults. Biggest readability win
   and de-risks every future verb addition. Land after the test harnesses (1–2) so the move is
   protected by the verb ITs already refactored to the harness.
4. **Extract `SignedGossipRelay`** from `WorldOwnershipService` / `WorldGrantGossipService` /
   `WorldDeletionService` (~150 shared lines across the three). The members-iterate + `send` +
   exclude-self/source + log-and-continue loop is identical; one extraction serves all three and the
   replication sweep (audit `WorldReplicationService` at the same time). **Now cheaper than the
   register estimated**: all three services' ITs drive them through one `MeshNode<S>` shape, so the
   extraction has a single test surface to preserve rather than three.
4. **COMPLETED — extract `SignedGossipRelay`** from `WorldOwnershipService` /
   `WorldGrantGossipService` / `WorldDeletionService`. Four relay loops became one. The
   `WorldReplicationService` audit the item asked for is done and recorded on its row: it does not
   carry the shape. See §3.
5. **COMPLETED — promote owner-only writes to `storage.io.AtomicFileWriter`.** `LocalFiles` and
   `PersistentIdentityStore` now delegate to one implementation with fail-closed POSIX creation and
   failure cleanup. See §3.
6. **COMPLETED — `LoopbackMeshHarness`** (`OwnershipGossipIT`, `GrantGossipIT`). Landed as
   `PeerTestHarness.messageNode` + `MeshNode`. See §3.

### 2.1 A shared base class costs the structural budget; a held collaborator does not

`HexKeyedStore` first landed as an abstract superclass, and `:peer:structureReport` went red:
`never_referenced_methods` 136 → 137 and `unreachable_methods` 267 → 268. The three protected
helpers were being called perfectly normally by both subclasses.

The cause is in the analysis, and it is worth knowing before the next extraction here.
`DeadCodeAnalysis.of` asks `graph.referencesTo(method.id())` — an **exact method-id lookup with no
walk up the hierarchy**. An inherited call compiles with the subclass as the receiver, so the
declaration on the base reads as referenced by nothing at all. (`reachableFromEntryPoints` *does*
walk the hierarchy, via `dispatchTargets`, which is why only one of the two buckets moved for
`directory()`.) A base class therefore silently charges this budget one entry per shared method,
for methods that are in fact used.

Composition costs two fields and names the owner at the call site, and it happens to be the more
honest model: a keyring **has** a directory of hex-named files, it is not one. Both stores now hold
a `HexKeyedStore`. Prefer that shape for anything extracted into this package while the budget is a
gate.

## 3. Completed

| Refactor | Evidence | Completed |
|---|---|---|
| Promote `LocalFiles`/`PersistentIdentityStore` atomic owner-only writes | `AtomicFileWriter.writeOwnerOnly`; `AtomicFileWriterTest` (4); both former copies are wrappers only | 2026-08-01 |
| `ControlSocketHarness` + `LoopbackMeshHarness` — Plan 11 phase 3, issue #212 | `dev.nodera.testkit.peer` (`PeerTestHarness`, `WorkerNode`, `MeshNode`, `ValidationNode`, `RegionFixtures`, `Await`); 25 `*IT.java` rewritten onto it; `scripts/test-totals.sh --java` reports 2,423 passed · 0 failed · **12 skipped** before and after; `java.test.code` 52,014 → 50,530 | 2026-08-05 |

### Note on the `ControlSocketHarness` row

The register named a new type. It was not written: `dev.nodera.testkit.harness.ControlClient`
already opens the control socket, sends the verb and reads the reply line — through the product's own
`CompanionClient` — and adding a second one would have made four implementations of that wire rather
than two. `WorkerNode` holds a `ControlClient` and exposes `request(String)` over it. The register's
intent is met; its proposed name is not, deliberately.

### What this makes cheaper, for whoever takes the production rows

Three rows in §1 are production files this phase did not touch and must not
(`WorldOwnershipService` / `WorldGrantGossipService` / `WorldDeletionService` → `SignedGossipRelay`;
`WorldKeyStore` / `WorldTombstoneStore` / `WorldRegistryStore` → `HexKeyedStore`). Both extractions
are now protected by ITs that construct those services through one factory each — `MeshNode`'s
`ServiceFactory` for the gossip trio, `PeerTestHarness.WorkerNodeBuilder.stateDir` for the store
trio — so a constructor change is one edit in the harness rather than one per suite.
| Extract `SignedGossipRelay` (§2 item 4) | `headless/SignedGossipRelay.java`; the four relay loops in `WorldOwnershipService`, `WorldGrantGossipService` and `WorldDeletionService` (deletion + revival) are each one `mesh.flood(frame, excluding, what)` call. `WorldReplicationService` audited and found not to carry the shape | 2026-08-05 |
| Extract `WorldIds` (`key` + `shortId`) | `headless/WorldIds.java`; seven identical `shortId` copies and the `trim().toLowerCase(ROOT)` world-id normaliser now have one home. `WorldHostingService` keeps a one-line delegate because it also declares a `key(String, int)` overload, and a local overload hides a static import of the same name | 2026-08-05 |
| Extract the `Json` reply builder (part of the `WorkerControlHandler` and `WorkerEvent` rows) | `headless/Json.java`; the ten hand-concatenated replies in `WorkerControlHandler` plus `WorkerTelemetryService.stateJson` are built through it, and the twice-written world row is one `content(...)`. Two of the three escape implementations in the category are gone; `WorkerEvent`'s remains | 2026-08-05 |
| Extract `HexKeyedStore` | `headless/HexKeyedStore.java`; `WorldKeyStore` and `WorldTombstoneStore` each **hold** one. The path-traversal guard now exists once and is strictly the stronger of the two former versions — the tombstone store gained the even-length check and the resolved-path containment re-check it did not have. `WorldTombstoneStore`'s two load sweeps and two read-and-verify methods, which differed only in record type, are one `loadAll` each. Also retired two dead accessors: `WorldKeyStore#directory()` and `WorldTombstoneStore#directory()`, which nothing in the tree called | 2026-08-05 |
