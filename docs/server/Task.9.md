# Server Task 9 — Live Acceptance: Mixed-Client Suites + CI

<!-- AI-AGENT-INSTRUCTION: The three suites EXIST NOW, ahead of the code, and report SKIPPED at their
     jar preflight naming L-61 — the same discipline e2e-mobs.sh already uses for L-60. A suite that
     asserts something no node in the topology can do proves nothing; a suite that says exactly what
     is missing is a specification. Assertions come from LOGS and CONTROL SOCKETS, never from GUI
     automation. Every suite launches through scripts/lib/e2e-main.sh + scripts/lib/e2e-server.sh —
     no suite starts a service itself. Keep this header accurate. -->

**Status:** 🚧 IN PROGRESS (suites committed and green for the built subset — enable, ALIGN-1, external link; the mixed-client headline stages skip on open rows)
**Category:** server · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [server 8](Task.8.md), [minecraft 1](../minecraft/Task.1.md)
**Consumed by:** every other server task's exit test

---

## Goal

Three scripted live suites, on the shared launcher and in the `e2e-live` matrix, that prove the
headline claim rather than describe it:

> **An unmodified client and a modded client, in the same world, one joining directly at the endpoint
> and one through the NoderaMC network, with an item passing between them exactly once.**

## Status detail

**The three suites, the launcher half, the vanilla-bot driver, and the CI matrix entries are all
committed** (`scripts/e2e-endpoint.sh`, `scripts/e2e-folia.sh`, `scripts/e2e-plugins.sh`,
`scripts/lib/e2e-server.sh`, `scripts/lib/vanilla-bot.py`, entries in `scripts/run-tests.sh` and
`.github/workflows/e2e-live.yml`). They run **green for the built surface**:

- `e2e-endpoint.sh` E1–E4 against real Paper 1.21.1: the plugin enables, refuses to disable itself,
  links its external worker, hands it the world, and survives a SIGKILL of the server JVM (L-71's
  exit).
- `e2e-folia.sh` against a real Folia: ALIGN-1 passes at the platform default and **refuses** at
  grid-exponent 2.
- `e2e-plugins.sh`: co-existence with whatever corpus an operator staged.

The **headline stages** (P2–P8, F2–F6, the WorldEdit certification) still report `SKIPPED` naming the
open row that blocks each — the in-process peer ([server 2](Task.2.md) deliverables 1/2/4), the
tenant lane ([server 6](Task.6.md)), the capture lane ([server 5](Task.5.md)) — and the suite exits 0
so a nightly run reads "not built yet", never "broken". L-61 (no plugin jar) is retired; the remaining
skips name L-62, L-64, L-65, L-66, L-67, L-68, L-69, L-70.

## Dependencies

- [server 8](Task.8.md) — everything the headline stages assert.
- [minecraft 1](../minecraft/Task.1.md) — the Xvfb harness, the quick-play client launch, and the
  shared launcher these suites extend rather than duplicate.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `scripts/lib/e2e-server.sh` — Paper/Folia staging, plugin install, vanilla-bot driver | ✅ |
| 2 | `scripts/lib/vanilla-bot.py` — the unmodified-client driver (vanilla protocol, offline mode) | ✅ |
| 3 | `scripts/e2e-endpoint.sh` — the mixed-client drive | ✅ (built-subset stages green; headline stages skip) |
| 4 | `scripts/e2e-folia.sh` — parallel regions, ALIGN-1 live, zero thread-context violations | ✅ (ALIGN-1 stages green; parallel-region stages skip) |
| 5 | `scripts/e2e-plugins.sh` — the compatibility corpus | ✅ (co-existence stage green; WorldEdit-certification stage skips) |
| 6 | `scripts/run-tests.sh` — the three suites in the canonical batch | ✅ |
| 7 | `.github/workflows/e2e-live.yml` — three matrix entries + the Paper/Folia download step | ✅ |
| 8 | Every stage above asserting instead of skipping | ⬜ (headline stages skip on open rows) |

## Design

### Why the unmodified client is a protocol bot, not a launched game

The modded client is a real NeoForge client launched through Gradle quick play — that harness exists
and works. The unmodified client deliberately is **not** a second launched game:

- a vanilla client needs a Mojang launcher, an asset index, and an account, none of which belong in
  CI, and none of which the modded harness needs;
- two full Minecraft clients plus a Paper server plus three workers on one runner is a memory
  problem, not a test;
- and none of it buys anything, because **every assertion in this suite comes from logs and control
  sockets**, exactly as in every other suite. Nothing is read off a screen.

So the unmodified client is `scripts/lib/vanilla_bot.py`: a Minecraft-protocol client that performs
the handshake, offline-mode login, and play-state exchange, and can move, chat, run a command, and
drop an item. It is *more* faithful to the thing under test than a launched client would be, because
it proves the endpoint is reachable **by anything that speaks the protocol**, with no NeoForge
anywhere in the process.

`NODERA_VANILLA_CLIENT=<command>` swaps in a real vanilla client for anyone who has one staged; the
suite drives it identically.

### `e2e-endpoint.sh` — the headline drive

| Stage | What it proves |
|---|---|
| **P0** | The standard topology (2 player slots · 1 tracker · 1 rendezvous · 3 peers) plus a **Paper** server with `nodera-endpoint` installed |
| **P1** | The endpoint boots its peer, adopts or publishes the world, announces it, and reports `custody: FULL` on its control socket |
| **P2** | The **modded** client joins **through the NoderaMC network** — resolved from the tracker, not from a hardcoded address — and is a session member **with its own key** |
| **P3** | The **unmodified** client joins **directly at the endpoint's game port**, with nothing installed |
| **P4** | Both are in the same world: the endpoint's player list holds both; the modded client is in membership, the vanilla player is a **tenant** with a stable id and is *not* in membership |
| **P5** | **The drive:** the tenant drops an item, the modded player picks it up, and it is credited **exactly once** — no vanish, no dupe, across the two client classes |
| **P6** | The validated item's projection neither despawned nor drifted over the hold window (L-69) |
| **P7** | On a password-protected world: a wrong password never reaches the world and no plaintext appears in any log (L-68) |
| **P8** | A client with a mismatched registry fingerprint is refused, and the message names the mod (L-70) |

P5 is the assertion the whole category exists for. Everything before it is setup and everything after
it is hardening. **E1–E4 (the built-subset) are green; P1–P8 skip on the open rows above.**

### `e2e-folia.sh` — parallelism, proven rather than argued

| Stage | What it proves |
|---|---|
| **F0/F1** | The stack on a **Folia** server; ALIGN-1 preflight passes at `grid-exponent = 4` |
| **F2** | Two tenants ~600 km apart force **two Folia regions**; `/nodera regions --folia` maps every Nodera region to exactly one of them |
| **F3** | Both regions commit **concurrently** — two certificates with overlapping wall-clock windows, which a single-threaded server cannot produce |
| **F4** | A `world.save()` on one region appears as a new archive version on another peer |
| **F5** | A delegated region under sustained redstone load commits for five minutes with the resync count under threshold (L-67) |
| **F6** | **Zero** thread-context violations: no `May not access .* off-thread`, no `ensureTickThread`, no `Cannot recursively operate in the regioniser` anywhere in the log |

F6 is the one that matters most and it is a grep, not a judgement call. A thread-context violation on
Folia halts the scheduler and stops the server, so it is both the likeliest failure mode and the
loudest. **F0/F1 (ALIGN-1) green; F2–F6 skip on the open rows.**

### `e2e-plugins.sh` — compatibility against real plugins

Loads the pinned corpus, then drives PC-1…PC-3: a WorldEdit `//set` in a delegated region must be
**certified** rather than suppressed or silently reverted; CoreProtect must still log every block
change; the log must be clean. On Folia the corpus narrows to the Folia-ready members and the suite
**names the ones it dropped** rather than quietly testing less.

### CI

Three entries join the `e2e-live` matrix — `endpoint`, `folia`, `plugins` — each on its own runner,
`fail-fast: false`, so one red suite never cancels the evidence from the others. A conditional step
resolves and caches the pinned Paper and Folia builds through the PaperMC API; the suites
themselves never download anything, so a local run and a CI run execute the same code path.

## Files

- `scripts/lib/e2e-server.sh` · `scripts/lib/vanilla-bot.py`
- `scripts/e2e-endpoint.sh` · `scripts/e2e-folia.sh` · `scripts/e2e-plugins.sh`
- `scripts/run-tests.sh` · `.github/workflows/e2e-live.yml`

## Testing

The suites *are* the test. Their own hygiene is asserted the way every other suite's is: services come
from the shared launcher, logs are audited for non-benign `ERROR`/`FATAL` lines
(`nodera_audit_errors`), worker `STATE` JSON is collected into the results directory, and a stage that
cannot assert reports `SKIPPED` naming the limitation that blocks it.

## Acceptance criteria

1. ⬜ `e2e-endpoint.sh` P5 asserts: an item passes from an unmodified player to a modded player,
   credited exactly once. *(built-subset E1–E4 green; P5 skips on [server 2](Task.2.md)/[6](Task.6.md))*
2. ⬜ `e2e-folia.sh` F3 asserts two Folia regions committing concurrently.
3. ⬜ `e2e-folia.sh` F6 finds zero thread-context violations across a full run.
4. ⬜ `e2e-plugins.sh` asserts a certified WorldEdit write against the pinned corpus.
5. 🚧 All three run green in the `e2e-live` matrix on their own runners — for the built subset; the
   headline stages exit 0 via `SKIPPED` naming their blocker.
6. ⬜ No stage reports `SKIPPED` any more — blocked on the open rows in
   [`LIMITATIONS.md`](LIMITATIONS.md).

## Limitations

None owned. Every stage that cannot yet assert names the limitation that blocks it, in the suite
output, so this task's progress is readable straight from a nightly run.
