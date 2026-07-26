# Server — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the server category. "Permanent" is banned. Every §B row has
     an owning task and an EXIT TEST and retires only when that exit test is green — verify against
     the exit clause, never against how small the prose looks. A newly discovered limitation enters
     §B as OPEN with a path, an owner, and an exit test BEFORE the discovering PR merges. Never delete
     a row: move it to LIMITATIONS.fixed.md with its evidence. §C rows are LOCKED DECISIONS or the
     trust model working correctly — a proposal that "fixes" one is a design regression and must be
     refused, not implemented. -->

**Category:** server · **Last audit:** 2026-07-26 · Open or retiring rows: **9**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints (never denied, engineered around)

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-8 | **A partitioned world has cross-partition latency.** An interaction that crosses a Nodera region boundary which is *also* a Folia region boundary — a piston pushing into the next region, an entity handing off, a plugin writing across the line — cannot be instantaneous: two execution threads must agree | ALIGN-1 nests 4 Nodera regions per Folia section, so the overwhelming majority of interactions never cross an execution boundary at all. The ones that do ride the **joint transfer certificate** path, which makes them atomic rather than fast — either both sides commit or neither does | [4](Task.4.md) |
| A-9 | **An unmodified client's protocol carries no Nodera state.** A vanilla client renders no custom payloads, so there is no way to show it a region map, a piece grid, or a committee panel through the protocol it speaks | Everything Nodera wants to tell a tenant is rendered onto surfaces the vanilla protocol already carries — tab list, boss bar, action bar, chat, titles, scoreboard — by the same `DiagnosticsView` view models the mod's `TabListRenderer` / `BossBarManager` / `ActionBarNotifier` already consume. A tenant sees ownership, health, and piece progress; they just see it as Minecraft UI | [6](Task.6.md) |

---

## §B — Staged capabilities (the burn-down list)

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-62 | **Full custody is a claim nobody checks.** A node advertising `custody: FULL` is believed. Nothing samples a region and verifies the node actually holds it, so an endpoint that has silently lost half its world still looks like a complete replica to the swarm | [3](Task.3.md) | `CustodyAuditIT`: an endpoint advertising `FULL` while missing a region is caught by a random spot-check against its advertised digest and **downgraded to `VIEW`**, with the world staying available throughout | OPEN |
| L-64 | **A delta spanning two Folia regions is refused, not applied.** Folia cannot put two region threads in one critical section, so a multi-region delta whose regions do not share an execution thread aborts with a named error and the caller resyncs. Correct, but it makes cross-boundary entity handoff and contraption migration a retry loop instead of a commit | [4](Task.4.md) | `CrossFoliaRegionCommitIT`: a prepare/commit across two Folia region threads, certified with joint transfer certificates and journalled with durable transfer stages, commits atomically — and a failure on one side commits **neither** | OPEN |
| L-65 | **Foreign-write certification has never met a real plugin.** The interference guard converts foreign writes into certified `ExternalDelta`s and is proven headlessly, but no WorldEdit, no protection plugin, and no logger has ever written into a delegated region on a running endpoint | [8](Task.8.md) | `scripts/e2e-plugins.sh`: the pinned corpus loads, a WorldEdit `//set` in a delegated region is **certified** rather than suppressed or silently reverted, CoreProtect still logs every change, and the log audit is clean | OPEN |
| L-66 | **The version pin is unresolved, and it is now measured rather than suspected.** The mod pins Minecraft 1.21.1 / NeoForge 21.1.238 / Java 21. Paper **has** a 1.21.1 build (1.21.1-133) and `e2e-endpoint.sh` is green against it. **Folia does not**: its published versions go 1.19.4, 1.20.x, 1.21.4, 1.21.5, 1.21.6, 1.21.8, 1.21.11, 26.1.2 — 1.21.1 was skipped, so no Folia build of the mod's own Minecraft version exists to resolve. `e2e-folia.sh` is green against Folia 1.21.4, which is sound for plugin enable and ALIGN-1 (platform properties) and useless for anything a 1.21.1 client must join. Also note the PaperMC **v2 API is sunset** (HTTP 410); resolution now goes through `fill.papermc.io/v3`. The row is therefore a decision, not a task: either the mod's pin moves to a Minecraft version Folia publishes, or the mixed-client Folia suites are dropped and Folia support is asserted only at the platform level | [1](Task.1.md) | `e2e-endpoint.sh` and `e2e-folia.sh` are both green against Paper and Folia builds of the Minecraft version the mod pins, resolved through the PaperMC **v3 (fill)** API in CI — which requires the pin and Folia's published versions to intersect | OPEN |
| L-67 | **Vanilla scheduled ticks cannot be cancelled at the source.** The mod's `LevelTicksMixin` makes the engine the only scheduler in a delegated region; Bukkit has no equivalent and a plugin may not add a mixin. So an endpoint's delegated regions run vanilla redstone and reconcile every edge through the interference guard — correct, but it converts redstone activity into external-mutation throughput | [5](Task.5.md) | `e2e-folia.sh` F5: a delegated region under sustained redstone load commits for five minutes with the resync count under threshold, and no region is revoked for interference rate | OPEN |
| L-68 | **Tenants have no cryptographic join gate.** L-52's live-join password runs in the configuration phase over a mod payload a vanilla client cannot answer, so on an endpoint the world password is enforced by a spawn-locked limbo and a `/nodera join <password>` command — the endpoint's gate, not the network's | [6](Task.6.md) | `e2e-endpoint.sh` P7: a wrong password never reaches the world, retries are throttled and then disconnected, the right password admits, and no plaintext appears in **any** log | OPEN |
| L-69 | **Validated items keep a vanilla-ticking projection.** The mod cancels a validated item's vanilla tick so only the canonical item moves; Bukkit cannot cancel a tick, so the endpoint pins the projection instead (unlimited lifetime, zeroed velocity, held pickup delay) and reconciles rather than suppresses | [5](Task.5.md) | `e2e-endpoint.sh` P6: a validated item on an endpoint neither despawns nor drifts over the hold window, and picking it up credits **exactly once** | OPEN |
| L-70 | **Mod-set gating is an assumption, not a confirmed requirement.** "NeoForge players may join except where the host requires specific mods" is read as: the endpoint declares required/allowed mods and `registryFingerprint` equality enforces it. The alternative reading — accept whatever the client brings — would need a palette-superset check with real determinism consequences. The current rule also admits or refuses whole clients, with no partial-compatibility path | [7](Task.7.md) | The reading is re-confirmed (or replaced) with the requester, **and** `e2e-endpoint.sh` P8 proves a fingerprint-mismatched client is refused with a message naming the offending mod | OPEN |
| L-71 | **The embedded peer dies with the server JVM.** In `peer.mode = embedded` the node lives inside the server process, so a crash takes the world off the network at the moment it most needs to still be there. **`external` mode now works and is the harness default**: `EndpointPeerLink` links the plugin to an always-on worker over the worker's own control socket (one line out, one line back — the protocol every other Nodera client already speaks), retries in the background, and logs only on a *change* of state so a worker down for an hour is not an hour of identical lines. Linking is never a startup gate: a slow worker must not stop a Minecraft server from accepting players. `e2e-endpoint.sh` E4 asserts the link against a real worker on a real Paper server. Remaining: the embedded mode still exists as a config value and does nothing — it should either host a node or be removed, and removing it is the honest end state given this row | [2](Task.2.md) | An endpoint configured `peer.mode = external` drives a standalone `nodera-headless` worker over the loopback control socket, and the server JVM is **SIGKILLed** with the world staying announced and seeded | RETIRING |

---

## §C — By design (not limitations, and must not be "fixed")

<!-- AI-AGENT-INSTRUCTION: Do NOT convert these into §B rows. Each is a LOCKED DECISION from
     ../plans/Plan.5.md §11 or the trust model working correctly. A proposal that replaces the region
     file format from a plugin, that mints a node per tenant, that lets capacity outrank distance, or
     that shims BukkitScheduler back into existence on Folia is a design regression and must be
     refused. -->

| Property | Why it is not a limitation |
|---|---|
| **A tenant cannot verify anything and trusts its endpoint** | That is the definition of tenancy, not a defect in it. A tenant has no key and no engine; they trust their endpoint exactly as a player trusts any Minecraft server today — which is strictly better than the status quo, where they cannot play at all. The burn-down this *does* carry is making the endpoint auditable **by other nodes** (L-62), never asking a keyless client to check something it cannot |
| **The plugin does not replace `RegionFileStorage` / `ChunkSerializer`** | Writing `.mca` from outside the server's own chunk system is fork territory — MultiPaper needed one. From a plugin it is a corruption vector that the first Paper update turns into data loss. The plugin changes world-access *behaviour* through supported seams instead, and the consequence is a feature: the world folder stays a normal Minecraft world folder a plain Paper server can open with the plugin removed. The escape hatch (a version-pinned NMS adapter) is specified as an optional last resort in [4](Task.4.md), gated behind its own row |
| **Capacity never buys authority** | An endpoint that primaries every region because it is bigger is a central server wearing a costume, and this project exists to not have one. Distance decides; `FULL` custody breaks ties only. Same invariant the project already holds for dedicated servers: special in capacity and availability, never in authority |
| **One endpoint is one node, whatever its player count** | Minting a node per tenant would let an endpoint with forty players claim forty committee seats and own every quorum in its world — a Sybil attack an honest operator would trip over by accident. N tenants grow an endpoint's footprint, never its weight |
| **Nodera regions stay 8×8 and static** | They are units of **authority**: region identity is what leases, epochs, and certificates are attached to. Folia's dynamic merge/split regions are units of **execution** and have no stable identity at all. Adopting Folia's partitioning as Nodera's would destroy the certificate chain |
| **A non-Folia-ready third-party plugin still fails to load on Folia** | That is Folia's rule, and NoderaEndpoint neither adds to it nor works around it. Shimming `BukkitScheduler` back into existence would hand plugins a main thread that does not exist and corrupt world state on the first parallel event. Choosing Folia is choosing parallelism over plugin breadth, and the docs say so rather than implying the plugin removes the trade-off |
| **`NoderaEndpointAPI` is read-only** | A plugin that could write through it would be a second world writer. There is exactly one ([`../README.md`](../README.md) §4.3 rule 5), and plugins already have a supported way to write: the Bukkit API, whose writes are certified rather than suppressed |

---

## Reading guide for the implementing model

- **The three suites exist before the code.** A stage that cannot assert reports `SKIPPED` naming the
  row that blocks it — the discipline `e2e-mobs.sh` already uses for L-60. A suite that asserts
  something no node in the topology can do proves nothing; one that says exactly what is missing is a
  specification.
- **Verify a retirement against the row's exit test**, not against how small the prose "limitation
  today" note looks.
- **ALIGN-1 is the load-bearing invariant.** If it ever fails to hold, most of §B is the wrong shape
  and the category needs re-planning, not patching. It is preflighted in [1](Task.1.md) and asserted
  live in [3](Task.3.md), both before anything depends on it.
- **A refusal beats a degrade** everywhere in this category: a split region, a missing worker in
  `external` mode, a fingerprint mismatch, a cross-region delta with no joint-transfer path. Each
  fails closed with the fix named in the message.
