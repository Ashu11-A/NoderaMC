# Server — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: Rows arrive here from LIMITATIONS.md when their EXIT TEST is green, WITH
     the evidence that retired them. Never delete a row or soften an evidence cell. If a retired
     capability regresses, open a bug issue and add a NEW row to LIMITATIONS.md — do not reopen the
     row here. -->

**Category:** server · **Last audit:** 2026-07-25 · Retired rows: **1**

| ID | Limitation | Retirement evidence | Owner | Retired |
|---|---|---|---|---|
| L-63 | `ViewOwnershipPlanner` took one `PlayerView` per node, so an endpoint with forty players contributed at most one disc to the ownership plan | `planMultiView` accepts a collection of views per node, and a node's distance to a region is the distance of its **nearest** view. That rule is the only one that keeps the plan's meaning: primacy belongs to whoever is nearest, and a node standing on the region through one tenant is standing on it — averaging or summing would let a crowd elsewhere on the same endpoint outrank a player on the block. A node still takes exactly **one seat** however many tenants are looking (three validators must mean three processes), while `coverCount` still counts **players**, because `isSoloOwned()` asks whether one person is here, not one process. The single-view `plan(...)` is unchanged and delegates. `ViewOwnershipPlannerTest` (17, +6): the second tenant's region is owned, the nearest view decides rank, no node is seated twice, cover count counts people, the single-view API is identical to a list-of-one — and the exit clause itself, two peers holding the same facts in different orders (reversed map, reversed tenant list) computing **identical** plans for a 20-tenant endpoint | [3](Task.3.md) | 2026-07-26 |

The category opened on 2026-07-25 with all nine tasks ⬜ NOT STARTED, and the first row to retire was
the one whose exit test needed no endpoint at all: **L-63** is pure planning geometry, so it could be
finished and proven while Paper and Folia support is still unwritten. The remaining eleven rows and
their exit tests are in [`LIMITATIONS.md`](LIMITATIONS.md); the next to retire is expected to be
**L-61**, when one `nodera-endpoint.jar` enables on Paper and Folia and the three live suites get past
their preflight.
