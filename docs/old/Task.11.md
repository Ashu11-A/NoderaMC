# Task 11 — World-Interference Control, Chunk Lifecycle, Delegability & Mod Compatibility

**Phase:** hardening for 2–4 (hard prerequisite of Task 8 on non-flat worlds) ·
**Depends on:** Task 6 · **Modules:** `neoforge-mod`, `protocol`, `core`,
`integration-tests`

## Goal

Close the two correctness holes found in review and define the mod-compatibility
contract:

- **Hole A**: non-player mutations inside delegated regions (random ticks, fluids,
  gravity blocks, fire, mobs, fake players, other mods) currently bypass the engine,
  desync replicas, and turn CAS failures into resync storms. After this task, a
  delegated chunk is mutated only by `WorldMutationApplier`; every other write is
  **suppressed** or **converted** into a certified `ExternalDelta`.
- **Hole B**: vanilla-lane mechanics bleeding across the lane boundary (piston from a
  vanilla region pushing into a delegated region). Closed structurally by the
  neighbor-ring delegability rule plus the same guard.

Also: chunk lifecycle becomes explicit (tickets keep delegated chunks loaded; unload
revokes — covers chunk loaders and ender-pearl-loaded chunks), `DelegabilityPolicy`
gains its full rule set with hysteresis, and `COMPATIBILITY.md` fixes the contract other
mods can rely on. Tuning input: the Task 5 `InterferenceProbe` report.

---

## Folder structure (additions)

```
neoforge-mod/src/main/java/dev/nodera/mod/
├── mixin/
│   ├── LevelChunkMixin.java           # setBlockState guard on delegated chunks
│   ├── ServerLevelMixin.java          # (extends Task 5 file) random-tick skip for delegated chunks
│   └── LevelTicksMixin.java           # scheduled block/fluid ticks targeting delegated chunks:
│                                      #   suppress + count (engine owns region semantics)
├── dedicated/
│   ├── interference/
│   │   ├── MutationGuard.java         # applier-context gate + write classification; STRICT|CONVERT
│   │   ├── InterferenceBuffer.java    # per-region, per-tick collected foreign mutations
│   │   ├── InterferenceCommitter.java # tick-end: buffer → ExternalDelta → version bump → replicas
│   │   └── InterferenceStats.java     # rates per region per source class; feeds DelegabilityPolicy
│   ├── coordinator/
│   │   ├── DelegabilityPolicy.java    # (Task 6 file grows) full reason set + re-evaluation loop
│   │   ├── ChunkTicketService.java    # custom TicketType NODERA_DELEGATED; hold while lease active
│   │   └── ChunkLifecycleHooks.java   # (Task 6 stubs made real) load/unload ⇒ policy + tickets
│   └── compat/
│       └── FakePlayerDetector.java    # FakePlayer subclass OR player without handshake session
└── (repo root) COMPATIBILITY.md       # the written mod-compat contract

core additions (append-only type tags):
└── consensuscert/ServerAuthorityCertificate.java
      # record: region, baseVersion, resultingVersion, resultingRoot, Reason, serverSignature
      # Reason enum: EXTERNAL_MUTATION, UNASSIGNED, NON_DELEGABLE, CROSS_REGION,
      #              DISPUTED, COMMITTEE_COLLAPSED, SPOT_CHECK
      # (defined HERE with the full enum; Task 8 consumes the non-EXTERNAL reasons)

protocol additions:
└── simulationmsg/ExternalDelta.java   # region, baseVersion, encodedDelta, certificateBytes
                                       # clients apply WITHOUT voting after verifying certificate
```

## Class relationships

```
MutationGuard (static gate, server main thread)
    ├─ applier context: scoped flag set by WorldMutationApplier around its apply pass
    │     (ScopedValue<Boolean> APPLIER_CONTEXT; fallback ThreadLocal if pinned to 21 LTS)
    ├─ source context: pushed markers around vanilla phases —
    │     ENTITY (entity tick loop), SCHEDULED (LevelTicks run), NEIGHBOR (neighbor updates),
    │     UNKNOWN (default)
    └─ verdict(chunk, pos, prev, next):
          not delegated chunk        → PASS (vanilla lane untouched)
          applier context            → PASS
          mode STRICT                → BLOCK (cancel write, log, count)
          mode CONVERT (default)     → PASS + record(pos, prevId, newId, source) → InterferenceBuffer

LevelChunkMixin.setBlockState  ──► MutationGuard.verdict   # the single choke point
ServerLevelMixin (random ticks) ──► skip delegated chunks entirely (suppression, not conversion)
LevelTicksMixin (scheduled)     ──► drop ticks targeting delegated chunks + count
                                     (MVP palette schedules nothing; anything arriving is
                                      boundary bleed — counted as NEIGHBOR interference)

InterferenceCommitter (server tick end)
    for each region with non-empty buffer:
        pipeline idle (ACTIVE)?  → build RegionDelta(expectedPrev from recorded prevId)
                                   → ServerAuthorityCertificate(EXTERNAL_MUTATION)
                                   → version++ → broadcast ExternalDelta → replicas advance
        pipeline busy (AWAITING_*/VOTING)? → hold buffer; commit after the decision,
                                   BEFORE the next batch is assembled (ordering rule:
                                   batch N+1's baseVersion always includes prior interference)

DelegabilityPolicy (full set, re-evaluated every lease renewal + on lifecycle events):
    UNSUPPORTED_PALETTE | CHUNKS_NOT_LOADED | NO_ELIGIBLE_NODES | CROSS_REGION_PENDING |
    OUTSIDE_GENERATED_TERRAIN                     (Task 6)
    ENTITY_PRESENT      — any entity in region bounds (narrowed by Task 12)
    NEIGHBOR_UNSUPPORTED— any region in the DELEGABLE_NEIGHBOR_RING fails palette check
    FAKE_PLAYER_ACTIVE  — FakePlayerDetector hit within cooldown window
    INTERFERENCE_RATE_HIGH — InterferenceStats rate > INTERFERENCE_REVOKE_RATE
    + DELEGABILITY_COOLDOWN_TICKS hysteresis both directions (no flapping)

ChunkTicketService
    lease issued   → addRegionTicket(NODERA_DELEGATED) covering region + halo chunks
    lease revoked  → release tickets (chunks may unload normally afterwards)
    foreign tickets (ender pearl, portal, other mods' loaders) are NEVER cancelled —
    Nodera only adds/removes its own
```

## Implementation details — NeoForge mod

- **Why CONVERT is the default, not STRICT**: cancelling an arbitrary vanilla write
  mid-mechanic (piston already committed its move, entity already changed state)
  corrupts vanilla invariants Nodera doesn't control. CONVERT lets the write land and
  immediately folds it into the version chain as a certified external event, so
  replicas stay root-consistent. STRICT exists for debugging and for CI determinism
  runs (`interference.mode=STRICT`).
- **Suppression vs conversion split**: suppress what is *safe to not happen* in a
  delegated region (random ticks, scheduled ticks — the engine owns those semantics
  per Plan §3; on the MVP palette they are no-ops anyway). Convert what *already
  happened* (any setBlockState reaching the choke point). Never both for the same
  write.
- **Interference ordering vs in-flight batches**: rule stated above (interference
  commits only between batches). Consequence: a client proposal never races an
  interference version bump; `STALE_BASE` rejections from interference are impossible
  by construction. Test this explicitly (injected interference during VOTING state).
- **Chunk loaders / ender pearls (the Task-analysis item)**: loader-held or
  pearl-loaded chunks with no nearby session player were already unreachable by
  assignment (proximity-based); now it is explicit policy: such regions are vanilla
  lane, the guard does not touch them, and their foreign tickets are respected.
  The only Nodera/loader interplay is `ChunkTicketService` bookkeeping isolation
  (own-ticket-type only). Covered by a test with a pearl-loaded chunk.
- **`ServerLevel` unload interplay**: with tickets held, unload of a delegated chunk
  cannot happen through normal paths; the Task 6 unload-revoke hook remains as the
  belt-and-suspenders path (forced unloads, dimension unload, `/kick`-style admin ops).
- **Mixin discipline** (Task 1 rule): each of the three mixins carries a header comment
  "why an event was not enough". `LevelChunkMixin` is the only write choke point —
  no scattering guards across callers.

## Implementation details — server peer

- `WorldMutationApplier` wraps both passes in the applier scope
  (`MutationGuard.applierScope(() -> ...)`) — the only code that may write delegated
  chunks. Assertion + counter for guard hits from within applier scope (must be the
  total of applied mutations, sanity check).
- `InterferenceStats` → `/nodera interference` (extends the Task 5 command): per region
  per source class, rolling rates, current mode, revocation events.
- `ExternalDelta` client handling: verify `ServerAuthorityCertificate` signature +
  reason, apply to replica without voting, advance version. Shared code path with
  `CommitAnnounce` replica advancement (one applier on the client too).

## Implementation details — `COMPATIBILITY.md` (normative content)

1. **Event ordering**: Nodera captures/cancels at `EventPriority.LOW`,
   `receiveCanceled=false`. Protection mods at any earlier priority always win.
2. **Fake players**: never become Nodera actors; their effects are external mutations.
3. **Unknown blocks**: any block outside the Nodera palette makes its region (and its
   neighbor ring) non-delegable — other mods' machines simply keep vanilla semantics.
4. **Redstone**: excluded from validated lanes by palette until Task 13; contraptions
   run pure vanilla.
5. **Chunk tickets**: Nodera adds/removes only its own `NODERA_DELEGATED` ticket type.
6. **What other mods must not do**: mutate delegated chunks from async threads
   (undefined even in vanilla; the guard converts main-thread writes only — async
   writes are logged as errors), and depend on block changes in delegated regions
   applying in the same tick as their cause (1–2 tick commit latency is normative).

## Acceptance criteria

1. **Guard conversion**: scripted foreign `setBlock` (simulating another mod) into a
   delegated region ⇒ CONVERT: `ExternalDelta` committed, all replica roots match the
   re-extracted world root afterwards; STRICT: write blocked + logged, world unchanged.
2. **Suppression**: normal-world delegated region (grass, dirt) idles 10k ticks ⇒ zero
   random-tick mutations, zero resyncs, stable root (compare vs pre-Task-11 baseline
   where the Task 5 probe showed non-zero interference).
3. **Ordering**: interference injected while pipeline is in VOTING ⇒ held, committed
   after decision, next batch bases on the post-interference version; no STALE_BASE.
4. **Tickets**: player walks away from their delegated region ⇒ chunks stay loaded
   while lease active; revoke ⇒ tickets released ⇒ chunks unload. Pearl-loaded chunk
   test: region never delegated, pearl ticket untouched, chunk keeps ticking vanilla.
5. **Delegability dynamics**: mob wanders in ⇒ `ENTITY_PRESENT` revoke within one
   renewal window, graceful (in-flight resolved first); mob leaves ⇒ redelegable only
   after `DELEGABILITY_COOLDOWN_TICKS`; interference storm ⇒ `INTERFERENCE_RATE_HIGH`
   revoke ⇒ quiet period ⇒ auto-restore. No flapping under an oscillating scenario
   (test with scripted entity in/out).
6. **Neighbor ring**: placing a redstone torch in a region adjacent to a delegated
   region ⇒ neighbor turns `NEIGHBOR_UNSUPPORTED` on next evaluation ⇒ revoked; piston
   boundary-bleed scenario from the review (vanilla piston pushing into delegated
   space) is now impossible to set up — integration test proves the setup itself gets
   demoted first, and the guard converts anything that slips through a race window.
7. **Compat contract**: dummy protection-mod listener test (from Task 6 acceptance)
   re-run green; `COMPATIBILITY.md` committed and referenced from `Plan.md`.
8. Task 8's soak prerequisites satisfied: hand-off note records guard overhead
   (ns/write at the choke point, JMH) and steady-state interference rates.
