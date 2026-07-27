# 05 — Source Attribution

**This is the document that answers "how does spark report a percentage per mod or plugin".**
Read it before quoting one of those percentages at anybody, because the number is real but it does
not mean quite what it looks like it means.

Attribution happens in two stages, in two different places:

1. **On the server, at export time** — spark resolves each sampled frame's class to the name of
   the mod or plugin that owns it, and ships those mappings alongside the call tree.
2. **In the viewer (or our reader), at read time** — the tree is walked and time is aggregated per
   source name.

Neither stage produces "a percentage per plugin" on its own. That number is constructed at stage
2, and there is more than one defensible way to construct it.

---

## Stage 1 — `ClassSourceLookup`

> "A function which defines the source of given `Class`es or (Mixin) method calls."
> — `ClassSourceLookup` javadoc

```java
public interface ClassSourceLookup {
    @Nullable String identify(Class<?> clazz) throws Exception;
    default @Nullable String identify(MethodCall methodCall)       { return null; }
    default @Nullable String identify(MethodCallByLine methodCall)  { return null; }

    ClassSourceLookup NO_OP = clazz -> null;

    static ClassSourceLookup create(SparkPlatform platform) { … }  // NO_OP on failure
}
```

Three overloads, in increasing specificity: by class, by exact method (class + name + descriptor),
and by class + line number. A platform implements whichever it can answer.

### Generic strategies

| Strategy | How it identifies |
|---|---|
| `ByClassLoader` | Walks the classloader chain upward (`loader = loader.getParent()`) until one is recognised. |
| `ByUrl` | Resolves a `file:` or `jar:` URL to a path, then returns the **jar filename minus `.jar`** — or `null` if it is not a jar. |
| `ByFirstUrlSource` | `ByClassLoader` + the loader's first URL. |
| `ByCodeSource` | `clazz.getProtectionDomain().getCodeSource().getLocation()` → `ByUrl`. |

### Per-platform implementations — they differ enormously

**Bukkit / Paper / Folia — by plugin classloader. Precise.**

`BukkitClassSourceLookup extends ByClassLoader` reflects the plugin field out of the classloader
and returns `plugin.getName()`:

- `org.bukkit.plugin.java.PluginClassLoader#plugin`
- on Paper, `io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader#loadedJavaPlugin`

Every Bukkit plugin gets its own classloader, so this is exact — and it is why our endpoint shows
up as exactly `NoderaEndpoint`.

**NeoForge / Forge — by JPMS module name. Coarse.**

```java
public String identify(Class<?> clazz) {
    if (clazz.getClassLoader().getClass().getName().equals("cpw.mods.modlauncher.TransformingClassLoader")) {
        String name = clazz.getModule().getName();
        return name.equals("forge") || name.equals("minecraft") ? null : name;
    }
    return null;
}
```

That is the whole implementation. Note what it does **not** do: no mixin awareness, no method-level
resolution, and `minecraft` and `forge` are deliberately returned as `null` — vanilla and loader
frames are *intentionally* unattributed rather than labelled.

**Fabric — the only mixin-aware one.**

`FabricClassSourceLookup` builds a path→mod-id map from `FabricLoader.getAllMods()`, then:

- `identify(MethodCall)` reflects the method, reads the `@MixinMerged` annotation, and maps the
  mixin config back to a mod id via `IMixinConfig.getMixinPackage()` /
  `getDecoration(FabricUtil.KEY_MOD_ID)`.
- `identify(MethodCallByLine)` uses **SMAP** debug information (`SourceMap.getReverseLineMapping()`)
  to map a line in a transformed class back to the mixin source file it came from.

This is worth knowing precisely because it tells you what NeoForge does *not* have.

### Resolving a name to a `Class<?>`

Stack frames carry strings. To call `identify(Class<?>)` spark must first find the class, via
`ClassFinder`:

- `InstrumentationClassFinder` — the good one, needs a dynamically loaded Java agent
- `FallbackClassFinder` — the degraded one

This is the entire reason `-XX:+EnableDynamicAgentLoading` is passed on every Nodera dev run and
profiled server ([03](./03-profiler-engines.md)). Without it, attribution quietly gets worse.

### The visitor, and what ships in the proto

`ClassSourceLookup.VisitorImpl` breadth-first walks every `ThreadNode`'s tree and fills three maps:

```java
SourcesMap<String>           classSources;   // "com.foo.Bar"                     -> source
SourcesMap<MethodCall>       methodSources;  // "com.foo.Bar;method;(Lx;)V"       -> source
SourcesMap<MethodCallByLine> lineSources;    // "com.foo.Bar;123"                 -> source
```

Per node it always attempts the class lookup, then *either* the method lookup (when a method
descriptor is present — the async engine) *or* the line lookup (when a line number is present —
the Java engine). `null` results are dropped; **any thrown exception yields `null` silently**.

They become proto fields on `SamplerData`:

```proto
map<string, string> class_sources  = 3;
map<string, string> method_sources = 4;
map<string, string> line_sources   = 5;
```

and, on `SamplerMetadata`, the full installed inventory regardless of whether it was sampled:

```proto
map<string, PluginOrModMetadata> sources = 13;   // name, version, author, description, builtin
```

That last one is how the viewer (and our reader) can say "these mods are installed but never
appeared in this profile".

---

## Stage 2 — turning mappings into percentages

### What the viewer does: `SourceViewGenerator`

For each known source, the viewer walks every thread tree collecting the **topmost** nodes
belonging to that source — recursing past non-matching nodes, but *not* descending once a match is
found. Each source's total is the sum of those whole subtrees, and sections are sorted descending.

Two merge modes, from the code's own comments:

- **Merge** — "Method calls with the same signature will be merged together, even though they may
  not have been invoked by the same calling method."
- **Separate** — "Method calls that have the same signature, but that haven't been invoked by the
  same calling method will show separately."

Upstream describes the view as:

> "In the sources view, a separate profiler tree will be shown for each plugin/mod in the profile.
> The tree is filtered and at the top level, shows all outgoing calls made by the source."

There is also a hard-coded exclusion whose comment reads, verbatim,
`// forgive carpet replacing the entire tick loop` — a mod that replaces the tick loop would
otherwise be attributed 100% of everything. Bear that in mind when a number looks too good.

### What our reader does: `scripts/lib/sparkprofile.py`

It computes **both** measures, because they answer different questions:

| Measure | Definition | Answers |
|---|---|---|
| **SELF** | A node's sampled time minus the sum of its children's, charged to that node's source. | "Whose bytecode was actually executing?" |
| **SUBTREE** | The whole subtree under each topmost owned frame — spark's own Sources measure. | "How much tick time did this mod *cause*?" |

They differ by a lot, and both are honest:

- A mod that calls `World.getBlockAt()` in a loop has small SELF (the loop) and large SUBTREE (the
  vanilla chunk lookups it triggered). The cost is real and it is the mod's fault; SUBTREE says so.
- SUBTREE **double-counts** when one mod calls another, and includes vanilla work that would have
  happened anyway. SELF never double-counts.

Quote SELF when you are hunting for a method to optimise. Quote SUBTREE when you are deciding
whether a feature is affordable.

---

## The limits — read this part

Attribution is genuinely useful and it is not a ledger. Concretely:

**It is not a pie chart, and the columns do not sum to 100%.** SELF plus unattributed does sum to
the sampled total; SUBTREE does not sum to anything in particular.

**Unattributed is not overhead.** On NeoForge it is, in descending order of volume: Minecraft
itself, NeoForge, the JDK, native frames, and lambdas. It is the *majority* of any healthy profile
and that is correct.

**On NeoForge, mixin-injected code is attributed to the host class — i.e. to nobody.** A mixin our
mod injects into `ServerLevel` executes in a class whose module is `minecraft`, which the lookup
deliberately maps to `null`. If Nodera ever moves logic into mixins, that logic becomes invisible
to this measurement. Only Fabric solves this, and we are not on Fabric.

**Our whole core attributes to one name.** The NeoForge mod is a fat jar registering the single FML
mod `nodera`, so `dev.nodera.engine.*`, `dev.nodera.network.*` and `dev.nodera.mod.*` all land in
one bucket. The per-*module* breakdown has to come from the frame names within that bucket, which
is exactly what `sparkprofile.py --source nodera` prints. The same is true of the Paper plugin,
where `core` is shaded into `nodera-endpoint.jar` — though there it is arguably the right answer,
since from the server's perspective that jar *is* the plugin.

**Lambdas, synthetic classes and reflection go unattributed.** `ClassFinder` usually cannot resolve
`Foo$$Lambda$123/0x…`, and `jdk.internal.reflect.*` frames belong to nobody by construction. Code
that is heavily lambda-based under-reports.

**A thrown lookup degrades the whole thing silently.** If `createClassSourceLookup()` throws, spark
logs "Failed to create ClassSourceLookup" and substitutes `NO_OP` — every frame unattributed, no
other symptom. An empty Sources view means "check the log", not "no mod used any time".

**Time is wall-clock, not CPU.** The async engine profiles the `wall` event, so a blocked thread
accrues time. On a `--thread *` capture this is why parked pools dominate — see
[13](./13-reading-a-profile.md).

---

## What this means in practice for Nodera

| Platform | Attribution quality | Source name |
|---|---|---|
| Paper / Folia | **Exact** — per plugin classloader | `NoderaEndpoint` |
| NeoForge | **Good, coarse** — one bucket for the whole mod | `nodera` |
| NeoForge, mixin code | **None** | attributed to `minecraft` → `null` |

Both were verified live in this repository: a Paper capture attributed a deliberately CPU-burning
test plugin to `Burner → dev.burner.BurnerPlugin.burn`, and a NeoForge dedicated-server capture
under a two-player entity-lane drive attributed sampled time to `nodera`. `scripts/e2e-profile.sh`
asserts the latter on every run, because a profile in which our own mod never appears looks exactly
like a profile of a very fast mod — and is almost never that.

---

**Previous:** [04 — Commands & Permissions](./04-commands.md) ·
**Next:** [06 — The Viewer](./06-viewer.md) ·
**Index:** [README](./README.md)
