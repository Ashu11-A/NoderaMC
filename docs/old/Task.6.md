# Task 6 — Coordinator (Phase 2): Leases, Epochs, Assignment, Client Proposal + Server Verification

**Phase:** 2 · **Depends on:** Task 5 gate · **Modules:** `neoforge-mod` (dedicated),
`protocol`, `core`, `consensus` (skeleton)

## Goal

Turn shadow lanes into the real pipeline for delegated regions: the assigned **primary
client executes first** and submits a `RegionProposal`; the server re-executes,
compares roots, and **commits the client's delta to the real world** on match. Full
lease/epoch machinery, reassignment on failure, compare-and-set world application.
Server still verifies 100% of batches (committee voting is Task 7).

---

## Folder structure (additions)

```
neoforge-mod/src/main/java/dev/nodera/mod/dedicated/
├── coordinator/
│   ├── NodeRegistry.java            # authenticated nodes: identity, capabilities,
│   │                                #   reliability score, connection state
│   ├── RegionAllocator.java         # which regions are delegable + who runs them
│   ├── DelegabilityPolicy.java      # single evaluator: DELEGABLE | reason set (this task:
│   │                                #   palette, chunks loaded, eligible nodes; Task 11 adds
│   │                                #   entity/neighbor/fake-player/interference checks + tickets)
│   ├── ChunkLifecycleHooks.java     # ChunkEvent.Load/Unload wiring (stubs here, real in Task 11)
│   ├── RendezvousPlacementPolicy.java  # implements core RegionPlacementPolicy
│   ├── LeaseManager.java            # issue/renew/expire RegionLease; epoch bump on change
│   ├── HeartbeatMonitor.java        # misses ⇒ lease revoke ⇒ reassignment
│   └── ReliabilityLedger.java       # EMA success score per node (persisted Task 6 SavedData)
├── routing/
│   ├── ActionRouter.java            # replaces ShadowCoordinator routing: action → owning
│   │                                #   region's primary (or server fallback if not delegated)
│   └── RegionPipeline.java          # per-region state machine (see below)
├── commit/
│   ├── ProposalManager.java         # holds pending proposals; pairs with verification results
│   ├── ServerVerifier.java          # re-executes batch via engine (async), yields root
│   └── WorldMutationApplier.java    # THE ONLY world writer — main thread, compare-and-set
└── persistence/
    └── NoderaSavedData.java         # SavedData: networkId, assignments, epochs,
                                     #   reliability scores, last committed versions

core additions (region/):
└── RegionPlacementPolicy.java       # interface (canAssign + score) — declared in core, impl above

protocol additions: (already declared in Task 4 files)
    RegionProposal, CommitAnnounce, ResyncRequest now flow for real; add
    ProposalRejected.java            # region, epoch, baseVersion, reason enum

consensus (skeleton this task):
└── dev/nodera/consensus/
    ├── ProposalKey.java             # (region, epoch, baseVersion)
    └── VerificationOutcome.java     # MATCH / MISMATCH / TIMEOUT / STALE_EPOCH
```

## Class relationships

```
RegionPlacementPolicy (core, interface)
      ▲
      └── RendezvousPlacementPolicy      # StableHash(nodeId, regionId) × capability ×
                                         #   reliability × latency weights + constraints

RegionPipeline — per delegated region, single-threaded state machine (server main thread):
    IDLE ─assign─► SNAPSHOT_SYNC ─ack─► ACTIVE ─batch─► AWAITING_PROPOSAL
      ▲                                                    │ proposal
      │                                                    ▼
      │                                          AWAITING_VERIFICATION ── MATCH ──► COMMIT ─► ACTIVE
      │                                                    │ MISMATCH/TIMEOUT
      └──────────── REVOKED ◄── lease expiry ◄─────────────┘ (penalize, resync or reassign)

WorldMutationApplier.apply(RegionDelta) — server main thread:
    for each BlockMutation: currentStateId == expectedPreviousStateId ? setBlock : abort → resync
    (all-or-nothing per delta: dry-run pass over all mutations first, then apply pass)
```

Data flow (Phase 2 steady state):

```
player action ─► ActionCapture ─► ActionRouter ─► BatchAssembler(region)
   batch ─► primary client (ActionBatchMsg) ─► client executes ─► RegionProposal
   batch ─► ServerVerifier (async re-execute)          │
                └────────────► ProposalManager ◄───────┘
                                    │ roots equal?
                          yes ─► WorldMutationApplier (main thread) ─► CommitAnnounce
                          no  ─► ProposalRejected + ReliabilityLedger.penalize + resync
```

## Implementation details — server peer (the bulk of this task)

- **Delegability**: centralized in `DelegabilityPolicy.evaluate(region) →
  DELEGABLE | Set<Reason>`. Reasons implemented in this task: `UNSUPPORTED_PALETTE`,
  `CHUNKS_NOT_LOADED`, `NO_ELIGIBLE_NODES`, `CROSS_REGION_PENDING`,
  `OUTSIDE_GENERATED_TERRAIN`. Task 11 appends `ENTITY_PRESENT`,
  `NEIGHBOR_UNSUPPORTED` (1-region ring), `FAKE_PLAYER_ACTIVE`,
  `INTERFERENCE_RATE_HIGH` — the enum and the re-evaluation loop (every lease renewal +
  on lifecycle events, with `DELEGABILITY_COOLDOWN_TICKS` hysteresis) are declared
  **now** so Task 11 only adds evaluators. A region turning non-delegable revokes
  gracefully: in-flight proposal resolves first, then lease revoke (`epoch++`), then
  vanilla lane. Non-delegable regions keep vanilla execution untouched (vanilla is the
  default; delegation is opt-in per region).
- **Suppressing double execution**: for a *delegated* region, captured actions must not
  also apply vanilla-side. MVP approach: cancel the vanilla `BlockEvent` (place/break)
  for delegated regions and let the committed `RegionDelta` produce the effect 1–2
  ticks later; send the acting player a transient "pending" state (client mod may render
  a ghost block). This is the first behaviour-visible change — gate behind config
  `delegation.enabled=false` by default until Task 7 stabilizes.
- **Event-ordering contract (mod compatibility)**: the capture-and-cancel listener for
  delegated regions registers at `EventPriority.LOW` with `receiveCanceled=false` —
  protection/claim/anti-grief mods listening at higher priorities cancel first and
  Nodera never turns the action into an `ActionEnvelope`; their veto always wins. Other
  mods' `MONITOR` observers see the event canceled, exactly as for any server-side
  rejection. The Task 5 shadow listener stays passive at `MONITOR`. This contract is
  normative — document it in `COMPATIBILITY.md` (Task 11) and cover it with a
  dummy-listener test.
- **Fake players** (quarries, turrets, other mods' automation): any `Player` without a
  Nodera handshake session — including `FakePlayer` instances — never produces an
  `ActionEnvelope`. Their world effects inside delegated regions are foreign mutations,
  handled by the Task 11 interference guard. Until Task 11 lands, `DelegabilityPolicy`
  treats an active fake player in a region as `FAKE_PLAYER_ACTIVE` ⇒ non-delegable
  (cheap detection: any non-session player mutation event in the region within the last
  cooldown window).
- **Guard prerequisite**: config `delegation.requireGuard` (default `true`). While the
  Task 11 mutation guard is absent, delegation refuses any region that is not on the
  flat-world MVP profile (probe rate from Task 5 = 0). Prevents silently running the
  CAS-resync storm on normal worlds before the guard exists.
- **`LeaseManager`**: leases issued `LEASE_LENGTH_TICKS`, renewed on heartbeat every
  `LEASE_RENEW_TICKS` while pipeline healthy. Any reassignment ⇒ `epoch++` persisted in
  `NoderaSavedData` (epochs never reused, survive restart — stale-proposal defense).
  Messages carrying an old epoch are dropped with `STALE_EPOCH` outcome (test).
- **Chunk lifecycle hook points** (`ChunkLifecycleHooks`): `ChunkEvent.Load`/`Unload`
  listeners registered now. Unload of any chunk of a delegated region ⇒ immediate
  graceful revoke (`epoch++`, pipeline → REVOKED) — the applier must never face an
  unloaded chunk. Keep-loaded tickets (custom `TicketType`) and the full lifecycle
  policy (chunk loaders, ender-pearl-loaded chunks) land in Task 11; until then
  delegation is effectively limited to player-adjacent regions where vanilla view/sim
  tickets keep chunks loaded anyway.
- **`ServerVerifier`**: dedicated executor (`Executors.newFixedThreadPool(n, named)`,
  n = config `verifier.threads`, default 2). Never main thread. Timeout
  `verify.timeoutMillis` (default 2000): client proposal without server result in time ⇒
  TIMEOUT ⇒ treat as mismatch-lite (no penalty, resync, execute server-side).
- **`WorldMutationApplier`**: main thread (`server.execute` or tick-end hook). Two-pass
  (validate-all-then-apply) so a delta never partially commits (Invariant 11 in spirit).
  On abort: `ResyncRequest` path — fresh `SnapshotExtractor` run, version bump, region
  pipeline back to SNAPSHOT_SYNC. Uses `Level.setBlock` with flags that suppress
  neighbor-update cascades outside the MVP rule set (flag choice documented in-code;
  physics stays server-vanilla for non-delegated mechanics).
- **`ReliabilityLedger`**: `score ← 0.98·score + 0.02·outcome` per proposal
  (outcome 1 match / 0 mismatch); floor for assignment `minReliability` (default 0.95,
  config). Persisted.
- **`NoderaSavedData`**: `SavedData` attached to the overworld; `setDirty()` on every
  mutation; codec = canonical encoding of a `PersistedCoordinatorState` record (reuse
  Task 2 infrastructure, do not hand-roll NBT for consensus state; wrap bytes in NBT).

## Implementation details — NeoForge mod (client side)

- Client now signs its own `ActionEnvelope`s? — **No.** Action capture stays
  server-side (actions originate from vanilla packets the server already validated).
  What the client signs in Phase 2: its `RegionProposal` (`proposerSignature` over the
  canonical proposal minus signature). Server verifies against the registered public
  key before verification is even scheduled.
- `ShadowWorker` generalizes to `RegionWorker`: same execute loop, but for PRIMARY
  assignments it returns full `RegionProposal` (encoded delta included) instead of a
  root-only `ShadowResult`. Shadow lane (Task 5) remains available for non-primary
  regions — keep both code paths shared: `ResultSink` interface with `ShadowSink` /
  `ProposalSink` impls.
- Replica advancement: after `CommitAnnounce(version, root)` matching its own computed
  root — advance replica; on mismatch with own root — the client itself resyncs
  (defensive: server is authoritative in Phase 2).

## Acceptance criteria

1. Two-client dev session, `delegation.enabled=true`: block placed by player A in A's
   primary region appears in the world **via the commit path** (log-verified: capture →
   batch → proposal → verification MATCH → applier) with end-to-end latency ≤ 150 ms
   at BATCH_TICKS=2.
2. Kill the primary client: lease expires ⇒ region reassigned (second client or
   revoked to server) under `epoch+1`; in-flight proposal with old epoch rejected
   STALE_EPOCH (integration test).
3. Forced-mismatch build (client patched to corrupt one mutation): proposal rejected,
   reliability drops, region resynced, world state provably uncorrupted (root of
   re-extracted snapshot equals server-computed root).
4. Applier atomicity test: delta with a bad `expectedPreviousStateId` in the middle ⇒
   zero mutations applied.
5. Restart persistence: epochs and reliability survive server restart
   (`NoderaSavedData` round-trip test).
6. `/nodera regions` shows per-region: state machine state, primary, epoch, lease
   remaining, last commit version, delegability reason set.
7. Forced chunk unload of a delegated region (teleport all players away, flush unload)
   ⇒ lease revoked cleanly before unload completes; applier provably never writes to an
   unloaded chunk (assertion in applier + test).
8. Event-ordering test: dummy listener at `NORMAL` cancels a place event in a delegated
   region ⇒ no `ActionEnvelope` produced, no committee traffic, vanilla cancel semantics
   preserved. Fake-player mutation test: no envelope, region flips `FAKE_PLAYER_ACTIVE`.
