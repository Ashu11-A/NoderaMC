# 06 — The Viewer

The viewer is a web app at `https://spark.lucko.me/`. Nodera uses it, but **only in its offline
mode** — we never upload. This document covers both, and is explicit about which parts we
deliberately do not use and why.

## The upload flow — what we do not do

The default `/spark profiler stop` does this:

1. Serialise the sampler to a `SamplerData` protobuf.
2. `POST` it to **bytebin** (`https://spark-usercontent.lucko.me/`) with content type
   `application/x-spark-sampler`.
3. Print `https://spark.lucko.me/<key>` as a clickable chat link.
4. Record the URL in `activity.json`.

**Nodera never does this.** A `.sparkprofile` contains our full class and method names, the
server configuration, the installed mod inventory with versions, and the host's CPU model, OS
string and memory figures. `stop` without `--save-to-file` publishes all of it to a third-party
host under a short, guessable-length key. Every Nodera capture therefore passes `--save-to-file`,
and `scripts/lib/spark.sh` has no code path that uploads.

The same reasoning rules out `/spark profiler open`, the live viewer: it opens a websocket to
`spark-usersockets.lucko.me` and streams the profile as it is collected.

This is a policy choice about *our* data, not a criticism of the service — it is well built, and
its key store is even end-to-end encrypted for live sessions (`TrustedKeyStore`, with new browser
clients approved via `/spark profiler trust-viewer --id <id>`).

## The offline path — what we do

**Drag a `.sparkprofile` file onto `https://spark.lucko.me/`.**

The homepage accepts dropped `.sparkprofile` and `.sparkheap` files and parses them **entirely
client-side**. Nothing is uploaded, no key is minted, no link exists. You get the full viewer — the
flame graph, the sources view, every filter — against a file that never left the machine.

```bash
# after any profiled suite run
ls run/results/e2e-profile/*/spark/*.sparkprofile
# then drag one onto https://spark.lucko.me/ in a browser
```

For everything that does not need a browser, `scripts/lib/sparkprofile.py` reads the same file
([07 — Data Format](./07-data-format.md)).

## The views

Switch with the 👁 button in the top controls bar.

### All

The default: the entire profile as one expandable tree, per thread. Best for following a specific
call path you already suspect.

### Flat

The slowest 250 method calls at the top level. Two independent toggles:

| Toggle | Options | Meaning |
|---|---|---|
| Display | **Top Down** / **Bottom Up** | callers-first, or callees-first |
| Sort | **Total Time** / **Self Time** | "time in this method *and* its sub-calls", or "time in this method's own code" |

The Self Time sort is the fastest way to find a single expensive method. It is the same quantity
our reader calls SELF.

### Sources

The per-mod/plugin view — the one this whole integration exists for. One filtered tree per source,
showing all outgoing calls made by that source at the top level. Merge/Separate modes control
whether identically-signed calls from different callers are combined.

Read [05 — Source Attribution](./05-source-attribution.md) before drawing conclusions from it.

It also renders an **"Other"** section listing mods and plugins that are installed but never
appeared, with the noun switched to "mods" on Fabric/Forge/NeoForge:

> "The following other {mods|plugins} are installed, but didn't show up in this profile. Yay!"

Our reader prints the same list as `installed but never sampled:` — it is how you distinguish
"cheap" from "never loaded".

### Flame Graph

Flame icon, or right-click → "View as Flame Graph".

> "The **width** of each node in this view **corresponds** to the portion of time spent executing
> it."

Click a node to focus it; "Exit Flame View" returns. Best for seeing shape at a glance — a wide
plateau is one expensive thing, a jagged comb is many small ones.

### Graph

Plots the per-window statistics (TPS, MSPT, CPU) across the profile's duration, so you can line up
a spike in the timeline with what the tree says was running.

## Other viewer features

| Feature | Notes |
|---|---|
| Deobfuscation mappings | Auto-detected, with a manual override dropdown. Irrelevant for us — NeoForge 1.21.1 runs Mojang-named at runtime, so frames are already readable. |
| Bookmarks | `alt`+click or right-click → "Toggle bookmark". Encoded into the URL, so a shared link opens with them highlighted and expanded. Only useful for uploaded profiles. |
| Search | Filters the tree by frame name. |
| Info points | ⓘ markers with community-editable explanations of well-known hot frames. Genuinely useful for vanilla frames you do not recognise. |

## ⚠ "Only ticks over" is not a viewer filter

A common misconception worth stating plainly: **`--only-ticks-over` is a capture-time flag.** There
is no control in the viewer to filter an existing profile down to slow ticks. The viewer only
*displays* the metadata about it:

- `tick_length_threshold` — the millisecond threshold used
- `number_of_included_ticks` — how many ticks qualified
- `number_of_ticks` — how many ticks elapsed in total

If you wanted a lag-spike profile and did not pass the flag, you must capture again. Our reader
prints all three on the header line when the aggregator was `ticked`.

## The raw-data API — also not used

For completeness, since it appears in the docs and someone will ask:

```
GET https://spark.lucko.me/<code>?raw=1                        # JSON metadata
GET https://spark.lucko.me/<code>?raw=1&path=metadata.platform # JSONPath filter
GET https://spark.lucko.me/<code>?raw=1&full=true              # everything
GET https://spark-usercontent.lucko.me/<code>                  # raw protobuf
```

with upstream's own request attached:

> "If you really-really want the actual sampler/heapdump data too, firstly, please consider
> downloading/parsing the **raw** data yourself. It saves resources at my end!"

These endpoints only work for profiles that were **uploaded**, which ours are not. The reference
parser is `lucko/spark2json`; ours is `scripts/lib/sparkprofile.py`, which reads the local file
directly and needs no service at all.

## Self-hosting

If a future Nodera deployment wanted shareable links without a third party, spark supports
pointing `viewerUrl`, `bytebinUrl` and `bytesocksHost` at your own instances
([08 — Configuration](./08-configuration.md)); bytebin and bytesocks are both open source. This is
recorded as a possibility, not a plan — the offline path costs nothing and needs no infrastructure.

---

**Previous:** [05 — Source Attribution](./05-source-attribution.md) ·
**Next:** [07 — Data Format](./07-data-format.md) ·
**Index:** [README](./README.md)
