# Network Task 3 — Event-Sourced + Durable Storage

<!-- AI-AGENT-INSTRUCTION: Canonical state is the CERTIFIED EVENT LOG plus checkpoints — never a
     mutable world snapshot. Do not add a second record of a region's head that could disagree with
     the log after a crash; heads are RECOVERED from the log tail on open, on purpose. Keep this
     header's status accurate. -->

**Status:** ✅ COMPLETED (live forward sync + manager wiring → [minecraft 2](../minecraft/Task.2.md))
**Category:** network · **Owns:** — · **Last audit:** 2026-08-10
**Depends on:** [network 1](Task.1.md), [engine 1](../engine/Task.1.md)
**Consumed by:** [network 4](Task.4.md), [network 9](Task.9.md), [worker 2](../peer/Task.2.md), [minecraft 5](../minecraft/Task.5.md)

---

## Goal

Demote the server. Canonical state is **genesis + an append-only certified event log + checkpoints +
content-addressed blobs**, not "the server's `ServerLevel`". A new peer catches up by replaying a
certified chain, and an uncertified suffix can never advance it.

## Status detail

Complete. `library/java/storage` carries 158 tests. Three tiers behind one `WorldStore` seam: an in-memory
event-sourced implementation, a **RocksDB archival tier** with WAL-backed column families, and a
byte-budgeted client tier. `FsContentStore` provides content-addressed blobs with atomic
temp-and-move writes and hash-verified, corrupt-blob-rejecting reads. Per-region heads are recovered
from the log tail on open, so there is **no second record that can disagree with the log after a
crash**.

`RocksCrashRecoveryIT` forcibly kills a writer JVM mid write-storm and reopens clean: contiguous ids,
an unbroken `prevRoot → resultingRoot` chain, and a live head.

Forward sync exists over the wire (`EventSyncQuery`/`EventSyncAnswer` + `EventSyncService`), proven
by `EventSyncOverTransportIT`: a fresh peer pulls certified events and their certificates over the
`PeerTransport`, replays the chain, converges on the certified root, and an **uncertified tail never
syncs**.

Also here: the signed world-identity and permission types (`WorldIdentity`, `WorldPermissionGrant`,
`WorldPermissions`, `WorldRole`) and the host-signed `CertifiedWorldGenesis`.

## Dependencies

- [network 1](Task.1.md) — the wire that carries sync.
- [engine 1](../engine/Task.1.md) — certificates and canonical encoding.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `WorldStore` seam + `ContentId`/`Checkpoint`/`GenesisManifest` | ✅ |
| 2 | In-memory event-sourced implementation | ✅ |
| 3 | `EventReplayer` — certified-chain walk | ✅ |
| 4 | `PeerSyncFlow` + `EventSyncService` — forward sync | ✅ |
| 5 | RocksDB archival tier (`RocksWorldStore`) + `FsContentStore` | ✅ |
| 6 | `BoundedClientWorldStore` + quota manager + eviction policy | ✅ |
| 7 | `WorldIdentity` / `WorldPermissionGrant` / `WorldPermissions` / `WorldRole` | ✅ |
| 8 | `CertifiedWorldGenesis` | ✅ |
| 9 | Live forward sync on a real mesh + chunk-meta attachments | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Event sourcing, because the alternative cannot be verified.** A mutable snapshot has no history to
check; a certified log can be re-walked by anyone and every link re-verified. That is what makes a
peer's state *provable* rather than merely present, and it is why the replayer refuses an uncertified
suffix instead of trusting the most recent bytes it received.

**Heads recovered from the log tail, not stored separately.** Writing "the head is version N"
alongside the log creates two records that can disagree after a crash — and the disagreement is
silent. Recovering the head by reading the tail is slightly slower on open and impossible to get
wrong.

**Content addressing is what makes replication safe.** A blob's filename is its hash. Two peers
holding "the same" blob hold byte-identical bytes by construction, a corrupt blob fails its own read,
and a file already present at a destination during a move is provably a duplicate rather than a
conflict.

**Atomic temp-and-move writes.** A partially written blob must never be readable under its final
name; the rename is the commit point. `AtomicFileWriter.writeOwnerOnly` additionally creates POSIX
temps as `0600` before content, fails closed on a lying provider, and deletes temps after failed
writes/moves while preserving cleanup errors as suppressed exceptions. Android may deny
`Files.getFileStore` even in app-private storage; that case attempts `0600` creation directly and
fails without writing when the provider rejects secure creation attributes; it never falls back to
an unrestricted secret-bearing temp file.

**Three tiers, one seam.** An archival peer wants RocksDB, a player's client wants a byte budget, and
a test wants memory. They implement the same `WorldStore` interface so every consumer is written once
and the durability tests run against the real tier.

## Files

- `library/java/storage/src/main/java/dev/nodera/storage/WorldStore.java`
- `library/java/storage/src/main/java/dev/nodera/storage/event/{EventReplayer,PeerSyncFlow}.java`
- `library/java/storage/src/main/java/dev/nodera/storage/rocksdb/RocksWorldStore.java`
- `library/java/storage/src/main/java/dev/nodera/storage/fs/FsContentStore.java`
- `library/java/storage/src/main/java/dev/nodera/storage/client/{BoundedClientWorldStore,StorageQuotaManager,ArchiveEvictionPolicy}.java`
- `library/java/storage/src/main/java/dev/nodera/storage/io/AtomicFileWriter.java`

## Testing

- Durable seam parity across close and reopen, including head-recovery-fed validation.
- Checkpoint ordering; content-addressed certificate idempotency; corrupt-blob read rejection.
- `RocksCrashRecoveryIT` — a forcibly killed writer JVM reopens with a contiguous, unbroken chain.
- `EventSyncOverTransportIT` — certified forward sync; an uncertified tail never advances a peer.
- `FsContentStoreRelocationTest` (5) — every blob survives a relocation, a store **reopened** on the
  new directory finds the content, same-directory is a no-op, and an identical blob already at the
  destination is merged rather than refused.
- `AtomicFileWriterTest` (4) — owner-only creation including denied store inspection, temp deletion
  after failed replacement, and suppressed cleanup failure.

## Acceptance criteria

1. ✅ Canonical state is genesis + certified log + checkpoints + content blobs.
2. ✅ An uncertified suffix never advances a peer.
3. ✅ A forcibly killed writer reopens clean with an unbroken chain.
4. ✅ Corrupt blobs fail their own reads.
5. ✅ The client tier never evicts an assigned region's current state, and eviction signals repair.
6. ⏳ Live: new-peer forward sync on a real mesh. **2026-08-10:** the run that would satisfy
   this is `nodera-test run mesh-soak`, which until now neither asserted the clause nor drove
   anything a committee could see — its block load was `/fill`, a direct world write that fires
   no capture event (see [#160](https://github.com/Ashu11-A/NoderaMC/issues/160) and the L-30
   row). The scenario now drives `/nodera debug drive` and asserts two workers' non-zero
   `committee_commits` in stage S2b; the run itself is still outstanding.

## Limitations

None open. **L-58** (archive directory was restart-only) is RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
