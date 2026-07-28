# Server Task 8 — Plugin Compatibility Contract

<!-- AI-AGENT-INSTRUCTION: "100 % plugin compatible" is only meaningful as PC-1…PC-4 below — four
     clauses that can each go RED. Do not soften them into prose. The governing decision is:
     PLUGINS KEEP AUTHORITY OVER THE WORLD; NODERA CERTIFIES WHAT THEY DID. A design that suppresses
     a plugin's write, or that silently reverts one, is a regression — reverts fire
     NoderaRegionDeniedEvent, always. NoderaEndpoint must never add a throwing override to any Bukkit
     API. Keep this header accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** server · **Owns:** L-65 · **Last audit:** 2026-07-28
**Depends on:** [server 5](Task.5.md), [engine 7](../engine/Task.7.md)
**Consumed by:** [server 9](Task.9.md)

---

## Goal

A server operator installs NoderaEndpoint on a server with thirty plugins and nothing breaks. Made
falsifiable as four clauses, each with a test that can go red, and proven against a pinned corpus of
plugins people actually run.

## Status detail

Not started. Foreign-write certification is proven headlessly by the interference guard and has never
been run against a real plugin ([L-65](LIMITATIONS.md)).

## Dependencies

- [server 5](Task.5.md) — the capture lane whose event handlers must not disturb other listeners.
- [engine 7](../engine/Task.7.md) — the interference guard, which is the whole of PC-3.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | PC-1: an API-surface audit + an ArchUnit rule proving no throwing override is added | ⬜ |
| 2 | PC-2: the event-priority discipline (`MONITOR` to observe, `LOWEST`/`HIGH` to gate) | ⬜ |
| 3 | `NoderaRegionDeniedEvent` — every denial and every revert is visible to other plugins | ⬜ |
| 4 | PC-3: foreign writes certified as `ExternalDelta` through the interference guard | ⬜ |
| 5 | PC-4: `folia-supported: true` + the no-`BukkitScheduler` rule, already enforced by task 1 | ⬜ |
| 6 | `NoderaEndpointAPI` published on the services manager (optional for plugins to use) | ⬜ |
| 7 | The pinned compatibility corpus + `scripts/e2e-plugins.sh` | ⬜ |

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

- `java/paper-plugin/src/main/java/dev/nodera/endpoint/compat/{NoderaEndpointAPI,NoderaRegionDeniedEvent,ForeignWriteBridge,EventPriorityAudit}.java`
- `scripts/e2e-plugins.sh`

## Testing

- `EventPriorityAuditTest` — every listener is registered at the priority its purpose allows; a
  `MONITOR` listener that cancels fails the build.
- `ForeignWriteBridgeTest` — a foreign write becomes an `ExternalDelta`; one that races a committed
  delta is reverted **and** fires `NoderaRegionDeniedEvent`; a write over the rate policy revokes the
  region with a stated reason.
- `NoderaEndpointAPITest` — the API is read-only and returns live state.
- ArchUnit — no throwing override of a Bukkit API type; no `BukkitScheduler` reference.
- Live (`e2e-plugins.sh`): the corpus loads; a WorldEdit `//set` in a delegated region is certified;
  CoreProtect still logs every block change; the log is clean. **This is L-65's exit.**

## Acceptance criteria

1. ⬜ The corpus loads on an endpoint with zero errors, on Paper and on the Folia-ready subset.
2. ⬜ A WorldEdit `//set` in a delegated region is certified as an `ExternalDelta`, not suppressed.
3. ⬜ A revert always fires `NoderaRegionDeniedEvent`; nothing vanishes silently.
4. ⬜ A `MONITOR` listener from another plugin still sees every event it saw before.
5. ⬜ ArchUnit proves no throwing override and no `BukkitScheduler` reference.
6. ⬜ `NoderaEndpointAPI` is discoverable through the services manager and is read-only.

## Limitations

- **L-65** — foreign-write certification has never been exercised against a real plugin corpus.
