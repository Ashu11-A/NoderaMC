# Task 7 — Committee Validation (Phase 3): Votes, Quorum, Equivocation, Failover — MVP Gate

**Phase:** 3 · **Depends on:** Task 6 · **Modules:** `consensus`, `neoforge-mod`,
`protocol`, `testkit`, `integration-tests` (new)

## Goal

Replace 100% server re-execution with committee validation: **primary + 2 validators**
execute every batch; server collects `ValidationVote`s, forms a **2-of-3 quorum** on the
resulting state root, commits, and only **spot-checks** a sample itself. Primary failover
under a new epoch. This task ends at the **MVP gate** — the canonical three-client
scenario from `Plan.md` §6 Phase 3.

---

## Folder structure (additions)

```
consensus/src/main/java/dev/nodera/consensus/
├── QuorumPolicy.java              # interface: int required(int committeeSize);
│                                  #   Decision evaluate(Proposal, Collection<SignedVote>)
├── MajorityQuorumPolicy.java      # 2-of-3 (MVP); parameterized for 3-of-4 later
├── VoteCollector.java             # per ProposalKey: gathers votes, timeout, yields Decision
├── Decision.java                  # sealed: Commit(QuorumCertificate) | Reject(reason) | Unresolved
├── EquivocationDetector.java      # same (node, region, epoch, version) + different root ⇒ flag
├── EquivocationRecord.java
└── SpotCheckPolicy.java           # deterministic sampling: recheck batch iff
                                   #   StableHash(region, version, serverSecret) % N == 0

neoforge-mod/src/main/java/dev/nodera/mod/dedicated/
├── coordinator/CommitteeAssembler.java   # builds RegionCommittee: 1 primary + 2 validators
│                                         #   (server itself eligible as validator seat)
├── commit/QuorumCommitService.java       # wires VoteCollector → WorldMutationApplier
└── validator/ServerValidatorWorker.java  # server acting as ordinary validator (one vote)

neoforge-mod client additions:
└── client/worker/ValidatorWorker.java    # VALIDATOR role: execute batch, send ValidationVote
                                          #   (root + decision + signature), keep replica advanced

integration-tests/            (new module; JUnit tags = scenario names)
├── build.gradle.kts          # depends on testkit + protocol + consensus; drives fake clients
└── src/test/java/dev/nodera/it/
    ├── ThreeClientQuorumIT.java
    ├── PeerDisconnectionIT.java
    ├── InvalidStateRootIT.java
    └── harness/
        ├── FakePeer.java             # headless protocol-speaking client (no Minecraft):
        │                             #   handshake, snapshots, engine execution, votes
        └── ClusterHarness.java       # boots dedicated server (GameTestServer or dev run)
                                      #   + N FakePeers; scripted actions; assertions
```

## Class relationships

```
CommitteeAssembler ── uses RendezvousPlacementPolicy (Task 6) with constraints:
    validators ≠ primary; distinct nodes; actor-only regions get ≥1 independent validator;
    server fills a validator seat when eligible nodes < 3 (config committee.serverSeat=true)

RegionPipeline (Task 6) states change:
    AWAITING_PROPOSAL ─proposal+votes─► VOTING ─Decision.Commit─► COMMIT ─► ACTIVE
                                          │ Decision.Reject / timeout
                                          ▼
                                   DISPUTED ─► ServerVerifier re-executes (authoritative tiebreak)
                                              ─► penalize the liar(s), resync, maybe reassign

VoteCollector (one per in-flight ProposalKey)
    ├─ inputs: RegionProposal (primary, counts as its own signed vote) + ValidationVotes
    ├─ QuorumPolicy.evaluate on each arrival
    ├─ timeout voting.timeoutMillis (default 1500) ⇒ Unresolved ⇒ DISPUTED path
    └─ output: Decision.Commit carries assembled QuorumCertificate (votes embedded)

EquivocationDetector — fed every root claim (proposals + votes), Caffeine-bounded history;
    hit ⇒ ReliabilityLedger.slash + committee removal + log/certificate for Task 9 storage.

ServerValidatorWorker / ValidatorWorker(client) — same role contract:
    interface RegionValidator { void onBatch(ActionBatch, RegionSnapshot ref); }
    both delegate to the ONE engine; server's vote carries no special weight (Invariant 2
    rehearsed early even though the server still owns commits until Task 9).
```

## Implementation details — server peer

- **`QuorumCommitService`**: subscribes proposal + vote messages (Task 4 dispatch
  table). On `Decision.Commit`: schedule `WorldMutationApplier` (unchanged from Task 6);
  broadcast `CommitAnnounce` (now including certificate bytes); store certificate
  in-memory ring buffer (persistence comes with Task 9 storage; for now
  `/nodera cert <region> <version>` can print the latest ones).
- **Spot-checks**: `SpotCheckPolicy` is **adaptive** per committee — 1/N sampling with
  N tied to the committee's minimum reliability: N=4 (25%) for new or suspect
  committees, N=8 default, N=64 (~1.6%) once reliability ≥ 0.99 is sustained. Selection
  stays deterministic (`StableHash(region, version, serverSecret) % N`). Verification
  cost decays toward zero instead of paying a fixed floor (ledger L-22; sampled batches
  re-executed by `ServerVerifier`). Spot-check mismatch against a quorum-committed root
  = **loud
  alarm** (means committee collusion or engine nondeterminism): log FATAL-level marker,
  freeze delegation for that region (back to server execution), dump fixture. Config
  `spotcheck.freezeOnMismatch=true`.
- **Failover (the MVP scenario)**: `HeartbeatMonitor` primary miss ⇒ `LeaseManager`
  promotes validator with highest reliability to primary, drafts replacement validator,
  `epoch++`, `RegionAssigned` to all members, pipeline → SNAPSHOT_SYNC (members already
  hold the replica at the committed version — snapshot sync is a version check, not a
  full stream, when roots match).
- **Batch fan-out**: `ActionBatchMsg` now goes to all 3 committee members (relay
  transport). Validators receive the primary's `RegionProposal` too? — **No** in MVP:
  validators vote on their *own* computed root; server compares. (Direct
  proposal-to-validator flow becomes relevant in Task 9/10 P2P mode.)

## Implementation details — NeoForge mod (client)

- `ValidatorWorker`: identical execute path as primary, different sink — sends
  `ValidationVote(decision=ACCEPT, root=own)` or
  `REJECT_INVALID_ACTION`/`RESYNC_REQUIRED` when the batch fails preconditions
  (version mismatch, bad signature — verified client-side too, defense in depth).
- Vote signature: Ed25519 over canonical `(region, epoch, version, resultingRoot,
  decision)` — exactly the `SignedVote.signedPortion()` from Task 2.
- HUD: role badge per region (P/V), vote latencies, last quorum outcome.

## Implementation details — integration harness (critical deliverable)

`FakePeer` makes CI-able what dev-client testing cannot: a plain-Java peer that speaks
the Task 4 protocol over a socket… **but** the NeoForge relay transport is Minecraft's
connection. Two options; implement (a):

  a. **Test transport**: `transport-api` gets a second impl `LoopbackTransport` in
     `testkit` (in-JVM message bus). `ClusterHarness` boots the *coordinator +
     consensus + engine* stack (all Minecraft-free: Tasks 2/3/consensus + a
     `FakeWorldAdapter` implementing the few server hooks — capture feed, applier sink)
     against N FakePeers over loopback. Real-Minecraft path stays covered by manual dev
     sessions + Task 5 soak.
  b. (rejected for now) genuine headless MC clients — too heavy.

This forces the Task 6/7 server logic to depend on interfaces (`WorldAdapter`:
`extractSnapshot`, `applyDelta`, `currentTick`) rather than `MinecraftServer` directly —
refactor `ShadowCoordinator`/pipeline accordingly (small, pays forever).

## Acceptance criteria — the MVP gate

1. **`ThreeClientQuorumIT`**: flat world, regions ≥ 4, peers A/B/C. A=primary of
   region (0,0), B/C validators. Scripted place action ⇒ all three roots equal ⇒
   2-of-3 quorum ⇒ delta applied ⇒ `CommitAnnounce` with valid certificate (signatures
   verify against A/B/C keys).
2. **`PeerDisconnectionIT`**: kill A mid-stream ⇒ B promoted under epoch+1 ⇒ next
   action commits with B as proposer; stale-epoch proposal from a resurrected A is
   rejected.
3. **`InvalidStateRootIT`** (byzantine): C votes a corrupted root ⇒ quorum still forms
   from A+B ⇒ C slashed in ReliabilityLedger; C alone can never commit (assert
   `QuorumPolicy` on 1-of-3).
4. Equivocation unit tests: two roots for one key ⇒ detected, slashed.
5. Manual dev-session replay of scenario 1–2 with real clients recorded in the
   verification log (video or log capture).
6. Spot-check alarm path tested (forced: corrupt server engine build flag).
