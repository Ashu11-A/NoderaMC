# Engine Task 2 — Deterministic Region Engine

<!-- AI-AGENT-INSTRUCTION: This is the component the whole project bets on. Any change here must
     preserve bit-for-bit reproducibility: no wall clocks, no ambient entropy, no unordered
     iteration, no IO, no floats in hashed state. If you add a rule, add its determinism property
     test in the same commit and bump RULES_VERSION when behaviour changes — a peer on an old rules
     version must REFUSE to validate, never silently diverge. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** engine · **Owns:** — (L-52 RETIRED) · **Last audit:** 2026-07-28
**Depends on:** [engine 1](Task.1.md)
**Consumed by:** [engine 3](Task.3.md)–[engine 12](Task.12.md), [network 4](../network/Task.4.md), [minecraft 2](../minecraft/Task.2.md), [worker 4](../peer/Task.4.md)

---

## Goal

A pure-Java, bit-for-bit deterministic simulator for one region: given
`(RegionSnapshot, ActionBatch, RegionExecutionContext)` produce `(RegionDelta, StateRoot)` —
identical on every JVM, every OS, every run. The first rule set is flat-world block place/break on a
restricted palette; the parity program (tasks 8–12) widens it without ever widening the determinism
envelope.

## Status detail

Landed and continuously extended. `library/java/engine` carries 508 XML-reported test cases, including 3
jqwik `@Property` determinism tests and the ArchUnit forbidden-API ban.

The rule set has grown well past the MVP palette: containers, redstone (palette v4 including
pressure plates and sticky pistons), fluids, random ticks, gravity, fire, lighting, observers and
quasi-connectivity, daylight sensors, mob AI and spawning, combat vitals, movement, portals,
commands, and the third-party rule-pack SDK. `RULES_VERSION` is **7** and the palette literal remains
`palette.v6` (v5 added obsidian for the L-2 fluid-interaction lane; v6 added farmland + wheat for
the L-1 farm half; rules v7 fixes complete, bounded dense/uniform halo-fluid seeding without changing
the palette).

L-52 is retired. Signed Q32.32 multiplication now lives once in core's `FixedPoint`; immutable
entity motion copies live on `PersistedEntityState`; and packed chunk-coordinate keys live once in
`ChunkKey`. This is behaviour-preserving: no encoding method, field order, tag, version, or rules
version changed.

## Dependencies

- [engine 1](Task.1.md) for the types, canonical encoding, and hashing.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `RegionEngine` seam + `FlatWorldRegionEngine` — pure `execute()` | ✅ |
| 2 | `FlatWorldRules` — the MVP block place/break rule set | ✅ |
| 3 | `DeterministicRandom` — L64X128MixRandom seeded from `StableHash` | ✅ |
| 4 | Halo model: read-only ring; a halo **write** is a hard throw, never a silent no-op | ✅ |
| 5 | `registryFingerprint` + `rulesVersion` guards — refuse, never diverge | ✅ |
| 6 | ArchUnit forbidden-API ban scoped to `dev.nodera.simulation..` | ✅ |
| 7 | Rule-pack SDK: `PackRules`, `PackDelegatingRuleSet`, `RulePackRegistry` | ✅ |
| 8 | Shared deterministic arithmetic, entity-copy, and chunk-key helpers (L-52) | ✅ |

## Design

**Purity.** `execute()` is a function. It reads a snapshot and a batch, writes nothing, touches no
clock, and returns a delta plus a root. Everything that looks like a side effect (scheduled ticks,
pending fluid updates, entity spawns) is **state in the root**, not a queue on the side — that is
what lets a delta boundary or a failover resume without losing behaviour.

**A halo write is a crash, not a warning.** The halo is the one-chunk read-only ring around a
region. Folia's hard `TickThread` ownership guards are the precedent: a fail-fast ownership
violation is debuggable, a silently tolerated one produces a divergence hours later in an unrelated
region. So the engine throws.

**Version guards refuse rather than diverge.** `rulesVersion` and `registryFingerprint` are checked
before execution. A peer running different rules cannot produce a comparable root, so it must decline
to validate — a refusal is a visible, diagnosable event; a silent mismatch is an unexplained
divergence storm. The rule-pack SDK made this sharper: `FlatWorldRegionEngine` **derives** its
expected fingerprint from the registry it will actually run, so it cannot be handed a number that
disagrees with its own behaviour.

**Randomness is a pure function of committed state.** `DeterministicRandom` is seeded per tick from
`StableHash` over consensus inputs. Two replicas draw the same numbers because they hashed the same
bytes — and any rule that *conditionally* draws must draw a fixed number of times regardless of the
branch, or the streams desynchronise. This constraint is load-bearing for tasks 10 and 11.

## Files

- `library/java/engine/src/main/java/dev/nodera/simulation/engine/FlatWorldRegionEngine.java`
- `library/java/engine/src/main/java/dev/nodera/simulation/DeterministicRandom.java`
- `library/java/engine/src/main/java/dev/nodera/simulation/rules/`
- `library/java/core/src/main/java/dev/nodera/core/state/{FixedPoint,ChunkKey,PersistedEntityState}.java`
- `library/java/engine/src/test/java/dev/nodera/simulation/{ForbiddenApiTest,DeterminismPropertyTest}.java`

## Testing

- **Property tests (jqwik):** same input ⇒ same root; permuted input ⇒ different root; order
  independence where the rule claims it.
- **Golden fixtures:** encoded snapshots/deltas byte-compared; every historical divergence is a
  committed replay fixture.
- **ArchUnit:** the forbidden-API ban runs in CI (`--release 21` bytecode keeps ArchUnit's ASM able
  to parse the classes; the test is enabled, not skipped).
- **Negative determinism tests:** drop a piece of state from the hash and prove divergence *is*
  detected — a root that cannot see a difference would certify one.
- **Shared helpers:** `FixedPointTest` checks 10,000 arbitrary signed products against a
  full-precision `BigInteger` oracle; `ChunkKeyTest` checks 10,000 signed-coordinate round trips;
  `EntityLaneTypesTest` byte-compares motion copies with direct pre-refactor construction.

## Acceptance criteria

1. ✅ Identical `(snapshot, batch, context)` produces byte-identical `(delta, root)` across JVMs,
   OSes, and runs.
2. ✅ A halo write throws.
3. ✅ A peer with a different `rulesVersion`/`registryFingerprint` refuses to validate.
4. ✅ The forbidden-API ban is green and enabled in CI.
5. ✅ Adding a rule pack cannot change base-block behaviour or the root of a packless node.

## Limitations

L-52 is RETIRED in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). The *scope* of the rule set is
what tasks 8–12 widen, and their limitation rows live with them.
