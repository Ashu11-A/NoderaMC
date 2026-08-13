# Server Task 8 — Plugin Compatibility Contract

<!-- AI-AGENT-INSTRUCTION: "100 % plugin compatible" is only meaningful as PC-1…PC-4 below — four
     clauses that can each go RED. Do not soften them into prose. The governing decision is:
     PLUGINS KEEP AUTHORITY OVER THE WORLD; NODERA CERTIFIES WHAT THEY DID. A design that suppresses
     a plugin's write, or that silently reverts one, is a regression — reverts fire
     NoderaRegionDeniedEvent, always. NoderaEndpoint must never add a throwing override to any Bukkit
     API. Keep this header accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** server · **Owns:** L-65 · **Last audit:** 2026-08-10
**Depends on:** [server 5](Task.5.md), [engine 7](../engine/Task.7.md)
**Consumed by:** [server 9](Task.9.md)

---

## Goal

A server operator installs NoderaEndpoint on a server with thirty plugins and nothing breaks. Made
falsifiable as four clauses, each with a test that can go red, and proven against a pinned corpus of
plugins people actually run.

## Status detail

**Deliverables 2, 3 and 4 landed on 2026-08-10; the corpus half of 7 came with them.** A plugin's
write into a region this endpoint validates now reaches the interference guard — through Bukkit's
events *and* through WorldEdit's own extent pipeline, which is the only way to see a `//set` — and a
refusal is published to the whole server as `NoderaRegionDeniedEvent` rather than happening silently.
Proven on a real Paper 1.21.1-133 with WorldEdit 7.3.9 and CoreProtect 23.2 installed: a `//set` over
1,280 blocks reported **1,280 foreign block writes observed**.

**What is NOT done, and it is the clause L-65 turns on.** Nothing on the plugin path *delegates* a
region yet (tasks [2](Task.2.md) and [3](Task.3.md)), and an `EXTERNAL_MUTATION` certificate is
signed by the node — which under `peer.mode: external` is a different process holding the key
(L-71). So the endpoint can classify, gate, announce and record a foreign write; it cannot certify
one. The bridge is wired with an empty delegation view and a certification sink that returns
nothing, deliberately and visibly, and the `observed` counter in `ForeignWriteBridge.summary()` is
the honest one today. **[L-65](LIMITATIONS.md) stays open.**

## Dependencies

- [server 5](Task.5.md) — the capture lane whose event handlers must not disturb other listeners.
- [engine 7](../engine/Task.7.md) — the interference guard, which is the whole of PC-3.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | PC-1: an API-surface audit + an ArchUnit rule proving no throwing override is added | ⬜ |
| 2 | PC-2: the event-priority discipline (`MONITOR` to observe, `LOWEST`/`HIGH` to gate) | ✅ `BukkitForeignWrites` gates at `HIGH`, observes at `MONITOR`; the WorldEdit hook is innermost at `VERY_EARLY` for the same reason |
| 3 | `NoderaRegionDeniedEvent` — every denial and every revert is visible to other plugins | ✅ fired on every refusal, from the one place a refusal can happen |
| 4 | PC-3: foreign writes certified as `ExternalDelta` through the interference guard | 🚧 the feed is complete and live (Bukkit events + WorldEdit's extent); certification needs a delegated region — tasks 2/3 |
| 5 | PC-4: `folia-supported: true` + the no-`BukkitScheduler` rule, already enforced by task 1 | ⬜ |
| 6 | `NoderaEndpointAPI` published on the services manager (optional for plugins to use) | ⬜ |
| 7 | The pinned compatibility corpus + `scripts/e2e-plugins.sh` | 🚧 `scripts/stage-plugin-corpus.sh` pins WorldEdit 7.3.9 + CoreProtect 23.2 and CI stages them; the suite is `PluginsScenario` C0–C5 |

## Design

### PC-1 — API surface

NoderaEndpoint adds **no throwing override** to any Bukkit, Paper, or Folia API. Anything a plugin
could call before it can call after, with the same semantics. There is no "Nodera mode" in which
`World#setBlockData` throws.

Enforced two ways: an ArchUnit rule over the module (no class implements or extends a Bukkit API type
in a way that narrows it), and the corpus below, which is the only thing that catches the API a
plugin uses that nobody thought about.

### PC-2 — Event contract

Every Bukkit event still fires, in the same order, with the same cancellability. The discipline:

| Purpose | Priority | Rule |
|---|---|---|
| observe (capture, telemetry) | `MONITOR` | must not cancel, must not mutate |
| gate (chunk editability, permission) | `LOWEST` or `HIGH` | may cancel, and must say why |

NoderaEndpoint **never cancels an action another plugin has already allowed** unless the region's
certified state forbids it — and when it does, it fires `NoderaRegionDeniedEvent` carrying the region,
the reason, and the certified head. A protection plugin's `BlockBreakEvent` allow is not silently
overridden; it is overridden *visibly*, which is the difference between a bug report and a log line.

### PC-3 — Write reconciliation, the clause that matters

A plugin that writes blocks or entities through the Bukkit API is a **foreign write source**, and
Nodera has had the answer since [engine 7](../engine/Task.7.md): the interference guard converts
foreign writes into certified `ExternalDelta`s rather than suppressing them.

> **Plugins keep authority over the world; Nodera certifies what they did.**

That is the whole design, and it is the only one that can be honestly called compatible. The
alternatives are both unacceptable: suppressing a plugin's write breaks the plugin, and ignoring it
breaks the state root.

Three consequences, all stated rather than discovered:

1. **A large write is a large delta.** A WorldEdit `//set` over a million blocks becomes a stream of
   external mutations. The guard's existing rate policy applies, and a write that exceeds it revokes
   the region rather than stalling the server — visibly, with the reason.
2. **A write that cannot be certified is reverted.** If a foreign write raced a committed delta, the
   committed delta wins and the foreign write is undone — **with `NoderaRegionDeniedEvent`**, never
   silently. A silently vanishing WorldEdit operation is the single worst outcome available here.
3. **A write to a region this node does not primary is routed**, and routing costs a tick. That is
   the physics of a partitioned world, recorded as envelope constraint [A-8](LIMITATIONS.md), not as
   a defect.

### PC-4 — Folia readiness

The plugin declares `folia-supported: true` unconditionally and never touches `BukkitScheduler`
(enforced in [server 1](Task.1.md)).

A third-party plugin that is **not** Folia-ready still fails to load on Folia. That is Folia's rule
(`docs/minecraft/folia/09-plugin-compatibility.md`), and NoderaEndpoint neither adds to it nor works
around it: shimming `BukkitScheduler` back into existence would hand plugins a main thread that does
not exist and corrupt world state on the first parallel event. On Paper, everything loads exactly as
before.

The honest consequence for operators: **an endpoint on Folia inherits Folia's plugin ecosystem, and an
endpoint on Paper inherits Paper's.** Choosing Folia is choosing parallelism over plugin breadth, and
the docs say so instead of implying the plugin removes the trade-off.

### `NoderaEndpointAPI`

Plugins are never *required* to know Nodera exists. For the ones that want to:

```java
NoderaEndpointAPI api = Bukkit.getServicesManager().load(NoderaEndpointAPI.class);
api.regionAt(location);              // the Nodera RegionId
api.ownership(regionId);             // PRIMARY | VALIDATOR | REPLICA | UNASSIGNED, + lease info
api.custody();                       // FULL | VIEW, plus the current digest
api.isTenant(player);                // tenant or node
api.awaitCertified(regionId);        // a future that completes when the region's state has arrived
```

Read-only by design. A plugin that could *write* through this API would be a second world writer, and
there is exactly one ([`docs/README.md`](../README.md) §4.3 rule 5).

### The corpus

A pinned set, chosen for coverage of the ways plugins touch a world rather than for popularity alone:

| Plugin | Exercises |
|---|---|
| WorldEdit / FAWE | bulk foreign writes — the hardest PC-3 case |
| EssentialsX | commands, teleports, inventories, homes |
| LuckPerms | permission resolution on every gate |
| Vault | the services manager, alongside `NoderaEndpointAPI` |
| PlaceholderAPI | async expansion resolution |
| CoreProtect | block logging at `MONITOR` — a direct PC-2 collision test |
| ViaVersion | protocol translation in front of the endpoint |

Pinned by version in `scripts/e2e-plugins.sh` so the corpus is reproducible; on Folia the set narrows
to the Folia-ready members and the suite says which ones it dropped and why, rather than silently
testing less.

## Files

- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/paper/compat/{ForeignWriteBridge,BukkitForeignWrites,WorldEditBulkWrites,NoderaRegionDeniedEvent}.java`
  — the package is `..endpoint.paper.compat`, not `..endpoint.compat`: everything in this module
  lives under `dev.nodera.endpoint.paper`. `NoderaEndpointAPI` and `EventPriorityAudit` are still
  unwritten.
- `scripts/stage-plugin-corpus.sh` — the corpus, pinned by version. (The suite itself is
  `PluginsScenario`; `scripts/e2e-plugins.sh` was converted to Java with every other live suite.)

## Testing

- `EventPriorityAuditTest` — every listener is registered at the priority its purpose allows; a
  `MONITOR` listener that cancels fails the build.
- `ForeignWriteBridgeTest` ✅ (9) — a foreign write into a delegated region is recorded for
  certification; one that races a committed delta is refused **and** reaches every plugin as
  `NoderaRegionDeniedEvent` carrying the region, the block, the reason and the state the world keeps;
  a bulk write folds every `flushThreshold`; a block outside the palette is neither certified nor
  refused; `observed` counts every write the bridge is handed, which is the number a Bukkit-only
  bridge gets wrong. *Still missing: a write over the rate policy revoking the region.*
- `NoderaEndpointAPITest` — the API is read-only and returns live state.
- ArchUnit — no throwing override of a Bukkit API type; no `BukkitScheduler` reference.
- Live (`scripts/nodera-test.sh run plugins`): C0–C2 the corpus loads and co-exists; **C3** a
  WorldEdit `//set` of 1,280 blocks is observed block-for-block by the bridge and lands in the world;
  **C4** CoreProtect logged every one of them, proven by rolling them back; C5 a clean stop. C3 and
  C4 **fail** when their subject is absent from the corpus — they do not note it. **L-65's exit adds
  one word C3 cannot yet assert: *certified*.**

## Acceptance criteria

1. 🚧 The corpus loads on an endpoint with zero errors, on Paper and on the Folia-ready subset.
   Paper ✅ (WorldEdit 7.3.9 + CoreProtect 23.2, real 1.21.1-133); Folia unrun (L-66).
2. 🚧 A WorldEdit `//set` in a delegated region is certified as an `ExternalDelta`, not suppressed.
   **Not suppressed ✅ and observed ✅** — 1,280 of 1,280 blocks reached the guard; **certified ❌**,
   because nothing delegates a region on this path (tasks 2/3).
3. ✅ A revert always fires `NoderaRegionDeniedEvent`; nothing vanishes silently.
4. ⬜ A `MONITOR` listener from another plugin still sees every event it saw before.
5. ⬜ ArchUnit proves no throwing override and no `BukkitScheduler` reference.
6. ⬜ `NoderaEndpointAPI` is discoverable through the services manager and is read-only.

## Limitations

- **L-65** — narrowed 2026-08-10, still open. Foreign-write certification has now met a real plugin
  corpus and the write reaches the guard; what has never happened is the *certification*, because no
  region on this path is delegated. See the row's exit column for the clause-by-clause reading.
