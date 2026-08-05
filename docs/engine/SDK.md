# Writing a Nodera rule pack

<!-- AI-AGENT-INSTRUCTION: This is the public contract for third-party mods (Task 16 / L-21). Keep
     it in sync with dev.nodera.simulation.rules.{RulePack,PackRules,RulePackRegistry,
     PackDelegatingRuleSet} and dev.nodera.coordinator.interference.AsyncActionGate. Every rule
     stated here must be enforced by code, not by convention — if a rule cannot be enforced, say so
     explicitly rather than implying it is. -->

A **rule pack** is a mod's deterministic contribution to Nodera's validated lane: extra blocks, and
the code that decides what they do. Packs are how a modded world stays *delegable* instead of
falling back to the vanilla lane the moment a non-vanilla block appears
([`LIMITATIONS.md`](LIMITATIONS.md) L-21).

## The shape of a pack

```java
public final class ExampleMachines implements RulePack {

    public static final int MACHINE      = 1000;   // ids >= 1000 belong to packs
    public static final int MACHINE_LIT  = 1001;

    @Override public String namespace() { return "examplemod"; }

    @Override public List<PackPaletteEntry> paletteEntries() {
        return List.of(new PackPaletteEntry(MACHINE,     "machine"),
                       new PackPaletteEntry(MACHINE_LIT, "machine_lit"));
    }

    /** Bump this on ANY behavioural change. It is the pack's version, not its build number. */
    @Override public long semanticFingerprint() { return 0x0001L; }

    @Override public Optional<PackRules> rules() { return Optional.of(new MachineRules()); }
}
```

A pack that only contributes ids can stop there — `rules()` defaults to empty, and the pack still
carries an identity.

## What the platform guarantees you

- **Your ids are yours.** The base palette is frozen below `RulePackRegistry.PACK_ID_FLOOR` (1000),
  and registration refuses a duplicate id across packs. Ownership is therefore a function, and
  `PackDelegatingRuleSet` routes an action to your `PackRules` only when the block it targets is one
  you declared. You cannot intercept a vanilla block, and no other pack can shadow yours.
- **Installation order never matters.** The registry sorts packs by namespace, so per-tick execution
  order is identical on every peer no matter what order the mod loader happened to use.
- **A version mismatch is a refusal, not a corruption.** Every pack's `(namespace, entries,
  semanticFingerprint)` is folded into `RulePackRegistry.combinedFingerprint`, which is the number
  each committee member pins. A member missing your pack — or running a different build of it —
  computes a different fingerprint and the engine refuses to validate with them. A packless registry
  is deliberately identical to no SDK at all, so merely shipping the SDK does not fork the network.

## What you must guarantee

`PackRules.validate` / `apply` / `tick` must be **pure functions of their arguments**:

| Rule | Why |
|---|---|
| No clocks, no IO, no network | Replicas run your code at different wall-clock times |
| No shared mutable statics | Two replicas would see different state |
| No floating-point in anything reaching hashed state | JVM float math is not reproducible across hardware, so every continuous quantity in hashed state is Q32.32 fixed point ([envelope A-5](LIMITATIONS.md)) |
| Only the supplied `DeterministicRandom` | Any other source diverges immediately |
| Iterate in a canonical order | `HashMap` iteration order is not a contract |
| Write blocks through `MutableRegionState.setBlock` | Direct writes bypass the mutation buffer |

**This is not sandboxed, and the failure mode is honest rather than silent.** A pack that breaks
determinism does not corrupt the world: the members running it compute a different root, their votes
stop matching, and the committee refuses them. But it does break *your* pack's players, so the
burden is yours.

## Mutating the world from your own thread

Do not. Off-thread direct writes into a delegated region are refused by `MutationGuard` with an
`AsyncWriteException` naming this page — deliberately a loud, actionable error rather than a silent
block-or-convert, because a silently-dropped write is a bug you find days later in a desync report.

The legal path is `AsyncActionGate.submit(envelope)`: it accepts signed actions from **any** thread
into a bounded FIFO, and the server thread drains it once per tick into the validated lane.
Asynchrony ends at the gate; determinism begins at the drain. Per-submitter FIFO order is preserved,
so your own actions stay in the order you submitted them.

```java
// From your executor, worker pool, or event callback — any thread:
asyncGate.submit(signedEnvelope);
```

## Registering

Register at startup, before the registry is frozen:

```java
RulePackRegistry registry = new RulePackRegistry();
registry.register(new ExampleMachines());
registry.freeze();

RegionEngine engine = new FlatWorldRegionEngine(
        FlatWorldRules.RULES_VERSION, registry, hashService);
```

The engine **derives** the expected fingerprint from the registry it is going to run, so it can
never be handed a number that disagrees with its own behaviour.

## Testing your pack

The bar a pack should meet before shipping is the one the built-in rules meet: run the same actions
through two independently-constructed registries and assert the resulting `StateRoot`s are equal.
`PackRuleExecutionTest` is the worked example — replica-identical roots, a pack rejection leaving the
world untouched, and canonical tick ordering across reversed installation order.
