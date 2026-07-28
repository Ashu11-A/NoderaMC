# Worker — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the worker category. "Permanent" is banned. Every §B row has
     an owning task and an EXIT TEST and retires only when that exit test is green. Never delete a
     row — move it to LIMITATIONS.fixed.md with its evidence. §C lists properties that are the design
     working correctly; do not convert them into §B rows. -->

**Category:** worker · **Last audit:** 2026-07-27 · Open or retiring rows: **7**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

None owned. The worker exists precisely to remove an envelope constraint that used to be real — "a
node lives only while its player has Minecraft open" — rather than to hide one.

---

## §B — Staged capabilities

Four rows opened on 2026-07-27 by the world-duplication audit ([`Task.8.md`](Task.8.md)). The
category's previous staged row, L-41, retired on 2026-07-26 — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| W-FETCH-1 | **A world archive being downloaded could be evicted mid-transfer.** The retention policy (`supersedeOlderVersions`, L-55) runs when this node *learns* a newer version exists. On a live world the host streams a new archive version every couple of minutes, so a joiner fetching a large archive had the manifest root its `PieceDownloader` was writing into unpinned and unpublished underneath it — the download could never complete, and the player sat on "Migrating world…" until the fetch deadline. Observed live: 913 KiB downloaded, 0 pieces retained. Fixed by making an in-flight root eviction-proof for the duration of the fetch | [8](Task.8.md) | `FetchSurvivesSupersessionTest` green **and** a live join to a world whose host is playing | RETIRING — headless proof green, live re-run outstanding |
| W-REPL-3 | **A peer offered manifests for content it held nothing of.** `answerManifestQuery` returned every version this node knew about, so a peer that had merely *heard* of a version advertised it as fetchable; the asker picked the highest, requested its pieces, and got silence. Fixed: only versions this node holds at least one piece of are offered. Paired with a `requestsForUnknownContent` counter, because a request for an unknown root was previously dropped with no log, no reply and no metric anywhere — which is why this took four live rounds to find | [8](Task.8.md) | `ArchiveFetchOverSocketsIT` (2) green over real TCP | RETIRING |
| W-REPL-1 | **A world this node claimed but held nothing of was never repaired.** The replication sweep skipped any world where `holdsCompletely(...) || hosts(...)` — but `hosts()` reads the registry claim, not the content store, and `restoreFromRegistry` reloads every row as hosted at boot whether or not the bytes survived. So a node announcing a world it could not serve was disqualified from fetching it, permanently and self-perpetuatingly. Observed live: "Yours — hosted here · 0.0% · 0 of 73 pieces" beside "3 peers holding it besides this node". Fixed: the skip is about content held, and a claim with nothing behind it is repaired ahead of placement and bounds | [8](Task.8.md) | `ReplicationRepairsEmptyClaimsTest` green (verified failing without the fix) **and** a live node at 0% reaching 100% within one sweep | RETIRING |
| W-REPL-2 | **Supersede-eviction destroyed the only servable copy of a plaintext world.** Learning that a newer archive version existed evicted every older one — including the copy this node was serving — in exchange for a manifest it had never held. With the host's game closed, no peer held that newer version either, so every peer offered each other a version none of them had and `ContentTransferService.serve` answered each request with silence. Whole swarms reached `0/73 pieces`. **Fixed** by scoping the rule to what it protects: eviction applies to **encrypted** versions only, because a password rotation is the only thing it can revoke and a plaintext archive has no password. Plaintext versions are bounded by the retention window instead. The L-55 fixtures were plaintext while documenting themselves as the re-key case, which is how the mismatch survived | [8](Task.8.md) | `SupersededManifestEvictionTest` (encrypted fixtures) + `ArchiveFetchOverSocketsIT#onlyHeldVersionsAreOffered`, both green | RETIRING |
| W-DUP-1 | A registry row is never reconciled against the world still existing, so a stale entry is re-announced on every worker start, forever | [8](Task.8.md) | A row whose world this node can no longer serve stops being announced within one refresh cycle | OPEN |
| W-DUP-2 | `stop` removes the in-memory world that held the ownership record, so a later `seed` recreates it unowned — an administered world reads as merely supported | [8](Task.8.md) | Stop then re-seed a world whose `.worldkey` exists on disk; the registry row still reads owned | OPEN |
| W-DUP-3 | `LocalFiles.writeAtomically` passes POSIX permissions to `createTempFile` and catches only `IOException`, so on a non-POSIX filesystem every worker-owned write throws — including the node identity, before anything else runs | [8](Task.8.md) | The worker boots and writes `worlds.dat` on a filesystem without POSIX permissions | OPEN |
| W-DUP-4 | Registries that already hold duplicate rows for one save are not repaired; the fix stops them growing | [8](Task.8.md) | A one-shot merge tool leaves one row per save, with the surviving id chosen by the persisted `nodera-world.dat` | OPEN |

---

## §C — By design (not limitations, and must not be "fixed")

<!-- AI-AGENT-INSTRUCTION: Do NOT convert these into §B rows. Each is a locked decision or the trust
     model working correctly. A proposal that adds a second region engine, or that makes the control
     channel remotely reachable, is a design regression and must be refused. -->

| Property | Why it is not a limitation |
|---|---|
| The worker is a Java process, not a lightweight Rust daemon | Option B is **locked**. A Rust-native peer is forbidden from re-executing regions by the single-engine determinism rule — a second engine implementation would have to stay bit-identical forever. A Rust seed/relay/route-only mode remains a possible later addition; it can never be a validator |
| Minecraft refuses to start without the worker | This is the gate working ([`../minecraft/Task.7.md`](../minecraft/Task.7.md)). The alternative — a game that runs while its node does not — is the failure mode the whole category exists to remove. The gate fails **closed with an actionable message**, never a stack trace or a silent no-network degrade |
| The control channel is unauthenticated | It is **loopback-only** local IPC. Binding it wider would turn it into a remote control plane for the node; the boundary is the bind address, and it is not negotiable |
| Requiring the worker does not make it trusted | Peers verify everything it serves by hash and signature. Requiring it locally is a persistence and reachability convenience, never a new trust anchor |
| The worker holds a world's signing key | It **is** the author for the worlds it hosts. That is why author-only re-key is a cryptographic statement rather than a UI convention. Single-signer genesis is a separate concern, retired in [`../engine/LIMITATIONS.fixed.md`](../engine/LIMITATIONS.fixed.md) (L-20) |

---

## Reading guide for the implementing model

- The governing rule: **no second region engine, in any language, ever.**
- Never bind the control listener to a routable interface.
- A world must never be *announced but unserved*: announce after content is seedable, not before.
- When a design choice trades against the §B row, prefer the choice that keeps the exit test
  achievable.
