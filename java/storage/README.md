# `java/storage`

<!-- AI-AGENT-INSTRUCTION: Canonical state is the CERTIFIED EVENT LOG plus checkpoints — never a
     mutable world snapshot. Do NOT add a second record of a region's head that could disagree with
     the log after a crash: heads are RECOVERED from the log tail on open, deliberately. Blob writes
     are atomic temp-and-move; a partially written blob must never be readable under its final name.
     Crash tests use real forced process kills. Update this file when a package is added or its
     responsibility changes. -->

**Canonical state as a certified, replayable log — not as "whatever the server currently has in
memory".**

- **Depends on:** `core`.
- **Depended on by:** `peer`, `neoforge-mod`.
- **Docs:** [`docs/network/Task.3.md`](../../docs/network/Task.3.md)

---

## Architecture

```
dev.nodera.storage            the WorldStore seam + ContentId/Checkpoint/GenesisManifest,
│                             CertifiedWorldGenesis, WorldIdentity/WorldPermissionGrant/
│                             WorldPermissions/WorldRole, EventChainGuard, RegionOrder
├── io/                       atomic file primitives
├── event/                    in-memory event-sourced impl, EventReplayer, PeerSyncFlow
├── rocksdb/                  RocksWorldStore over WAL-backed column families
│                             (events / checkpoints / certificates / regions / meta)
│                             + FsContentStore — content-addressed blobs
└── client/                   BoundedClientWorldStore, StorageQuotaManager,
                              ArchiveEvictionPolicy (a player's byte budget)
```

## Why it is shaped this way

**Event sourcing, because the alternative cannot be verified.** A mutable snapshot has no history to
check; a certified log can be re-walked by anyone and every link re-verified. That is what makes a
peer's state *provable* rather than merely present — and it is why the replayer refuses an uncertified
suffix instead of trusting the most recent bytes it received.

**Heads are recovered from the log tail, never stored separately.** Writing "the head is version N"
alongside the log creates two records that can disagree after a crash, and the disagreement is silent.
Recovering the head by reading the tail is slightly slower on open and impossible to get wrong.

**Content addressing makes replication safe.** A blob's filename is its hash. Two peers holding "the
same" blob hold byte-identical bytes by construction; a corrupt blob fails its own read; and a file
already present at a destination during a move is provably a duplicate rather than a conflict — which
is what makes relocating an archive directory safe rather than destructive.

**Atomic temp-and-move.** The rename is the commit point. A half-written blob under its final name
would be indistinguishable from a valid one until something hashed it.

**Three tiers, one seam.** An archival peer wants RocksDB, a player's client wants a byte budget, and
a test wants memory. Writing consumers once against `WorldStore` means the durability tests run
against the real tier.

**One hard eviction rule.** The client tier may evict anything **except** the current state of an
assigned region — the one thing whose loss the committee cannot repair from elsewhere. When only
pinned data remains it raises a loud error rather than quietly evicting what matters, and every
eviction signals repair so redundancy is restored elsewhere.

## Tests

102 tests: durable seam parity across close and reopen (including head-recovery-fed validation),
checkpoint ordering, content-addressed certificate idempotency, corrupt-blob read rejection, the
forced-kill `RocksCrashRecoveryIT`, certified forward sync with an uncertified tail refused, and
`FsContentStoreRelocationTest` (blobs survive relocation and a **reopened** store finds them).

```bash
./gradlew :storage:test
```
