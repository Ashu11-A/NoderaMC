# Worker — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the worker category. "Permanent" is banned. Every §B row has
     an owning task and an EXIT TEST and retires only when that exit test is green. Never delete a
     row — move it to LIMITATIONS.fixed.md with its evidence. §C lists properties that are the design
     working correctly; do not convert them into §B rows. -->

**Category:** worker · **Last audit:** 2026-07-28 · Open or retiring rows: **3**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

None owned. The worker exists precisely to remove an envelope constraint that used to be real — "a
node lives only while its player has Minecraft open" — rather than to hide one.

---

## §B — Staged capabilities

Three rows, all owned by [Task 8](Task.8.md). The category's previous staged row, L-41, retired on
2026-07-26 — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). Two replication rows that were
opened here on 2026-07-27 (W-REPL-2, W-REPL-3) retired on 2026-07-28 once their headless exit tests
were confirmed green, and the registry-integrity bundle (W-DUP-1, W-DUP-2, W-DUP-4) retired on the
same date with its exit tests green. W-DUP-3 is the one row of that bundle that remains RETIRING:
component and production startup-seam zipfs proofs are green, but the exact launched-process exit
remains outstanding.

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| W-FETCH-1 | **A world archive being downloaded could be evicted mid-transfer.** The retention policy (`supersedeOlderVersions`, L-55) runs when this node *learns* a newer version exists. On a live world the host streams a new archive version every couple of minutes, so a joiner fetching a large archive had the manifest root its `PieceDownloader` was writing into unpinned and unpublished underneath it — the download could never complete, and the player sat on "Migrating world…" until the fetch deadline. Observed live: 913 KiB downloaded, 0 pieces retained. Fixed by making an in-flight root eviction-proof for the duration of the fetch | [8](Task.8.md) | `FetchSurvivesSupersessionTest` green **and** a live join to a world whose host is playing | RETIRING — headless proof green, live re-run outstanding |
| W-REPL-1 | **A world this node claimed but held nothing of was never repaired.** The replication sweep skipped any world where `holdsCompletely(...) || hosts(...)` — but `hosts()` reads the registry claim, not the content store, and `restoreFromRegistry` reloads every row as hosted at boot whether or not the bytes survived. So a node announcing a world it could not serve was disqualified from fetching it, permanently and self-perpetuatingly. Observed live: "Yours — hosted here · 0.0% · 0 of 73 pieces" beside "3 peers holding it besides this node". Fixed: the skip is about content held, and a claim with nothing behind it is repaired ahead of placement and bounds | [8](Task.8.md) | `ReplicationRepairsEmptyClaimsTest` green (verified failing without the fix) **and** a live node at 0% reaching 100% within one sweep | RETIRING |
| W-DUP-3 | Worker identity, registry, world-key and tombstone writes now use one fail-closed owner-only atomic writer. `HeadlessPeerMainStateTest` invokes the production startup-state seam on zipfs and recovers identity plus a replaced registry, but this does not launch the worker distribution because configured paths resolve on the process default filesystem | [8](Task.8.md) | Launch the worker distribution with identity and registry on a non-POSIX default/mounted filesystem; perform a registry-writing host operation; restart and recover the same identity and updated `worlds.dat` | RETIRING — implementation + closest production seam green; launched-process proof outstanding |

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
- Two rows (W-REPL-2, W-REPL-3) retired on 2026-07-28; if either regression recurs, open a **new**
  bug row here rather than resurrecting the old id.
