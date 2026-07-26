# Server — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the server category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test name or the log line a live run showed), then reconcile ../ROADMAP.md §2 and the
     root README bar. Live observations count as evidence ONLY when they name the log line or
     artifact that showed them. Never rewrite an old note. -->

**Category:** server · **Last audit:** 2026-07-25 · Tasks completed: **0 / 10**

Tests and live suites: [`TESTING.md`](TESTING.md) · architecture reference:
[`REFERENCE.md`](REFERENCE.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md) · programme plan:
[`../plans/Plan.5.md`](../plans/Plan.5.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Plugin skeleton, build lane, platform abstraction | ✅ COMPLETED | `nodera-endpoint.jar` enables on real Paper 1.21.1 **and** Folia; ALIGN-1 passes at the default and REFUSES at exponent 2. **L-61 retired 2026-07-26**; L-66 (version pin) remains |
| [2](Task.2.md) | Embedded peer + control plane | 🚧 IN PROGRESS | `nodera-endpoint.yml` is parsed, validated and enforced at enable (`EndpointConfig`, 9 tests); the node itself remains. Owns L-71 |
| [3](Task.3.md) | Region custody and the ownership bridge | 🚧 IN PROGRESS | Owns L-62; **L-63 retired 2026-07-26** (multi-view planning). **The design risk**, deliberately before any behaviour change |
| [4](Task.4.md) | World I/O: custody reconciler, chunk gating, save boundary | ⬜ NOT STARTED | Owns L-64. Format-level `.mca` replacement is refused (§C) |
| [5](Task.5.md) | Entity, mob, and event capture lane | ⬜ NOT STARTED | Owns L-67, L-69. Two NeoForge hooks have no Bukkit twin |
| [6](Task.6.md) | The vanilla endpoint: tenants | ⬜ NOT STARTED | Owns L-68. A0′ lands here |
| [7](Task.7.md) | Modded clients on an endpoint | ⬜ NOT STARTED | Owns L-70. Admission is `registryFingerprint` equality |
| [8](Task.8.md) | Plugin compatibility contract | ⬜ NOT STARTED | Owns L-65. PC-1…PC-4 make "100 %" falsifiable |
| [9](Task.9.md) | Live acceptance: mixed-client suites + CI | ⬜ NOT STARTED | The three suites are **committed and skipping** |
| [10](Task.10.md) | Endpoint telemetry + the tenant boundary | 🚧 IN PROGRESS | **L-79 retired 2026-07-26**: `TenantBoundary` + floor landed and tested; the reporter itself remains, and must emit through it |

---

## 2. Milestone notes (newest first)

### 2026-07-26 — The endpoint reads its own configuration, and refuses what it cannot honour

Server task 2 is the node; this is the part of it that everything else needs first. `nodera-endpoint.yml`
— the file the harness already stages — is parsed by `EndpointConfig`, **without Bukkit's YAML
reader**. Using the platform's reader would tie the meaning of the file to a running server, which is
exactly where a misread is expensive to catch; the file's real shape is nested scalars and two inline
lists, so it is read with no Minecraft on the classpath and the whole contract is unit-testable.

Validation **refuses contradictions, not omissions**. A missing key takes a documented default,
because an operator who leaves a section out is asking for the default. A key that cannot be honoured
is refused with *every* reason at once and the message names what to change — reporting only the
first would send someone round the loop once per mistake.

Running it against a real Paper server immediately paid for itself. The first rule said "custody:
FULL with no world id is a contradiction", and the plugin refused the harness's own staged config.
The rule was wrong: an endpoint is routinely configured before its world has an id, which the host
flow mints from the certified genesis. It is only a contradiction when the world is also to be
**announced** — advertising full custody of a world nobody can name is an announce no tracker can
use. The rule now says that, and the suites stage a world id like a real operator would.

The suite gained the assertion that would have caught this without a human reading the log: E1 now
fails if the plugin **refused** or disabled itself during boot. Every later stage reads lines the
refusal path also writes, so without it a refusing plugin passed the suite.

The plugin still says, in as many words, that it does not host a node yet — an endpoint that quietly
did nothing would look identical to one that was working, and the operator would find out when their
world failed to appear on a tracker.

### 2026-07-26 — The preflight refuses, on a real Folia — L-61 RETIRED

A preflight that only ever passes has never been shown to be a check. `e2e-folia.sh` boots the same
jar twice on a real Folia: at the platform default it logs

> ALIGN-1 preflight passed: grid-exponent 4, 4 Nodera regions per Folia section, none split

and at grid-exponent 2 — where an 8-chunk Nodera region straddles two 4-chunk sections — it refuses:

> Nodera refuses to enable: threaded-regions.grid-exponent is 2 … Set threaded-regions.grid-exponent
> to at least 3 (Folia's default is 4) in config/paper-global.yml and restart.

`e2e-plugins.sh` completes the set with co-existence: the corpus is whatever an operator staged,
nothing is downloaded (a compatibility suite that fetches its own subjects tests whatever the
internet served that morning), and what is **absent** is named rather than silently untested.

One honest constraint, recorded rather than papered over: **Folia has no 1.21.1 build** — it went
from 1.21.x to 1.21.4+ — so the Folia suite runs the newest available. That is sound for what it
asserts, because plugin enable and ALIGN-1 are platform properties rather than gameplay; it is also
exactly why the mixed-**client** suites stay blocked on L-66, which is about a client being able to
join at all.

L-61 is RETIRED and task 1 is complete. Eight rows were behind it; they are behind task 2 now, which
is the endpoint hosting a peer.

### 2026-07-26 — There is a plugin, and it enables on a real Paper

The category's gate row said "the plugin does not exist", and eight rows sat behind it. It exists
now, for exactly what task 1 covers: `java/paper-plugin` builds `nodera-endpoint.jar`, and
`scripts/e2e-endpoint.sh` runs it against a downloaded **Paper 1.21.1** — the plugin enables, names
its platform, reports ALIGN-1, and disables cleanly with no exception in the log.

Three decisions worth keeping:

**The platform is detected from the regionised scheduler, not a name.** Folia forks Paper, forks
rename themselves, and a plugin that keys off a version string is broken by the next rebrand. The
probe is for the class that only exists where regions do.

**ALIGN-1 lives in `core`, not in the plugin.** It is arithmetic that decides whether two threads can
end up writing one authority unit, so it is tested exhaustively — every region in a ±64 grid across
exponents 3 to 6 — without a server anywhere near it. The plugin reads a number out of
`paper-global.yml` and asks. Below exponent 3 it refuses to enable and the message names the setting,
the value found and the fix, because a plugin that disables itself silently is indistinguishable from
one that failed to load.

**The suite asserts what task 1 built and nothing else.** `nodera_bukkit_up` waits for the endpoint's
peer control socket — correct for the task-2 suites, wrong here, because this plugin hosts no peer
yet. Waiting for something unbuilt would report "broken" where the honest reading is "not built yet".

Writing this also corrected the register: L-61 claimed all three endpoint suites were committed. Only
the harness library was. `e2e-endpoint.sh` is now real; `e2e-folia.sh` and `e2e-plugins.sh` are not.

### 2026-07-26 — The first server row retires without a server

A second row followed the same logic. **L-79** — an endpoint's aggregate telemetry being shaped by
tenants who were never asked — is retired by `TenantBoundary`, which makes both halves of the
mitigation one thing: the builder accepts a population *count* and totals, so "no attribute varies
with which tenants are connected" holds **by construction** rather than by review, and below the
floor tenant-derived attributes are **omitted** rather than zeroed, because a zero says "measured,
and it was nothing" — a statement about two identifiable people on a two-player server. Tick health
describes the machine and survives the floor.

The obligation this leaves is written into [`Task.10.md`](Task.10.md): the endpoint reporter must
emit through the boundary. A reporter that assembles its own attribute map would be a new
limitation, not a regression of this one.


L-63 was the category's one row whose exit test needed no Paper build at all: it is pure planning
geometry, and the planner it names lives in `core`. So it could be finished and proven while the
endpoint jar is still unwritten.

`ViewOwnershipPlanner` took **one** `PlayerView` per node, which is a fair model of a player's own
client and a wrong model of an endpoint: a server with forty tenants contributed one disc, and the
regions its other thirty-nine stood in went unowned or fell to whichever distant player happened to
be nearest. `planMultiView` takes a node's whole view set, and a node's distance to a region is the
distance of its **nearest** view — the only rule that keeps the plan's meaning, since averaging or
summing would let a crowd elsewhere on the same endpoint outrank a player standing on the block.

Two invariants were preserved on purpose and both are tested: a node takes exactly **one seat**
however many of its tenants are looking (otherwise "three independent validators" could be one
process three times), and `coverCount` still counts **players**, because `isSoloOwned()` asks whether
one person is here, not one process.

The exit clause is the last test: two peers holding the same facts in different orders — reversed
map ordering, reversed tenant list — compute identical plans for a 20-tenant endpoint. That is the
property the whole no-coordinator design rests on.

### 2026-07-25 — A tenth task, specified before the first line of the plugin

[Task 10](Task.10.md) is written now, ahead of every task it depends on, for one reason: the tenant
boundary has to be designed in rather than retrofitted.

A tenant joins with an unmodified client. They have installed nothing, agreed to nothing, and have no
surface on which to be asked — so **no value derived from an individual tenant may ever become
telemetry**. Not a name, not a session length, not a play time, not a count of one. `endpoint.window`
carries a bucketed tenant count, which is a property of the server.

The consent that does exist here is the *operator's*, and it is off by default; deliberately, the task
also owes operators an in-server notice, because a server owner will be asked "does this send my data
anywhere?" and needs an answer they can point at. Registered **L-79** for the residual: a two-player
server's aggregate is closer to describing a person than a two-hundred-player server's is.

### 2026-07-25 — The category opens: A0 becomes A0′, and the suites land before the code

NoderaMC could not be reached by anyone who had not installed a NeoForge mod. That was a deliberate
early decision — [`docs/README.md`](../README.md) §1's **Assumption A0** — and it kept exactly one lane
to make correct while the engine, the wire, and the committee stack were being proven. It is the wrong
decision for adoption: the population that runs Paper and Folia already has worlds, plugins,
communities, and hardware, and none of it could reach the network.

This category opens that door **without opening a second lane**. The reframing is small and it is the
whole design:

> **A0′ — the node/tenant split.** Every *node* runs the Nodera peer and is a first-class member. A
> *player* is not necessarily a node. A player who runs the mod is their own node; a player who does
> not is a **tenant** of a node that is one. There is still exactly one validated lane: a tenant's
> action enters it as an `ActionEnvelope` proposed and signed by the endpoint's node, on the tenant's
> behalf.

Validation is untouched. The engine is still the one engine, the committee still re-executes, and no
unmodified client ever holds a vote or needs one.

Three findings shaped the plan more than anything else:

1. **ALIGN-1.** A Nodera region is 8×8 chunks anchored at chunk 0; a Folia section is 16×16 anchored
   at chunk 0. So **four whole Nodera regions nest inside every Folia section, never a partial one** —
   and therefore all Minecraft-side work for one Nodera region runs on exactly one Folia region
   thread, with no locking. The `WorldMutationApplier` single-writer invariant, which reads as
   incompatible with a server that has no main thread, holds *per region* for free. This is the
   invariant the whole category stands on, so it is preflighted at enable and asserted live before
   anything depends on it.
2. **The tenant lane already exists.** `LiveEntityLaneRuntime.submit` builds
   `NodeId actor = new NodeId(player.getUUID())`, registers the actor against the host's key, and
   signs with the host's identity — the transitional path for *"players whose clients have not
   announced a node yet"*. Tenancy is that path made permanent, explicit, and auditable through a
   signed roster, not a new mechanism.
3. **Mod compatibility already has a cryptographic answer.** `GenesisManifest.registryFingerprint` is
   what makes two peers agree on the palette, so *"which clients may join"* resolves to fingerprint
   equality rather than a mod-name list — exact where a name list is approximate.

Two things were **refused** rather than scoped, and the refusals are recorded in
[`LIMITATIONS.md`](LIMITATIONS.md) §C: replacing `RegionFileStorage`/`ChunkSerializer` from a plugin
(fork territory, and a corruption vector on every Paper update — the world folder must stay openable
by a plain Paper server with the plugin removed), and minting a synthetic node per tenant (which would
let a forty-player endpoint claim forty committee seats and own every quorum in its world).

The evidence lane landed with the plan rather than after it. `scripts/e2e-endpoint.sh`,
`scripts/e2e-folia.sh`, and `scripts/e2e-plugins.sh` are committed, wired into `scripts/run-tests.sh`
and the `e2e-live` matrix, and report `SKIPPED (server task 1 — no plugin jar)` at their preflight —
the same discipline `e2e-mobs.sh` already uses for L-60. The headline assertion is
`e2e-endpoint.sh` P5: **a tenant drops an item, a modded player picks it up, credited exactly once**,
across the two client classes, in one world.

The overall completion figure moved **96.9 % → 80.4 %**: nine new tasks entered the denominator at
zero, so `96.9 × 44/53 = 80.4`. Nothing regressed — the build got larger.
