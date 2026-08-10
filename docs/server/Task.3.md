# Server Task 3 — Region Custody and the Ownership Bridge

<!-- AI-AGENT-INSTRUCTION: This is where the design risk lives, and it is deliberately BEFORE any
     Minecraft behaviour changes so a wrong answer is cheap. Two rules are absolute: (1) Nodera
     regions stay 8×8 and static — Folia's dynamic regions are execution units and never become
     authority units; (2) capacity never buys authority — FULL custody breaks ties in the ownership
     plan and NEVER outranks geometric distance. The multi-view planner change lands in `core` with
     headless tests before the plugin consumes it. Keep this header accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** server · **Owns:** L-62 (retired 2026-07-28, REOPENED 2026-08-06 — its exit test was deleted; L-63 RETIRED 2026-07-26) · **Last audit:** 2026-08-10
**Depends on:** [server 2](Task.2.md), [engine 5](../engine/Task.5.md), [network 6](../network/Task.6.md)
**Consumed by:** [server 4](Task.4.md), [server 5](Task.5.md), [server 6](Task.6.md)

---

## Goal

An endpoint holds **100 % of its world's chunks** at their current certified heads, says so on the
wire in a way another peer can check, contributes **all of its players' fields of view** to the
decentralized ownership plan under its single node identity, and knows for any Nodera region which
execution thread owns it. After this task the endpoint is a correct participant in the ownership
model; it still changes nothing about how Minecraft behaves.

## Status detail

In progress. **Custody is not a checkable claim.** `CustodyDigest` and `CustodyAudit` landed on
2026-07-28 with `CustodyDigestTest` (8) and `CustodyAuditIT` (5) and retired L-62; both classes and
both suites were **deleted on 2026-08-06** (commit `0b02aa5`, issue #210) because no production
entry point reached them. See the decision note under Deliverables: the custody half of this task
(2, 3, 4) is one blocked item pending a decision on whether proactive verification is wanted at all,
not three separate pieces of remaining work. What remains that is actually actionable is the
Minecraft side: the custody tiebreak in the plan (6), and the operator-facing half of the region map
(8) — the map itself (7) landed on 2026-08-10 with [4](Task.4.md)'s cross-region gate, which is what
needed it. `ViewOwnershipPlanner` takes one `PlayerView` per node
(**L-63**, retired 2026-07-26 — `planMultiView` takes a node's whole set of views and ranks it by its nearest one), and no custody claim is checkable by anyone

## Dependencies

- [server 2](Task.2.md) — the embedded peer whose `WorldStore` backs custody.
- [engine 5](../engine/Task.5.md) — leases, epochs, and the committee the plan feeds.
- [network 6](../network/Task.6.md) — `ArchiveAuditTask` / `ArchiveRepairService`, the spot-check path
  the custody digest reuses.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `CustodyClass` (`VIEW` \| `FULL`) on the membership entry | 🚧 the type exists in `core.region` and the plugin's config uses it; the **membership entry** field is deliverable 3's wire change |
| 2 | `CustodyDigest` — Merkle root over `(RegionId → head SnapshotVersion)` in canonical `RegionOrder` | ↩️ **WITHDRAWN 2026-08-06** — built, then deleted unreachable; see the decision note below |
| 3 | The digest rides every tracker announce and every membership gossip | 🛑 **BLOCKED** — this deliverable's input no longer exists |
| 4 | Spot-check: any peer samples a region and downgrades a failing claim to `VIEW` | ↩️ **WITHDRAWN 2026-08-06** — same note |
| 5 | `ViewOwnershipPlanner` multi-view overload (`Map<NodeId, Collection<PlayerView>>`, min distance) | ⬜ |
| 6 | Custody-class tiebreak, **after** distance, **before** the `NodeId` tiebreak | ⬜ |
| 7 | `NoderaFoliaRegionMap` — `RegionId` → owning Folia region, with the ALIGN-1 assertion live | 🚧 landed 2026-08-10 in `dev.nodera.endpoint.paper.world`, consumed by [4](Task.4.md)'s cross-region gate. It answers `shareExecutionThread(a, b)` rather than naming a Folia region id: Folia's regions merge and split and have no stable identity (§C), so the answerable question is which regions one thread writes. Same section ⇒ ALIGN-1 arithmetic, no runtime call; wider ⇒ `FoliaOwnershipProbe` (reflection over `Server#isOwnedByCurrentRegion`); unanswerable ⇒ refusal. Built at enable and logged; `e2e-folia` F1 asserts the line. `NoderaFoliaRegionMapTest` (7) |
| 8 | `/nodera regions --folia` — the live mapping, for the suites to assert against | ⬜ |

> ### Decision — the custody half of this task, 2026-08-06 (issue #210)
>
> **Deliverables 2 and 4 were built, shipped as ✅, and then deleted.** `CustodyDigest` and
> `CustodyAudit` went in commit `0b02aa5` with the archival audit triangle: a transitive closure
> from the three real production entry points could not reach any of them, and the debugger-profiled
> run of the real worker never loaded one. They passed their own tests and nothing else ran them.
>
> They are marked **withdrawn**, not ⬜ and not ✅, and the distinction is the point. ⬜ would claim
> the work was never done; it was. ✅ claims a capability the product has; it does not. Withdrawn
> says: this was built, it was found to be unreachable, and it was removed rather than left in the
> jar as a check nobody runs.
>
> **Deliverable 3 is blocked, not open.** "The digest rides every tracker announce and every
> membership gossip" cannot be picked up as written — there is no digest to put on an announce.
> Anyone starting it would go looking for `CustodyDigest` and find nothing, which is exactly what
> this note exists to prevent.
>
> **The open question, which is a design decision and not a wiring task:** is proactive custody
> verification wanted at all? The reduction's argument is that it is redundant — every piece a node
> serves is hash-verified by the receiver against the manifest, and since the "no negative on the
> piece wire" fix a peer asked for pieces it lacks answers with a `ContentAvailability` stating what
> it actually holds, so a false `FULL` claim fails to answer and is passed over. The counter-argument
> is the one this task was written for and it is not answered: **that is discovery at fetch time, by
> the fetcher.** Nothing notices a silently-degraded replica *before* somebody needs the data, which
> is precisely when a replication-factor decision was already made on the strength of the claim.
>
> Until that is decided, deliverables 2, 3 and 4 should be treated as one item, not three. The
> Merkle design in §5 of [`REFERENCE.md`](REFERENCE.md) is preserved as a record of intent — it was
> not disproven, it was unreachable — and `git show 0b02aa5^:peer/src/main/java/dev/nodera/peer/archival/CustodyDigest.java`
> recovers the implementation if the decision goes the other way.

## Design

### C1/C2 — custody is a claim, and claims were to get checked
*(Design of record. Not implemented — see the decision note under Deliverables.)*

`FULL` means: *this node can answer a request for any region's current certified state at any time,
without loading a chunk into the game.* It is backed by the peer's `WorldStore` and `WorldArchive`,
not by the server's chunk cache — a chunk unloaded in Minecraft is still held by the node.

The design was that a claim should be spot-checkable: every announce would carry a `CustodyDigest`,
a Merkle root over the whole world's per-region heads in canonical `RegionOrder` so two honest nodes
with the same state produce the same root, and any peer could ask for a random region's head plus
its inclusion proof and verify it against the advertised root.

**No announce carries a digest and no peer spot-checks anything.** The archive-audit path this was
to reuse was deleted with the digest on 2026-08-06. What actually holds a false `FULL` claim in
check today is weaker and later: every piece a node serves is hash-verified by the receiver against
the manifest, and a peer asked for pieces it lacks answers with a `ContentAvailability` saying what
it really holds — so the liar fails to answer and is passed over, at fetch time, by the fetcher.

A node whose spot-check fails is **downgraded to `VIEW`**, loudly, and keeps playing. The world stays
available; only the claim is withdrawn. This is *verified, never trusted* applied to a new claim
rather than a new trust model.

### C3 — tenant views are the endpoint's views

`ViewOwnershipPlanner` hands each region to the nearest player node. An endpoint has no view of its
own, but its players are standing in the world and they are its responsibility. So the endpoint
contributes **every one of its players' discs, under its own single `NodeId`**, and a node's distance
to a region becomes the **minimum** over its views:

```java
// core, additive — the single-view form stays as a delegating convenience
public static Map<RegionId, RegionClaim> plan(
        Map<NodeId, Collection<PlayerView>> views, int maxCommitteeSize, Collection<NodeId> residents)
```

Determinism survives: `min` over a view collection sorted at encode time is deterministic, and the
existing `NodeId` tiebreak is untouched, so every peer that knows the same views still computes the
same plan without asking anyone. This is the property the whole ownership model rests on and it is
the first thing the tests assert.

**Why not give each tenant its own synthetic node?** Because a node is a key holder and a committee
seat. Minting one per tenant would let an endpoint with 40 players claim 40 committee seats and
quietly own every quorum in the world — a Sybil attack the endpoint would not even have to intend.
One endpoint, one node, one seat per region: **N tenants grow the endpoint's *footprint*, never its
*weight*.**

### C4 — capacity never buys authority

An endpoint that primaries every region because it is bigger is a central server wearing a costume.
So the ranking is, in order:

1. **geometric distance** to the region centre — a modded player standing closer than any tenant
   primaries the region and the endpoint validates it;
2. **custody class** — `FULL` before `VIEW`, and only on an exact distance tie;
3. **`NodeId`** — the existing deterministic tiebreak.

This mirrors the invariant the project already holds for dedicated servers: *the server is special in
capacity and availability, not in authority.*

### C5 — standby primacy

When a region's primary leaves, an endpoint holding `FULL` custody is the natural successor: it
already has the state, so promotion costs no transfer. That is the existing resident-validator and
gateway-election machinery with a successor that is never cold.

### The Nodera↔Folia region map

`NoderaFoliaRegionMap` answers two questions and nothing else:

- *which execution thread owns Nodera region `R`?* → the Folia region owning
  `(R.centerChunkX, R.centerChunkZ)`, dispatched through `NoderaScheduler.onRegion`;
- *do two Nodera regions share an execution thread?* → the cross-region decision every later task
  needs, and the trigger for [L-64](LIMITATIONS.md)'s joint-transfer path.

ALIGN-1 (one Nodera region ⊂ one Folia region, for `grid-exponent ≥ 3`) is asserted **live** here, not
just arithmetically in task 1: the map cross-checks `Bukkit.isOwnedByCurrentRegion` for all four
corners of every region it hands out, and a violation is a hard error naming the region — because a
region split across two threads is corruption with a delay fuse.

## Files

- `library/java/core/src/main/java/dev/nodera/core/region/ViewOwnershipPlanner.java` (multi-view overload)
- `library/java/core/src/main/java/dev/nodera/core/region/CustodyClass.java`
- ~~`peer/src/main/java/dev/nodera/peer/archival/CustodyDigest.java`~~ — deleted 2026-08-06 (`0b02aa5`)
- ~~`peer/src/main/java/dev/nodera/peer/archival/CustodyAudit.java`~~ — deleted 2026-08-06 (`0b02aa5`)
- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/region/{NoderaFoliaRegionMap,EndpointOwnershipPlanner,EndpointCustodyService}.java`

## Testing

- `ViewOwnershipPlannerTest` (extended) — a node with N views; the min-distance rule; a 20-tenant
  endpoint planned identically by two independent peers; the custody tiebreak fires **only** on an
  exact distance tie and never ahead of a nearer player.
- ~~`CustodyDigestTest`~~ / ~~`CustodyAuditIT`~~ — **deleted 2026-08-06 with the classes they
  covered.** `CustodyAuditIT` was L-62's exit test, and with it gone the row is open again: see L-62
  in [`LIMITATIONS.md`](LIMITATIONS.md) §B for what would have to exist before it can be checked, and
  the withdrawn-retirement section of [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) for the evidence
  as it stood.
- `NoderaFoliaRegionMapTest` — mapping arithmetic; ALIGN-1 across the coordinate origin and both
  negative axes.
- Live (`e2e-folia.sh` F2): `/nodera regions --folia` shows every Nodera region mapped to exactly one
  Folia region, with two distinct Folia regions present.

## Acceptance criteria

1. 🛑 An endpoint advertises `FULL` custody with a digest another peer can verify — **blocked**: the digest was deleted unreachable on 2026-08-06 and there is nothing to advertise. See the decision note above.
2. 🛑 A false `FULL` claim is downgraded by a spot-check, and the world stays available — **blocked**: no spot-check exists. A false claim is discovered at fetch time by the fetcher, never proactively, and cannot downgrade anything.
3. ⬜ An endpoint with N players contributes N views under one `NodeId`, and the plan is identical on
   two independent peers.
4. ⬜ A modded player nearer to a region than any tenant **primaries** it; the endpoint validates.
5. ⬜ `FULL` custody wins a tie and loses to a nearer `VIEW` node — asserted, not assumed.
6. ⬜ Every Nodera region maps to exactly one Folia region live, with the corner cross-check green.

## Limitations

- **L-62** — retired 2026-07-28, **REOPENED 2026-08-06**. `CustodyDigest`, `CustodyAudit`, `CustodyAuditIT` and `CustodyDigestTest` were all deleted on that date (`0b02aa5`, issue #210) as unreachable from production. The retirement was earned — the suite ran green and the work was real — but nothing in the tree can re-prove it, and a row whose exit test does not exist is not a retired row. The current row and the exit test it now waits on are in [`LIMITATIONS.md`](LIMITATIONS.md) §B; the original evidence is preserved under "Withdrawn retirement" in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). The deletion was correct and this reopening does not ask for it to be undone — see the decision note above.
- **L-63** — RETIRED 2026-07-26: `ViewOwnershipPlanner.planMultiView` plans from a node's whole view set with the min-distance rule; two peers holding the same facts in different orders derive identical plans for a 20-tenant endpoint (`ViewOwnershipPlannerTest`). Evidence in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
