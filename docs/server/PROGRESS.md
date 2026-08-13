# Server — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the server category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test name or the log line a live run showed), then reconcile ../ROADMAP.md §2 and the
     root README bar. Live observations count as evidence ONLY when they name the log line or
     artifact that showed them. Never rewrite an old note. -->

**Category:** server · **Last audit:** 2026-08-10 · Tasks completed: **1 / 10**

Tests and live suites: [`TESTING.md`](TESTING.md) · architecture reference:
[`REFERENCE.md`](REFERENCE.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md) · programme plan:
[`../plans/Plan.5.md`](../plans/Plan.5.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Plugin skeleton, build lane, platform abstraction | ✅ COMPLETED | `nodera-endpoint.jar` enables on real Paper 1.21.1 **and** Folia; ALIGN-1 passes at the default and REFUSES at exponent 2. **L-61 retired 2026-07-26**; L-66 (version pin) remains. `NoderaScheduler` seam + ArchUnit ban deferred to tasks 3–5 |
| [2](Task.2.md) | Embedded peer + control plane | 🚧 IN PROGRESS | `nodera-endpoint.yml` parsed/validated/enforced (`EndpointConfig`, 9 tests); **external worker link shipped** (`EndpointPeerLink` + `ControlClient`, E4 green). **L-71 retired 2026-07-26**. The in-process `PeerRuntime` remains; `embedded` does nothing (follow-on scope) |
| [3](Task.3.md) | Region custody and the ownership bridge | 🚧 IN PROGRESS — **custody half blocked** | **L-63 retired 2026-07-26** (multi-view planning) still holds. **L-62 retired 2026-07-28 and REOPENED 2026-08-06**: `CustodyDigest`, `CustodyAudit` and both their suites were deleted 2026-08-06 (`0b02aa5`, #210) as unreachable, taking the row's exit test with them, so deliverables 2 and 4 are **withdrawn** and 3 ("the digest on the announce") is **blocked on a class that no longer exists** — do not pick it up before reading the decision note in [`Task.3.md`](Task.3.md). Actionable remainder: the custody tiebreak, `NoderaFoliaRegionMap` |
| [4](Task.4.md) | World I/O: custody reconciler, chunk gating, save boundary | ⬜ NOT STARTED | Owns L-64. Format-level `.mca` replacement is refused (§C) |
| [5](Task.5.md) | Entity, mob, and event capture lane | 🚧 IN PROGRESS | Owns L-67, L-69. Two NeoForge hooks have no Bukkit twin. **Deliverable 6 landed 2026-08-10** (`ProjectionPinner` + adapter + listener, wired from `onEnable`, `ProjectionPinnerTest` 12) — its INPUT is missing, because nothing delegates a region (tasks 2/3). **Deliverable 7 was measured rather than built**: the mechanism is `:engine`'s interference guard, and the two thresholds L-67's exit clause is stated in are derived in [`Task.5.md`](Task.5.md) §Design. Neither row retires |
| [6](Task.6.md) | The vanilla endpoint: tenants | ⬜ NOT STARTED | Owns L-68. A0′ lands here |
| [7](Task.7.md) | Modded clients on an endpoint | ⬜ NOT STARTED | Owns L-70. Admission is `registryFingerprint` equality |
| [8](Task.8.md) | Plugin compatibility contract | ⬜ NOT STARTED | Owns L-65. PC-1…PC-4 make "100 %" falsifiable |
| [9](Task.9.md) | Live acceptance: mixed-client suites + CI | 🚧 IN PROGRESS | The three suites + launcher + CI matrix are **committed and green for the built subset** (enable, ALIGN-1, external link). **Correction 2026-08-10:** the headline stages did not "skip on open rows" — they did not exist. `endpoint` had E0–E4, `folia` F0–F2, `plugins` C0–C2 and nothing else. **P6** and **F5** are the first two written (#180, #177) and they FAIL at their delegation gate, on purpose |
| [10](Task.10.md) | Endpoint telemetry + the tenant boundary | 🚧 IN PROGRESS | **L-79 retired 2026-07-26**: `TenantBoundary` + floor landed and tested; the reporter itself remains, and must emit through it |

---

## 2. Milestone notes (newest first)

### 2026-08-10 — The projection pin exists; L-67's two thresholds exist; neither row retires

Two rows, one commit, because both are server task 5 and both turned out to be blocked on the same
missing thing.

**L-69 (#180) — the pin is built.** `ProjectionPinner` (Minecraft-free), `BukkitProjections` (the
`Item` adapter and the `RegionScheduler` dispatch) and `ProjectionListener` (`ItemSpawnEvent`,
`EntityPickupItemEvent`) are wired from `NoderaEndpointPlugin.onEnable`, and `ProjectionPinnerTest`
drives 72,000 simulated ticks — an hour, twelve vanilla despawn ages — against a fake item that keeps
moving after every velocity write. Evidence: `ProjectionPinnerTest` 12 tests, `./gradlew
:paper-plugin:test` green.

Two things the design as written here would have got wrong, both found by writing the test rather
than by reading:

- *"pickup delay held at maximum, pickup routed through `EntityPickupItemEvent`"* — that event does
  not fire while `pickupDelay > 0`, so a maximal delay makes L-69's own exit clause ("picking it up
  credits exactly once") unreachable: the item would never be picked up at all. The intent is
  detected on the region tick instead, from a player inside one block, and the event handler's job is
  to **cancel** the vanilla credit — which is what makes exactly-once survive a plugin that zeroes
  the delay behind us.
- *"zeroed velocity re-applied on the region tick"* does not bound drift. The platform integrates the
  entity **before** the region task runs, and gravity replaces the velocity on the next tick.
  Re-applying position every tick would bound it and would be client-visible jitter, so the pin uses
  a 0.5-block hysteresis band and the observable bound is 1.0 block — the number `endpoint` P6
  asserts.

**L-67 (#177) — the two thresholds now exist, and they refuse the clause.** "The resync count under
threshold" and "no region revoked for interference rate" named two numbers and stated neither, which
makes an exit test unanswerable. Both were already in production code and are now recorded in
[`Task.5.md`](Task.5.md) §Design: resync ≤ **100 bps** (`EntityLaneSoakMetrics.MAX_RESYNC_RATE_BPS`,
1% of commit outcomes) and revocation strictly above **60** foreign writes per **1200**-tick window
(`NoderaConstants.INTERFERENCE_REVOKE_RATE`, compared at `DelegabilityPolicy:163`).

The derivation is the finding. One two-tick repeater clock — ordinary, not a stress test — is 600
edges a window, and on an endpoint every one is a foreign write because there is no mixin to cancel
the scheduled tick. That is ten times the revocation bound, so F5's two halves cannot both hold: the
load its first clause demands revokes the region its second clause requires to survive. The same
clock costs **zero** on the modded host. Evidence: `InterferenceThroughputTest` (`:engine`, 4 tests),
which also pins the property that makes the approximation survivable at all — the certificate rate
follows `InterferenceCommitter.setCommitIntervalTicks` and not the write volume, so sixty-four times
the load costs the same number of certificates. Resolving it is a **decision**, framed with three
options in `Task.5.md` §Design; making the revocation rate source-aware is the recommendation.

**Neither row retires, and the reason is the same for both.** Every clause in both exit tests says "a
**delegated** region", and nothing on the endpoint path delegates one — server tasks 2 and 3. So
`endpoint` **P6** and `folia` **F5** were written and both **fail at their first assertion**
(`ServerEndpointSupport.requireDelegatedRegion`), rather than measuring vanilla and reporting it as
the validated lane. They fail rather than skip because a delegated region is an artefact the
harness's own topology should produce, which makes a skip structural — red under every flag since PR
#259 — so failing is the same colour and says more. Expect the `endpoint` and `folia` legs of
`e2e-live` to be red until tasks 2/3 land.

**A correction that outlives this commit:** [`TESTING.md`](TESTING.md) claimed the mixed-client
stages "report SKIPPED naming the open row that blocks each". They did not exist. Five rows (L-65,
L-67, L-68, L-69, L-70) named stages that were in no form on this tree, which means those rows were
**unmeasurable** rather than unmet — a different statement about the project. §1.3 and §1.4 now say
per stage which exist.

**And one recorded decision:** L-66/#182 (Folia publishes no build of the pinned Minecraft version)
blocks L-67's closing condition, so a recommendation is now framed in [`Task.1.md`](Task.1.md) — drop
the mixed-client Folia requirement, keep Folia at the platform level, and give the residue its own
row with a re-check trigger. It is framed, not taken: moving a version pin is the owner's call. It
lives in the task file because a decision recorded only in an issue comment evaporates.

### 2026-08-06 — L-62 REOPENED: the exit test that retired it no longer exists

The reduction programme of issue #210 deleted `CustodyDigest`, `CustodyAudit`, `CustodyAuditIT` and
`CustodyDigestTest` in commit `0b02aa5`, on the correct finding that no production entry point could
reach any of them. That deletion also removed L-62's exit test, and this category's rule is that the
exit test is what a retirement rests on — not how convincing the prose in the evidence cell reads. The
row is therefore back in [`LIMITATIONS.md`](LIMITATIONS.md) §B as OPEN, with the retirement evidence
preserved verbatim in the withdrawn-retirement section of
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

Nothing regressed on 2026-08-06; what changed is what can be demonstrated. A node advertising
`custody: FULL` is not sampled and cannot be downgraded, and the only thing that catches a false claim
is the fetcher hash-verifying pieces against the manifest and being told by `ContentAvailability` what
a peer actually holds — detection after a replication decision rather than before it. The row becomes
checkable again when task 3 deliverable 3 carries the digest on the tracker announce, because only
then does an auditor receive a root it did not compute itself. Restoring the deleted suite alone would
not do it.

### 2026-07-28 — A custody claim can now be falsified — L-62 RETIRED (evidence withdrawn 2026-08-06)

`custody: FULL` was believed by everyone and checked by no one, so an endpoint that had silently lost
half its world still read as a complete replica. `CustodyDigest` makes the claim checkable — a Merkle
root over `(RegionId → head SnapshotVersion)` with the leaves in canonical
`RegionOrder.BY_DIMENSION_XZ`, so two honest nodes with the same state produce the same root and any
peer can verify one region's head from an inclusion proof without holding the world. `CustodyAudit`
samples one region at random **from the region set the auditor believes the world to have** — never a
set the subject supplied, because a liar would just omit what it lost — and a failure **downgrades to
`VIEW` rather than evicting**, so a transient loss never becomes an outage.

The evidence is `CustodyAuditIT` (5, green): a 64-region endpoint that lost one region and advertises
an internally perfect digest survives every sample that misses the loss and is caught the moment it is
sampled, named in the reason; the world stays available, asserted by re-serving and re-verifying all
63 surviving regions **after** the downgrade. `CustodyDigestTest` (8) covers ordering,
one-changed-head, one-missing-region and proofs at world sizes 1…100. `EndpointConfig` now uses
`dev.nodera.core.region.CustodyClass` instead of a private enum, so the configured claim and the
audited claim are one type. Carrying the digest on the tracker announce is task 3 deliverable 3 and
was deliberately not folded in here: the audit verifies against a root it is handed, which is exactly
what an announce will hand it.

### 2026-07-28 — Documentation sweep: statuses reconciled against the actual code

A category-wide audit against the tree (5 main + 3 test classes in `endpoints/paper-plugin`, 20 unit tests;
jscpd duplicated-block count for the module: **0**) reconciled every task header with the code. Three
headers had been lying: [Task.1](Task.1.md)'s status detail still read "Not started" under a ✅
COMPLETED header; [Task.2](Task.2.md)'s still read "Not started" while its external-mode link and
L-71's SIGKILL exit were already green; [Task.9](Task.9.md)'s read "the suites exist and skip" while
`e2e-endpoint.sh` E1–E4, `e2e-folia.sh` ALIGN-1, and `e2e-plugins.sh` co-existence all run green. All
three are corrected, `Last audit` rolled to 2026-07-28 across the category, the task index in
[Task.0](Task.0.md) now matches [`TESTING.md`](TESTING.md), and a new
[`REFACTORING.md`](REFACTORING.md) records that the module is too small to have any duplication to
refactor yet. No limitation moved: the open rows in [`LIMITATIONS.md`](LIMITATIONS.md) are unchanged
because no exit test was newly run.

### 2026-07-26 — The Paper/Folia plugin carries the peer

`:paper-plugin` now depends on `:peer` and bundles the Nodera stack into `nodera-endpoint.jar`. This
is the opposite trade from the NeoForge mod, and it is deliberate: a player installs an app that
supervises a worker beside their game, whereas a server operator installs one jar into a machine that
has no desktop app on it. The peer therefore runs in-process here, and only here.

### 2026-07-26 — The server JVM was killed and the world stayed on the network — L-71 RETIRED

L-71's exit test is a sentence about a crash, so the suite performs one: `e2e-endpoint.sh` E4 links
the endpoint to its worker, hands the world over with the same `NODERA-HOST` verb the mod sends,
confirms the worker reports it in `connected_worlds`, then **`kill -9`s the server JVM** and asks
again. The worker answers, and the world is still hosted.

The kill is deliberately ungraceful. A clean stop would demonstrate the opposite of the claim — the
row is about what happens when a server dies without warning, and that is the only shape of evidence
that settles it.

The assertion reads the `world_id` **field** out of `NODERA-STATE` rather than grepping the id
anywhere in the reply. An error message quoting the id would otherwise have read as a hosted world,
which is the failure mode that makes a green suite worse than no suite.

What remains is follow-on scope rather than this row: `embedded` is still a config value that does
nothing. It should either host a node or be deleted, and given what this row concluded, deleting it
is the honest end state.

### 2026-07-26 — The endpoint has a node, and it is not inside the server

`peer.mode: external` works, and it is now what the harness stages. The plugin links to an always-on
worker over the worker's own control socket, and `e2e-endpoint.sh` E4 asserts it against a real
worker on a real Paper server: *"linked to the Nodera worker at 127.0.0.1:25613"*.

External first is not a shortcut around the embedded node — it is L-71's own conclusion. A node
inside the server JVM dies with it, taking the world off the network exactly when it most needs to
still be there. An always-on worker beside the server has neither problem, and it is the same worker
the companion app and the mod already supervise, so the endpoint gets crash independence by **not**
owning the process rather than by engineering around owning it.

Two rules the link follows, both tested against a real socket rather than a mock (the property is a
wire property; a mock would prove the mock):

- **Linking is never a startup gate.** A worker that is slow to boot must not stop a Minecraft server
  from accepting players. The endpoint retries in the background and the world stays playable.
- **Only changes are logged.** A worker down for an hour must not write an hour of identical lines
  into an operator's log.

The first version of the client used a 4-byte length prefix — the framing the companion app uses
elsewhere. The worker answered nothing, and the live run said so in one line. The protocol belongs to
the server, not to the client's preference, and the class now says that where the next person will
read it.

L-71 moves OPEN → RETIRING. Its exit is a SIGKILL of the server JVM with the world staying announced
and seeded, which needs the worker to be hosting the world — the rest of task 2.

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
now, for exactly what task 1 covers: `endpoints/paper-plugin` builds `nodera-endpoint.jar`, and
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
