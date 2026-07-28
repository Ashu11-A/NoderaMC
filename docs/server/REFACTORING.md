# Server — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: This register tracks duplication and refactor debt in java/paper-plugin.
     Source: the jscpd run (build/jscpd/jscpd-report.json) plus manual observation. The category is
     largely unwritten (5 main + 3 test classes today), so the register is intentionally short — it
     grows when implementation lands. A row retires when the duplicated block is gone, with the
     commit that removed it; "permanent" is banned here too. -->

**Category:** server · **Last audit:** 2026-07-28 · Source: `build/jscpd/jscpd-report.json`
(filtered to `java/paper-plugin/`) + manual review · Open candidates: **0**

The Paper/Folia endpoint is scaffolded, not built out — 5 main classes (`NoderaEndpointPlugin`,
`EndpointConfig`, `EndpointPlatform`, `ControlClient`, `EndpointPeerLink`) and 3 test classes. The
jscpd run over the whole Java tree reports **0 duplicated blocks** touching `java/paper-plugin/`, and
manual review agrees: the five classes are single-purpose and share no boilerplate that a shared
helper would shrink. So the register opens empty.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---|---|---|---|
| (none yet — category scaffolded) | — | — | — | — |

Notes worth keeping for when implementation arrives:

- `EndpointConfig.Scalars` (the flat-YAML reader in `EndpointConfig.java:147`) is the one piece that
  could grow Duplication debt later — every later task adds keys, and a second hand-rolled parser
  anywhere in the module would be the obvious clone. Plan if it appears: extract a shared `YamlScalar`
  helper **only** when a second call site shows up, not preemptively.
- The control-socket framing in `ControlClient.java:67` is deliberately the worker's own one-line
  protocol. If a future task adds a second control client, that framing becomes the shared seam — but
  there is exactly one call site today, so there is nothing to factor yet.

## Sequencing

None — wait for implementation. The first realistic candidate lands with [server 3](Task.3.md)
(region/Folia mapping helpers) or [server 5](Task.5.md) (the Bukkit event mirror, which is the Bukkit
twin of `dev.nodera.mod.server.entity.EntityCaptureBridge` and the most likely place for cross-package
duplication). When a clone appears, it enters this table with a file, a duplication percentage from
jscpd, the sibling it duplicates, and a one-line refactor plan; the table stays sorted by percentage.
