# LEGACY.md — Legacy System Ledger (temporary)

<!-- AI-AGENT-INSTRUCTION: Temporary ledger for the Rust-services transition (Tasks 27–29). Every
     Java file whose responsibility moves to `nodera-tracker`/`nodera-rendezvous` is classified
     here BEFORE it is touched. A REMOVE/REWRITE row is resolved only in the same commit that
     lands its replacing task with `./gradlew check` + `cargo test` green. When §1 has no
     unresolved rows and §2 is reflected in the task files, delete this file. -->

Status values: `PENDING` (replacement not landed) → `RESOLVED` (removed/rewritten, commit cited).

## §1 — Legacy Java inventory

Classification: **REMOVE** (deleted outright) · **REWRITE** (file stays, serving/plan role
stripped) · **KEEP** (explicitly audited as *not* legacy — listed to stop future scope creep).

### Tracker system → Rust `nodera-tracker` (Task 28)

| File | Classification | Why / replacement | Status |
|---|---|---|---|
| `java/peer-runtime/src/main/java/dev/nodera/peer/discovery/TrackerService.java` | **REMOVE** | The embedded per-world peer+seeder index and query answering move to the standalone Rust tracker; Java keeps only a `TrackerClient` (announce loop + query). | **RESOLVED** (2026-07-19, Task 28) — deleted with `TrackerServiceTest`/`TrackerIT`; replaced by `TrackerClient` + `TrackerServiceIT` against the real binary |
| `java/peer-runtime/src/main/java/dev/nodera/peer/discovery/package-info.java` | **REWRITE** | Drops the "tracker role runs here" contract text; keeps directory/inventory/bootstrap docs. | **RESOLVED** (2026-07-19, Task 28) |
| `java/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaPeerService.java` | **REWRITE** | Wires `TrackerClient` against configured `tracker.endpoints` (server + client specs). Note: it never actually constructed `TrackerService`, so this was an addition, not a removal — the audit row overstated the coupling. | **RESOLVED** (2026-07-19, Task 28) |
| `protocol/src/main/java/dev/nodera/protocol/discovery/{TrackerQuery,TrackerResponse,InventoryAdvertisement,ManifestSeeders}.java` | **KEEP** | Frozen wire family (tags 27–29) — the Rust tracker *answers these same messages*; deleting them would break the contract, not honor it. | — |
| `peer-runtime/.../discovery/{PeerDirectory,ArchiveInventory}.java` | **KEEP** | Remain as peer-local caches: Task 21 audit/repair and rarest-first selection read them. Only the *serving* role moves out. | — |
| `peer-runtime/.../discovery/{BootstrapClient,CachedPeerStore,InvitationCodec,PersistentIdentityStore}.java` | **KEEP** | Peer-side join mechanisms + persisted identity (L-28 exit). Tracker/rendezvous endpoints become *additional* bootstrap sources, not replacements. | — |
| `neoforge-mod/.../client/multiplayer/TrackerDataSource.java` (+ `TorrentWorldListWidget`, `MultiplayerScreenAddon`, `WorldSearchBox`, `CreateTorrentWorldOption`) | **KEEP** | Task 26 GUI consumes `TrackerResponse` unchanged — after T28 the data simply originates from the Rust binary. | — |
| `diagnostics/src/main/java/dev/nodera/diagnostics/view/TorrentWorldListView.java` | **KEEP** | Pure view model; data source swap is invisible to it. | — |

### Java API unification (issue #30, 2026-07-21)

| File | Classification | Why / replacement | Status |
|---|---|---|---|
| `java/transport-neoforge` (whole module) | **REMOVE** | Placeholder with no main source (one classpath smoke test); the planned in-game relay lane lands inside `neoforge-mod` when it materializes (layering rule 3 — Minecraft types live only there). Its NeoForge toolchain slot is not needed by the unified `java/transport`. | **RESOLVED** (2026-07-21, issue #30) — deleted in the transport-unification commit; `settings.gradle.kts`/`neoforge-mod` no longer reference it |
| `protocol`, `transport-api`, `transport-socket`, `transport-rendezvous`, `storage-api`, `storage-eventsourced`, `storage-rocksdb`, `storage-client` (modules) | **REWRITE** | Merged into the unified `java/transport` and `java/storage` modules — packages unchanged, only the Gradle module boundary moved. | **RESOLVED** (2026-07-21, issue #30) |

### Rendezvous / relay / NAT → Rust `nodera-rendezvous` (Task 29)

| File | Classification | Why / replacement | Status |
|---|---|---|---|
| `transport-libp2p` (planned module — never built; commented placeholder in `settings.gradle.kts`; spec'd in the pre-2026-07-19 Task 10) | **REMOVE** (from plans) | Superseded before birth: Plan §3.10's "Rust sidecar plan B" is promoted to the plan — `rust/nodera-rendezvous` + `java/transport-rendezvous` (Task 29). | **RESOLVED** (2026-07-19, Task 27) — the commented `settings.gradle.kts` line is gone; `Tested.md` row renamed to `transport-rendezvous` |
| `transport-socket/src/main/java/dev/nodera/transport/socket/SocketPeerTransport.java` | **KEEP** | Stays the LAN/direct-TCP path (L-27 text always said so); `transport-rendezvous` composes around it, does not replace it. | — |
| `protocol/src/main/java/dev/nodera/protocol/RelayEnvelope.java` + `transport-neoforge` | **KEEP** | The *NeoForge server relay* is the Phase 1–4 in-game lane and permanent fallback (Plan §3.10) — a different relay than the Task 29 internet relay. Name collision only. | — |
| `peer-runtime/.../peer/{PeerRuntime,GatewayElection,SessionView,TickSync}.java` + `protocol/membership/*` | **KEEP** | Session/gateway roles are in-game peer responsibilities riding whatever transport exists; the rendezvous service coordinates *reachability*, never sessions. | — |
| `coordinator/.../{RendezvousPlacementPolicy,NodeRegistry}.java`, `peer-runtime/archival/RendezvousArchivePolicy.java` | **KEEP** | "Rendezvous" here = rendezvous *hashing* (deterministic placement math), unrelated to the rendezvous *service*. Flagged to prevent an over-eager cleanup. | — |

> **Task 29 landed (2026-07-19).** `rust/nodera-rendezvous` (signed-record registration, paged
> discovery, HMAC relay reservations, tokio circuit bridging + metering, punch coordination) and
> `java/transport-rendezvous` (the third `PeerTransport`: direct-first / punch-upgrade /
> E2E-encrypted relay-fallback) shipped; wire family tags 35–43 + `PeerCandidate`/`SignedPeerRecord`
> are byte-exact cross-language; L-23 and L-27 are RETIRED (`RendezvousRelayIT` drives the real
> binary). Task 29 is **additive** — it removes no Java files beyond the already-resolved
> `transport-libp2p` plan (row above), so there is nothing new to flip here. The `KEEP` rows stand.

## §2 — Task-file ledger (rewrites / removals)

> **2026-07-21 consolidation note:** all legacy `Task.<N>.md` files (and `Prompt.base.md`) were
> moved verbatim to [`docs/old/`](old/); the current specs are the module tasks
> `docs/Task.0.md` … `docs/Task.7.md` (mapping: `Task.0.md` §4). `MONOREPO.md` was retired — the
> monorepo is the default architecture (`Task.0.md` §3); its migration record survives as
> [`old/MONOREPO.md`](old/MONOREPO.md) + [`old/Task.27.md`](old/Task.27.md). The path-prefix rewrite queue below is thereby **RESOLVED**:
> the new module tasks are written against the monorepo layout. File references in this ledger's
> tables use the legacy paths as historical record — resolve them under `docs/old/`.

No legacy `Task.<N>.md` file was deleted in this transition, so **no renumbering is required**
(the renumber-on-delete rule stays armed for any future deletion). Task 17 remains file-less
(standing debugger issue), as before.

| Task file | Action | Reason |
|---|---|---|
| `docs/Task.10.md` | **REWRITTEN** (2026-07-19) | Its `transport-libp2p` half (jvm-libp2p, `NatTraversalManager`, `RelayManager`, `TransportSelector`) is superseded by Task 29's Rust rendezvous+relay service and `transport-rendezvous`. Task 10 keeps gateway migration + the full-peer-down demo and now *consumes* the Task 29 transport. |
| `docs/Task.20.md` | **REWRITTEN** (2026-07-19) | Its embedded `TrackerService` serving role is interim, superseded by Task 28's standalone Rust tracker. Task 20 keeps the Java-side discovery it already shipped (directory, inventory, 3-mechanism bootstrap, persistent identity). |
| `docs/Task.0.md`, `README.md`, `docs/Roadmap.md`, `docs/Plan.md`, `docs/Prompt.base.md`, `docs/LIMITATIONS.md` | **UPDATED** (2026-07-19) | Index/graph/status/limitation rows extended for Tasks 27–29 and the Rust-services decision. |
| Tasks 1–16, 18–26 (path references) | **REWRITE QUEUED** (Task 27 landed 2026-07-19) | Monorepo path-prefix pass per `MONOREPO.md` §"Task-spec rewrite note" — mechanical except Tasks 1/4 (build/transport layout sections). |

## §3 — Removal discipline

1. Nothing in §1 is deleted before its replacing task's acceptance criteria are green
   (`./gradlew check` + `cargo clippy -D warnings` + `cargo test`, plus the task's ITs).
2. The deleting commit flips the row to RESOLVED with the commit hash — same-commit discipline
   as the LIMITATIONS ledger.
3. A KEEP row may be re-classified only by editing this file first (review gate), never by a
   drive-by deletion.
4. When every REMOVE/REWRITE row is RESOLVED and the §2 queue is empty, delete `LEGACY.md`
   itself (its history is the record).
