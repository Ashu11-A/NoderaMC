# LIMITATIONS.md — The Binding Limitation Register

Target state: **zero permanent scope exclusions and zero player-visible limitations.**

This register is normative (referenced by `Plan.md` and Task 0 §7). Rules:

1. **"Permanent" is banned.** Every limitation is either an *envelope constraint*
   (§A — a fact of physics/platform that is engineered around until players cannot
   observe it) or a *staged capability* (§B — open until its owning task retires it
   with a passing exit test). There is no third category.
2. Every §B entry has an **owning task** and an **exit test**. A task that stages or
   retires an entry updates this file in the same commit (Task 0 §7 definition of done).
3. New limitations discovered during implementation enter §B as OPEN with a path and an
   owner before the discovering PR merges.
4. **Assumption A0** (Task 0): every player runs the Nodera mod and joins as a network
   peer. There is no vanilla-client population; the handshake enforces it. Entries that
   only existed because of a vanilla-client population are RETIRED in §C.

Status values: `OPEN` → `RETIRING` (owner task in progress) → `RETIRED` (exit test green).

---

## §A — Envelope constraints (never denied, engineered around)

These do not burn down; each carries the mechanism that removes its *player-visible*
impact. An §A entry is "satisfied" when its mechanism has shipped and the impact is not
observable in normal play.

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-1 | Consensus latency can exceed the 50 ms tick budget | Batching (T6); pending-ghost rendering (T6); full client prediction + rollback makes commit latency invisible (T16) | 6 → 16 |
| A-2 | All-to-all validation is O(n²) | Regional committees — messaging is O(committee) per region (T7); bulk traffic direct P2P (T10) | 7, 10 |
| A-3 | Under partition, safety beats liveness: a minority partition cannot commit its regions | Regions pause, never fork; forward sync on rejoin (T9); dynamic committee reconfiguration shrinks the blast radius (T16) | 9 → 16 |
| A-4 | NeoForge payload caps (≤ 1 MiB clientbound, < 32 KiB serverbound) | Chunked zstd streams (T4); content-addressed multi-seeder transfer (T9 → delivered by T19/T20); direct P2P bulk lane (T10) | 4, 9, 19, 20, 10 |
| A-5 | JVM float math is not reproducible across hardware | Purpose-built engine; fixed-point Q32.32 for all continuous quantities in hashed state (T2, T3, T12) | 2–3, 12 |
| A-6 | Players concentrated in one region serialize on one primary | Correctness unaffected; most-capable-peer primary selection (T6); sub-region work splitting as a T16 stretch goal | 6 → 16 |
| A-7 | Minecraft/NeoForge version churn breaks mixins | Pinned versions upgraded in single commits (T0); minimal load-bearing mixin set (T1/T11); Minecraft-free core modules | 0, 1, 11 |

---

## §B — Staged capabilities (the burn-down list)

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-1 | Random ticks suppressed in delegated regions (no grass/fire/crop ticking there) | T14 | Farm soak: engine-owned random ticks, identical roots on 3 replicas, suppression counter deleted | OPEN |
| L-2 | Fluids excluded from validated lane | T14 | Water/lava spread deterministic across replicas, incl. cross-region via T13 migration | OPEN |
| L-3 | Gravity blocks + fire excluded | T14 | Sand/gravel fall + fire spread validated; parity envelope vs vanilla documented | OPEN |
| L-4 | No deterministic lighting (light-dependent mechanics can't validate) | T14 | Engine skylight/blocklight matches committed roots; crops/spawning read engine light | OPEN |
| L-5 | Observer + quasi-connectivity missing; redstone palette v2 semantic gaps | T14 | Palette v3 ships observer + QC; `COMPATIBILITY.md` gap note deleted | OPEN |
| L-6 | Daylight/time-coupled blocks excluded (time not in validated context) | T14 | Committed world-time in `RegionExecutionContext`; daylight sensor validated | OPEN |
| L-7 | Mobs are server-authoritative ghosts; AI not validated | T15 | Per-species retirement: ghost-share = 0 for every shipped species in soak | OPEN |
| L-8 | Mob spawning cycles vanilla-only | T15 | Deterministic spawn cycles (engine light + seeded RNG) match vanilla rate envelope | OPEN |
| L-9 | Projectiles, TNT, rails/minecarts not validated | T15 | Arrow/pearl/TNT/cart determinism fixtures green on 3 replicas | OPEN |
| L-10 | Comparator, hoppers, note block outside every lane (need containers) | T16 | Container lane ships; palette completes; regions with them delegable | OPEN |
| L-11 | Player inventory outside region root (pickup = one-way credit) | T16 | Inventory state validated; dupe-proof under cross-region transfer tests | OPEN |
| L-12 | Player movement server-authoritative | T16 | Optimistic validated movement with rollback; cheat-movement rejected by committee | OPEN |
| L-13 | Combat / health / XP not validated | T16 | PvP + PvE damage validated; state in root; `@Invariant(10)` extended test | OPEN |
| L-14 | Portals, dimensions, commands outside validated path | T16 | Cross-dimension = generalized region transfer; deterministic command subset | OPEN |
| L-15 | Regions outside generated terrain non-delegable | T16 | Deterministic world-gen-from-seed in engine; `OUTSIDE_GENERATED_TERRAIN` reason deleted | OPEN |
| L-16 | Committed effects appear 1–2 ticks late (ghost/pending render) | T16 | Client prediction + rollback: local apply instant, reconciliation invisible in normal play (interim: T6 ghost render) | OPEN |
| L-17 | Gateway migration = brief reconnect + action freeze | T16 | Local-replica world view: play continues with zero reconnect during migration | OPEN |
| L-18 | Membership semi-trusted (whitelist); Sybil/collusion not defended | T16 | BFT committee rotation + admission control + dynamic sizing; Byzantine ITs green under adversarial FakePeers | OPEN |
| L-19 | Small populations degrade quorum (3-of-4 → 2-of-3 degraded mode) | T16 | Dynamic committee sizing with certified reconfiguration replaces static degraded mode | OPEN |
| L-20 | Genesis is a single-signer trust root (server self-certifies) | T16 | Multi-party genesis re-certification signed by founding peer set | OPEN |
| L-21 | Third-party mods excluded from validated regions (palette exclusion) | T16 | Deterministic RuleSet SDK: mods ship rule packs, covered by registryFingerprint; SDK sample mod validated in CI | OPEN |
| L-22 | Fixed spot-check floor costs ~12.5% server re-execution | T7/T8 | Adaptive spot-check (N=4→64 by reliability); steady-state ≤ 2% for proven committees | RETIRING |
| L-23 | Cross-NAT direct P2P unproven (was: the jvm-libp2p bet — superseded 2026-07-19 by the Rust rendezvous relay, Task 29) | T29 | `RendezvousRelayIT` cross-NAT soak green; `TransportSelector` direct/punched/relayed mix exercised; pure-relay gateway continuity green (Task 10 acceptance #1) | RETIRED |
| L-24 | `mobCapture` ghost lane default-off until proven | T15 | Flips default per-species as validation ships | OPEN |
| L-25 | Async world writes by other mods undefined under the guard | T16 | RuleSet SDK provides the legal async mutation API; guard rejects the rest with a documented error | OPEN |
| L-26 | Redstone bounded to palette v2 | T13→T14→T16 | v2 (T13) → +observer/QC/daylight (T14) → +comparator/hopper/note (T16): full redstone parity | OPEN |
| L-27 | Direct-P2P `SocketPeerTransport` needs reachable listen endpoints (LAN / port-forward / VPN); no NAT hole-punching or relay fallback | T29 | `transport-rendezvous` behind the same `PeerTransport` seam adds hole-punch upgrade + end-to-end-encrypted relay circuits against the Rust `nodera-rendezvous` service; `SocketPeerTransport` stays the LAN path; cross-NAT continuity soak green | RETIRED |
| L-28 | Peer identity is ephemeral — `NodeIdentity` is regenerated per process, so a returning peer/server gets a new `NodeId` | T20 | Identity persisted (`server-identity.bin` / client game-dir) and reloaded; returning peer keeps its `NodeId` and re-joins its committees | RETIRED |
| L-29 | Gateway election is rendezvous-hash only; `NodeCapabilities` are carried but not yet weighted (Plan §3.5) | T9 | Capability-weighted rendezvous (cores/mem/latency/reliability) selects the gateway; determinism property test still green | RETIRED |
| L-30 | Continuity beta meshes peers full-mesh with gossiped membership; ~~no committee re-execution / quorum on the P2P lane~~ (committee validation now runs over the `PeerTransport` — the T9 forward event-sync half remains) | T7→T9 | Committee validation (T7) and event-sourced sync (T9) run over the same `PeerTransport`; certified region state flows peer-to-peer | RETIRING |
| L-31 | In-game diagnostics HUD ships session + net panels live; the region-ownership and entity-control panels/boss-bars render `UNASSIGNED` placeholders (zone geometry is real, ownership is not) | T18 | With a committee (T6) / entity lane (T12) active, `/nodera regions` shows `ownedChunks > 0` and the zone boss-bar turns GREEN inside an owned region; placeholder path deleted | OPEN |
| L-32 | World data transfers whole-region only; chunking is transport-level frame-splitting, pieces are not addressable, no multi-seeder swarm fetch | T19 | Chunk-section `PieceManifest` + `ContentRequest/Chunk/Availability` + deterministic rarest-first selection; resume-after-partial test green; bad-piece hash-reject green | RETIRING |
| L-33 | No async client chunk pipeline; a region renders only after its whole snapshot arrives, no lock-until-arrived guard | T19 | Pieces render on arrival; un-arrived section locked vs edit; manifest hash validates before render; `DistributionIT` reassembles from seeders each holding <40% | RETIRING |
| L-34 | No tracker / archive-inventory / multi-bootstrap; a peer learns the mesh only via single-bootstrap gossip, cannot list worlds/peers/seeders by content | T20 | `TrackerQuery/Response` returns peers+seeders+counts+health; `ArchiveInventory` advertised+queried; `BootstrapClient` joins via configured-list / `CachedPeerStore` / `InvitationCodec` with the original bootstrap offline | RETIRING |
| L-35 | No replication placement or repair; content held only where produced, no redundancy guarantee, no ≥25%-seed / <5%-per-peer enforcement | T21 | Rendezvous `ArchivePlacementPolicy` hits snap×5/log×4; dynamic seed floor `min(25%, R/N)` + per-peer cap `max(5%, 2·R/N)` enforced (5% asymptote at large N; `FULL_ARCHIVE` host exempt); `ArchiveRepairIT` re-creates missing replicas after a peer kill with no data loss | RETIRING |
| L-36 | Reliability is a single proposal-outcome EMA; connectivity/uptime/availability/worlds-seeded not weighted (Plan §3.5/§10) | T22 | Weighted multi-factor score drives placement/gateway/handoff; determinism property test green; offline-decay implemented | RETIRING |
| L-37 | No client storage quota / eviction policy (`storage-client` unbuilt); remote-peered data can grow unbounded | T22 | `BoundedClientWorldStore` + `StorageQuotaManager` + `ArchiveEvictionPolicy` (never evicts assigned-region current state); unit tests | RETIRING |
| L-38 | No retention-before-drop; worlds never garbage-collected, no coordinated 24 h decommission | T22 | Coordinated 24 h countdown (network-visible) on zero-seeder worlds; cancel-on-seeder-return; drop-at-expiry; `RetentionIT` | RETIRING |
| L-39 | World content is plaintext on the P2P net; any connected peer can read any chunk; no per-world encryption | T23 | AES-GCM-256 content encryption under Argon2id(password)-derived key; seeders store ciphertext, verify by hash; join requires password; ciphertext-integrity + wrong-password + nonce-uniqueness tests green | RETIRING |
| L-40 | No continuous active-player data stream and no shutdown-hook flush; crash safety is replay-only | T24 | `ActivePlayerStream` keeps replicas within one batch of live state; `EmergencyFlush` + shutdown hook drain under-replicated pieces on clean exit; `CrashRecoveryIT` proves no committed-data loss on `kill -9` via redundancy | RETIRING |
| L-41 | No separate-OS-sidecar process for emergency chunk flush on a Minecraft crash (rule 5 full form) | T24 (stretch) → T32 | The Task 32 companion daemon **is** the separate always-on process: it runs the peer out-of-game, so a Minecraft `kill -9` cannot take the node down. RETIRED when the daemon's continuous seed/flush is proven to survive a game crash (a different process, by construction) | RETIRING |
| L-42 | No cross-peer tick-skew / TPS metric; region-boundary sync has no laggard detection, no low-TPS handoff | T25 | `TickSkewMeter`/`TpsMeter` computed outside the engine; `LagHandoffPolicy` triggers committee failover on sustained skew; `LagHandoffIT` proves boundary consistency after a laggard primary is replaced | RETIRING |
| L-43 | No client multiplayer GUI; surfaces are server-pushed packets only (tab/boss/action-bar); no server-list/search/health/torrent-host-create screen | T26 | Multiplayer page lists torrent worlds (player/friend/recent) + search; per-world player/chunk/reliability counters; red/gray health + 24 h countdown; create-world "torrent hosting" + password option; `runClient` acceptance (GUI env) | RETIRING |
| L-44 | Tracker is embedded in a Java peer (`TrackerService`, Task 20) — the world list / announce surface dies with its host peer; no always-on discovery infrastructure | T28 | `TrackerServiceIT`: the standalone Rust `nodera-tracker` binary serves the world list with every Java seeder of a world offline; peers announce/query it; embedded serving path deleted per `LEGACY.md` | RETIRED |
| L-45 | No automated real-client GUI acceptance harness. Task 30's decentralization is proven headlessly + compile-clean, but the pause-menu "Share" flow, integrated-server host activation, and a second client joining a shared world are only *manually* verifiable — the mod jar dropped into `~/.minecraft/` (NeoForge 21.1.x), driven by hand; there is no `neoforge { runs { register("client") } }` block, so `runClient` cannot even launch from Gradle | T30+ | A `runs` block ships; a headless `runClient` under Xvfb drives Share → a second client sees + joins the world through the tracker + rendezvous/socket, asserting the listing and mesh formation | OPEN |
| L-49 | Task 33 landed the world-identity/authorship + P2P permission model + live worker telemetry headlessly, but several enforcement/UX halves ride the live NeoForge/worker mesh: the singleplayer world-list per-row "server-like" player count needs the first mixin (`WorldSelectionListEntryMixin`) in a GUI env; live chunk/region validation + revalidation needs the `committee`/`coordinator` stack wired over the worker's `PeerRuntime` (the headless `CommitteeMvpIT` pipeline, now live); permission grants must be gossiped/announced and `BANNED` enforced at `JOIN`; password change re-encryption must propagate over the network (Task 23 live); the worker must seed extracted region pieces | T33 | `runClient`: a shared world shows a live player count on its row; a committee re-executes + commits a region delta over the worker mesh; a BANNED peer is refused at join; a re-keyed world's new ciphertext replaces the old across seeders | OPEN |
| L-46 | Task 31 GUI redesign is proven by view-model tests + compile-clean screens, but the presentation itself is unverified live: "Open to Nodera" taking the vanilla LAN slot, the single-player public-world badge's per-row placement (screen-level summary only for now — vanilla `WorldSelectionList` row geometry is not cross-package accessible), the tabbed `NoderaMultiplayerScreen`, and the `PieceMapWidget` green-fill grid all wait on a GUI env; the feeds (own worlds, tracker/rendezvous status, per-piece state) are pluggable suppliers defaulting empty until the live wiring / Task 32 daemon lands | T31 | `runClient` (with L-45's harness) shows one "Open to Nodera" button in the LAN slot, a live public-world count on the world list, three working multiplayer tabs, and a piece-map that fills green as pieces arrive | OPEN |
| L-47 | Task 32 peer worker + gate landed (`nodera-headless` boots a `PeerRuntime` + serves the `ControlServer` the mod probes — verified live; the mod's `companion.required` gate defaults ON so Minecraft aborts without the worker; `scripts/dev.sh` runs the worker; the Tauri app supervises/monitors it, workspace-excluded). Remaining: the worker's live telemetry pump feeding the dashboard's real metrics, the worker↔mod host/join control verbs (so hosting delegates to the worker rather than the in-JVM peer), per-OS installers, app icons, and an automated end-to-end installer + gate + cross-machine continuity test | T32 | A CI/tooling job builds the app, runs the gate both ways (worker present ⇒ start, absent ⇒ actionable abort), and proves a hosted world stays listed + joinable after the host closes Minecraft (the worker kept it alive) | OPEN |
| L-48 | ~~The always-on node cannot validate regions from the companion~~ | T32/T16 | The bundled headless Java peer runs `committee` re-execution out-of-game and casts votes; a companion-only node participates in a quorum in a headless IT | RETIRED |

> **L-32/L-33 status (Task 19, 2026-07-18).** The Minecraft-free half is green: the `distribution`
> module ships the addressable piece plane (`PieceManifest`/`Piece`), the append-only
> `ContentRequest`/`ContentChunk`/`ContentAvailability` wire tags, deterministic rarest-first
> selection, hash-validate-before-accept with retry-away-from-the-liar, piece-level resume, and the
> `ChunkLockMap` fail-closed lock. `DistributionIT` proves reassembly from 3 seeders each holding
> <40% of the pieces, with the assembled blob hashing to the engine's own `StateRoot`. Both rows
> move to RETIRED once the mod-side consumers exist — the renderer and `WorldMutationApplier`
> actually consulting `ChunkLockMap.isChunkEditable` on a live server — which is gated on the same
> NeoForge lane as Tasks 5–8's live halves.

> **L-28 retired (Task 20, 2026-07-18).** `PersistentIdentityStore` persists a peer's `NodeIdentity`
> (owner-only, atomic temp+move write) and reloads it on the next run, so a returning peer/server
> keeps its `NodeId`. Verified by `PersistentIdentityStoreTest.loadOrGenerate...reloadsTheSameIdentity`.
>
> **L-34 status (Task 20, 2026-07-18).** The Minecraft-free control plane is green: `TrackerService`
> answers per-world `TrackerQuery` with peers + per-manifest seeders + counts + reliability-in-basis-
> points + `WorldHealth`; `ArchiveInventory` (piece-level, LRU-bounded) ingests
> `InventoryAdvertisement` gossip; `BootstrapClient` joins via all three mechanisms (configured list,
> `CachedPeerStore` redial, signed `InvitationCodec`) with the original bootstrap offline
> (`TrackerIT`/`MultiBootstrapIT`). The row moves to RETIRED once the mod side wires the tracker
> feed and the multiplayer UI (Task 26) reads it — gated on the NeoForge lane. **Note (2026-07-19):**
> the embedded `TrackerService` is interim — Task 28 moves the serving role into the standalone
> Rust `nodera-tracker` (L-44), so the retiring mod-side wiring constructs a `TrackerClient`
> against configured endpoints, not the embedded service.

> **L-35 status (Task 21, 2026-07-18).** The Minecraft-free durability layer is green: deterministic
> rendezvous placement (snap×5/log×4/compacted×3, host exempt from R), the ≥25%-seed floor and
> <5%-per-peer cap (both dynamic in R/N), an audit that diffs expected vs the live inventory, and a
> bounded repair service that pulls pieces by hash, verifies before recording, and is re-audited
> rather than trusted. `ArchiveRepairIT` re-creates missing replicas after a peer kill with no data
> loss. The row moves to RETIRED once the mod side runs the repair coordinator on a live mesh under
> churn — gated on the NeoForge lane (and on Task 22's reliability scoring, which feeds the
> free-rider penalty).

> **L-36/L-37/L-38 status (Task 22, 2026-07-18).** The Minecraft-free halves are green. L-36: the
> multi-factor `ReliabilityScorer` blends correctness+connectivity+uptime+availability+worlds-seeded
> in pure-integer basis-point math (bit-identical across JVMs), with slash-to-0 and offline decay.
> L-37: the new `storage-client` module (`BoundedClientWorldStore`/`StorageQuotaManager`/
> `ArchiveEvictionPolicy`) honours a byte budget, evicts oldest-cold-first, never evicts an assigned
> region's current state, and signals repair. L-38: `RetentionPolicy` runs a coordinated (earliest-
> deadline) 24-h countdown on zero-seeder worlds, cancels on seeder return, drops at expiry. All
> three move to RETIRED once the mod side wires them into the live runtime — the scorer fed by
> PeerLink/heartbeat/seed-share, the client runtime over the bounded store, the tracker surfacing
> the countdown — gated on the NeoForge lane.
>
> **L-39 status (Task 23, 2026-07-18).** The Minecraft-free encryption path is green. AES-GCM-256
> encrypts each piece under a password-derived content key; Argon2id is production-default behind
> pinned `bcprov-jdk18on:1.78.1`, with JDK PBKDF2 fallback. KDF costs and input sizes are bounded
> before allocation. Seeders receive only ciphertext and verify ciphertext hashes; joiners re-derive
> nonces locally, reject wrong passwords/tamper, and prove decrypted bytes against the plaintext
> engine `StateRoot` (`EncryptedDistributionIT`). Password/key material is never serialized and
> there is no escrow: password loss means no recovery. Plaintext manifests still reveal structure
> metadata (region ids, piece sizes/counts, KDF parameters, cadence), not block contents. L-39 moves
> to RETIRED once opt-in create/join wiring and password-attempt throttling ship in the NeoForge lane.
>
> **L-40 status (Task 24, 2026-07-18).** The Minecraft-free durability path is green.
> `ActivePlayerStream` coalesces each region to its newest committed manifest, reuses physically-held
> hashes, counts only verified store/activation acknowledgements, and advances under explicit byte
> windows; `ActivePlayerStreamIT` keeps ten versions across five holders within one batch.
> `EmergencyFlush` prioritises lowest replication, excludes the departing peer, and uses one absolute
> deadline; `PeerShutdownHook` runs it exactly once before goodbye/runtime stop. Committee
> `VotePersistence` durably prepares state before ACCEPT and stores certificates before canonical
> apply. `CrashRecoveryIT` forcibly kills a child JVM (proving no shutdown hook ran), discards the
> primary, restores snapshot ×5 into actual destination stores, and replays/restarts from a surviving
> certified root. L-40 retires when live NeoForge commit signals, content-message adapters, holder
> inventory, and client/server lifecycle registration use these seams. L-41 remains OPEN: no separate
> OS sidecar exists; current proof establishes redundancy safety, not a crash-surviving quarantine
> process.
>
> **L-42 status (Task 25, 2026-07-18).** The Minecraft-free lag path is green. `SessionKeepAlive`
> tag 23 now emits v2 with canonical per-region assignment progress while accepting v1 as empty;
> `TickSync` admits only locally verified certificates as region/network references and treats remote
> reports as advisory. Integer-EMA `TickSkewMeter` exposes validator and region skew, `TpsMeter`
> measures commit throughput using injected monotonic time, and `LagHandoffPolicy` requires skew
> strictly above four ticks for consecutive windows with assignment resets and cooldown. Guarded
> `CommitteeFailover` rejects stale decisions, applies one reliability penalty, and bumps exactly one
> epoch. `LagHandoffIT` proves isolated promotion, continued commit, untouched neighbouring state,
> and certified replay. L-42 retires when live committee commit feeds, coordinator policy scheduling,
> diagnostics HUD exposure, and NeoForge runtime construction use these seams.

> **L-43 status (Task 26, 2026-07-18).** The headless + compile halves are green. The Minecraft-free
> `TorrentWorldListView` builds the multiplayer panel from tracker data (name/players/chunks/
> reliability/health/countdown rows, case-insensitive search, deterministic order), with the
> red/gray rule carried by three new dedicated `Semantic` world-health values — distinct from the
> session `Health` trio, so the Task 18 HUD keeps its yellow DEGRADED. The `Dist.CLIENT`
> `dev.nodera.mod.client.multiplayer` package compiles against NeoForge 21.1.77: a
> `ScreenEvent.Init.Post` hook (no mixin — `nodera.mixins.json` stays empty) adds the search box +
> list widget to `JoinMultiplayerScreen` and the torrent-hosting toggle + independent password field
> to `CreateWorldScreen`; `TrackerDataSource` unpacks `TrackerResponse` outside `diagnostics`.
> The row moves to RETIRED after the `runClient` GUI pass with live tracker data — gated on the
> same GUI env as Tasks 1/4/18 — plus the live create-world pipeline (manifest + tracker
> registration + host-peer roles).

> **L-44 retired (Task 28, 2026-07-19).** The standalone Rust `nodera-tracker` serves the frozen
> discovery family (tags 27–29) and the appended announce family (33–34). `TrackerServiceIT` spawns
> the real release binary and drives it from Java peers: two peers announce two worlds with
> per-world isolation, a JDK-`NodeIdentity`-signed announce is verified by `ed25519-dalek` inside
> the service, a tampered record is refused with `bad-signature` and never reaches the registry, a
> `STOPPED` announce removes a peer immediately, and — the row's actual exit — a world whose every
> Java seeder has gone silent past the TTL is **still listed by name with its countdown and a DEAD
> verdict**, which the embedded tracker could not do. The embedded `TrackerService` (+ its unit test
> and `TrackerIT`) is deleted; `PeerDirectory`/`ArchiveInventory` stay as peer-local caches per
> `LEGACY.md`. Remaining gap, tracked with the Task 26 live GUI work rather than here: the mod's
> announce loop is constructed but not yet scheduled on a timer, so the endpoints are wired and the
> query path is proven headlessly while the periodic announce lands with the live client pass.
>
> **L-23 + L-27 retired (Task 29, 2026-07-19).** The standalone Rust `nodera-rendezvous` service and
> the `java/transport-rendezvous` module close the cross-NAT / relay-fallback gap. The service speaks
> the frozen rendezvous/relay family (tags 35–43): signed-record registration (Ed25519, TTL,
> trust-on-first-use identity binding, per-IP quota), paged discovery, HMAC relay reservations, and a
> tokio circuit bridge that meters bytes/duration/idle and tears down with a reason code. The Java
> `RendezvousPeerTransport` composes direct-first / relay-fallback behind the same `PeerTransport`
> seam (`SocketPeerTransport` stays the LAN path), with an X25519-ECDH + Ed25519-authenticated +
> AES-GCM `EndToEndCipher` so the relay forwards opaque bytes it can neither read nor forge, and a
> `TransportSelector` that prefers direct > punched > relayed. `RendezvousRelayIT` spawns the **real
> binary** and drives two relay-only Java peers through it: they register, discover each other, and a
> `PeerJoin` + `SessionKeepAlive` cross the E2E-encrypted circuit byte-exact; exhausting the
> reservation's byte ceiling tears the circuit down; and the selector reports the direct path when one
> is available (the punch-upgrade policy). Remaining gap, riding the same live/NAT env as Task 10's
> migration runs rather than this ledger: a real cross-internet soak and the pure-relay
> `SessionContinuityIT` wiring (Task 10 acceptance #1) — the mechanism is proven headlessly and over
> loopback here; the live-internet numbers land with the Task 10 live pass.
>
> **L-29 retired (Task 9, 2026-07-18).** `GatewayElection` is now capability-weighted (Plan §3.5):
> within a tier the peer with the highest pure-integer weight (cores + GiB memory + inverse
> latency + reliability, each clamped to a bucket) wins; the rendezvous score only spreads duty
> among equal-weight peers; the bootstrap-preference and deterministic UUID tie-break are
> unchanged, so the order-independence property test still holds. Verified by
> `GatewayElectionTest.capabilityWeightIsBoundedPureIntegerMath` and `mostCapablePeerWins*`.

> **L-48 retired / L-30 retiring (issue #30 goal pass, 2026-07-21).** The previously
> runtime-unreferenced `engine` validation stack is wired into the worker:
> `dev.nodera.peer.validation.WorkerValidationService` runs `CommitteeMember` re-execution
> **out-of-game** and participates in quorum over the same `PeerTransport` the membership session
> rides, consuming the previously unconsumed `simulationmsg` wire family
> (`ActionBatchMsg`/`RegionProposal`/`ValidationVote`/`CommitAnnounce`).
> `WorkerQuorumValidationIT` proves the L-48 exit: three **companion-only** worker nodes (no
> Minecraft process) form a committee over `LoopbackTransport`-registered `PeerRuntime`s — the
> primary proposes, validators re-execute with THE engine and vote over the wire, the 2-of-3
> quorum commits, every worker converges on the byte-identical root **matching the reference
> engine**, and each persists the co-signed `QuorumCertificate` in its own `CertificateStore`
> (certified state flowing peer-to-peer). Primary loss promotes a validator under epoch+1
> (`CommitteeFailover`) and the surviving 2-member committee keeps committing. The fallback lane
> (`FallbackRouter`/`FallbackExecutor` — the formerly orphaned Task-8 runtime) executes an
> unassigned-region action through the server lane and the Phase-4 soak ratio holds.
> `HeadlessPeerMain` constructs the lane and the worker `STATE` JSON now reports live
> `validation` counters (additive field; the Tauri parser uses serde defaults). L-30 stays
> RETIRING: the T9 forward event-sync half (`PeerSyncFlow` over the transport) is the remaining
> exit clause.

> **L-45 opened / decentralization (Task 30, 2026-07-20).** The first Task 30 increment finishes Plan
> Phase 5's "demote the server" on the mod side: the `Dist.DEDICATED_SERVER` gate is removed, so a
> player's integrated server runs the same host lane and shares a world from the pause-menu "Share to
> Nodera" button; `NoderaPeerService` is role- not dist-driven; `scripts/dev.sh` no longer installs or
> runs a Minecraft server. This is proven headlessly (`ShareOptionsTest`) + compile-clean against
> NeoForge 21.1.77. What is NOT yet automatable is the live GUI flow itself — hence L-45 OPEN. The
> live production it fronts (genesis-from-existing-world + self-cert, the password-change re-manifest,
> `RendezvousPeerTransport` composition, per-piece encryption) rides the same NeoForge live lane as
> Tasks 5–8/19/23/26 and stays deferred with them; **L-20 is unchanged** (genesis is still a
> single-signer trust root — now the hosting player's identity rather than the dedicated server's —
> with T16 owning multi-party re-certification).

## §C — Retired by assumption A0 (every player is a peer)

| ID | Former limitation | Why void |
|---|---|---|
| R-1 | Vanilla clients cannot join / are refused at handshake | Not a limitation: there is no vanilla-client population by definition. The refusal is the enforcement of A0. |
| R-2 | Actions by session-less players skipped by capture | Session-less players do not exist under A0; the defensive skip remains as a bug counter (`sessionlessActors`, expected 0). |
| R-3 | Gateway migration served "modded peers only"; vanilla players stranded when the full peer is down | Void: all players are modded peers; migration (T10) + local-replica view (T16) covers the whole population. |

---

## Reading guide for the implementing model

- Build the current task **as specified**; do not implement a §B entry early, but do not
  build anything that structurally blocks its owner task (same discipline as the
  Invariants in `Plan.md` §8).
- When a design choice trades against a §B entry, prefer the choice that keeps the exit
  test achievable.
- If you believe an entry is genuinely impossible (not merely hard), do not silently
  drop it: file it as a challenge against this register with the physical argument —
  the register only accepts reclassification to §A with a hiding mechanism, never
  deletion.
