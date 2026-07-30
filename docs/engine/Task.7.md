# Engine Task 7 — Interference Guard, Chunk Lifecycle, Delegability, Mod Compatibility

<!-- AI-AGENT-INSTRUCTION: `MutationGuard` is the SINGLE write choke point for delegated regions.
     Every new foreign write source must reach it — do not add a bypass, a whitelist, or a
     "trusted caller" shortcut. The normative mod-compatibility contract is COMPATIBILITY.md at the
     repository root; any change to guard behaviour updates that file in the same commit. Keep this
     header's status accurate. -->

**Status:** ✅ COMPLETED (headless; mixins/tickets/fake-player detection → [minecraft 2](../minecraft/Task.2.md))
**Category:** engine · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [engine 4](Task.4.md)
**Consumed by:** [engine 6](Task.6.md), [engine 8](Task.8.md)–[engine 12](Task.12.md), [minecraft 2](../minecraft/Task.2.md)

---

## Goal

Close the correctness hole that makes delegated regions unsafe on real worlds, and define the
mod-compatibility contract. Non-player mutations inside a delegated region — random ticks, fluids,
gravity blocks, fire, mobs, fake players, **other mods** — must never reach the world behind the
committee's back. They are suppressed, or converted into a **certified** `ExternalDelta` that
advances the region's version like any other commit.

## Status detail

Complete headlessly: `MutationGuard`, `InterferenceBuffer`, `InterferenceStats`,
`InterferenceCommitter`, the full `DelegabilityPolicy` reason set, `DelegabilityMonitor` hysteresis,
`ServerAuthorityCertificate` (tag 54), `ExternalDelta` (tag 32), and the normative
`COMPATIBILITY.md`.

Remaining, and delivered by [`minecraft/Task.2.md`](../minecraft/Task.2.md): the three mixins
(`LevelChunkMixin` choke point, random-tick and scheduled-tick suppression), `ChunkTicketService`,
`FakePlayerDetector`, and live acceptance.

The guard's documented async-write rejection now has its live mixin call site — **L-25 is
RETIRED** (2026-07-25; see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)). `LevelChunkMixin`
routes every block write through `BlockWriteGuard`, which reaches `MutationGuard.verdictChecked`
by thread; the engine-side guard semantics stay pinned headlessly by `MutationGuardTest`.

## Dependencies

- [engine 4](Task.4.md) — the pipeline whose mid-batch state the guard must respect.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `MutationGuard` — the single `setBlockState` choke-point classifier | ✅ |
| 2 | `InterferenceBuffer` — coalescing (one CAS guard per position; no-op writes vanish) | ✅ |
| 3 | `InterferenceStats` — tick-window rates | ✅ |
| 4 | `InterferenceCommitter` — buffer → CAS-guarded delta → certificate → version bump | ✅ |
| 5 | `ServerAuthorityCertificate` (tag 54) + `ExternalDelta` (tag 32) | ✅ |
| 6 | `DelegabilityPolicy` full reason set + `DelegabilityMonitor` hysteresis | ✅ |
| 7 | `AsyncActionGate` — the legal off-thread submission path | ✅ |
| 8 | `COMPATIBILITY.md` — the normative mod-compat contract | ✅ |
| 9 | Mixins, `ChunkTicketService`, `FakePlayerDetector`, live acceptance | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**One choke point or none.** A guard with two entry points is not a guard. Every write to a delegated
chunk must pass `MutationGuard`, which classifies it: **PASS** for the vanilla lane outside delegated
regions, **PASS** for writes inside the applier's own scope, **STRICT** block (used in CI), or
**CONVERT** — record it for certified conversion.

**Convert by default, block in CI.** Blocking a foreign write in production breaks other mods
silently; converting it keeps the world coherent and makes the *rate* the signal. CI runs STRICT so a
new bypass fails a test instead of quietly inflating the interference rate in production.

**Held while voting ⇒ `STALE_BASE` is impossible by construction.** The committer holds the buffer
while the pipeline is mid-batch and flushes it after the decision. That ordering, not a retry loop,
is what removes the interference-versus-commit race: an external delta can never be applied against a
base the committee is still deciding on.

**Delegability is a policy with hysteresis, not a boolean.** `ENTITY_PRESENT`,
`NEIGHBOR_UNSUPPORTED`, `FAKE_PLAYER_ACTIVE`, `INTERFERENCE_RATE_HIGH` each demote a region.
Restoration is damped by a cooldown, so a region provably cannot flap between delegable and
non-delegable — an oscillating region would thrash committees and produce exactly the resync storm
this task exists to prevent.

**Asynchrony ends at the gate.** `AsyncActionGate.submit` accepts signed actions from any thread into
a bounded FIFO; the server thread drains it once per tick into the validated lane. So third-party
mods have a *legal* async path, and the guard's rejection of a raw off-thread write is a documented
error naming that path — never a silent block. The contract is published in
[`SDK.md`](SDK.md).

## Files

- `library/java/engine/src/main/java/dev/nodera/coordinator/interference/{MutationGuard,InterferenceBuffer,InterferenceStats,InterferenceCommitter}.java`
- `library/java/engine/src/main/java/dev/nodera/coordinator/{DelegabilityPolicy,DelegabilityMonitor}.java`
- `library/java/core/src/main/java/dev/nodera/core/state/ServerAuthorityCertificate.java`
- `COMPATIBILITY.md` (repository root — normative)

## Testing

- Guard classification, including the applier-scope sanity counter.
- Buffer coalescing: one CAS guard per position; no-op writes disappear.
- The certified-conversion flow: a replica applies the external delta **without voting** and
  re-extracts to the live world root.
- The held-while-voting ordering proof (no `STALE_BASE` by construction).
- Full delegability reason set; monitor hysteresis — immediate revoke, cooldown restore, oscillation
  cannot flap.
- `AsyncMutationApiTest`: 4-thread × 50-action per-submitter FIFO drain; the off-thread direct write
  gets the documented error while applier-scope and undelegated writes stay legal.

## Acceptance criteria

1. ✅ Every foreign write reaches the guard and is classified.
2. ✅ Converted writes become certified `ExternalDelta`s that advance the version.
3. ✅ Interference `STALE_BASE` is impossible by construction, proven by an ordering test.
4. ✅ Delegability cannot oscillate.
5. ✅ `COMPATIBILITY.md` states the event ordering, fake-player, palette, ticket, and async-write
   rules normatively.
6. ⏳ Live: the three mixins, chunk tickets, fake-player detection, and a normal-world soak with a
   near-zero interference probe — [minecraft 2](../minecraft/Task.2.md).

## Limitations

- **L-25** (async writes by other mods under the guard) — **RETIRED 2026-07-25**; see
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). The live mixin call site landed in the
  minecraft category; the guard's headless semantics remain pinned here by `MutationGuardTest`.
