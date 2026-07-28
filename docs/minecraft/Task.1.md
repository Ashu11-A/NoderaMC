# Minecraft Task 1 — Mod Skeleton, Build Conventions, Run Harness

<!-- AI-AGENT-INSTRUCTION: This task is the MULTIPLIER: once the scripted client harness runs in CI,
     the deferred acceptance of tasks 2–6 and of several other categories runs as one batch. Do not
     treat it as low-value scaffolding. Version pins change in a single dedicated commit, never
     mid-task. Keep this header's status accurate and keep L-45 in agreement with it. -->

**Status:** ✅ COMPLETED
**Category:** minecraft · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** —
**Consumed by:** every other task in this category, and the live acceptance of [engine](../engine/Task.0.md), [network](../network/Task.0.md), [rendezvous 3](../rendezvous/Task.3.md), [app 4](../app/Task.4.md)

---

## Goal

A building, CI-green mod with correctly split entrypoints — and, more importantly, a **harness that
drives real clients**: launch a host and a joiner from Gradle, script a full session, and assert the
outcome from logs and worker control sockets, with no GUI automation.

## Status detail

**Dev runs work.** The `runs { }` block launches `runClient`, `runServer`, and the scripted
`runClientHost` / `runClientJoin` / `runClientJoinTwo` / `runClientRejoin` variants. `runServer` boots
to `Done` on a fresh world with auto-share activating the host peer and a tracker announce;
`runClient` reaches the title screen.

**The scripted suites exist and run in CI under a headless display.** Fifteen suites drive real
NeoForge clients — each with its own peer worker — against the real tracker and rendezvous binaries:
continuity, ownership, churn, pickup, mobs, pearl, mesh-soak, determinism, password, rekey, commands,
farlands, crash, ownership-follow, and profile. A shared launcher gives every suite the same topology,
a lock makes concurrent runs structurally impossible, and the `e2e-live` workflow runs them one per
runner as a matrix under Xvfb — so a live exit is now a gate, not a hand-run.

Getting here required reconciling several real build problems: the NeoForge pin moved 21.1.77 →
**21.1.238** to match the real client; the Nodera project modules had to join the mod definition (FML
module isolation otherwise hides them from the mod at dev-run time); and runtime versions had to align
with Minecraft's strict pins. Folding the suites into CI cost three defects invisible on a developer
machine (the accessibility-onboarding screen blocking quick play; a killed server holding RCON open
while saving; terrain generation 200 km out making an RCON read come back empty) — see the **L-45**
retirement evidence in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

## Dependencies

None. This task is a prerequisite, not a dependent — which is why it ranks first in the roadmap.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Convention plugins + version catalog + both entrypoints | ✅ |
| 2 | Dist isolation with a guard test | ✅ |
| 3 | Registered (initially empty) mixin config; payload registrar; CI jar | ✅ |
| 4 | `runs { }` block: client and server dev runs | ✅ |
| 5 | Version pin reconciled to the real client | ✅ |
| 6 | Quick-play scripted client variants (no GUI automation) | ✅ |
| 7 | Shared launcher + standard topology + suite lock | ✅ |
| 8 | Eight scripted live suites | ✅ |
| 9 | The suites running in CI under a headless display | ✅ (**L-45** retired 2026-07-25; `e2e-live` green under Xvfb) |

## Design

**No GUI automation, ever.** The suites use Minecraft's own quick-play arguments to boot straight
into a world or a session. Automating clicks against a game UI is famously brittle and would make
every screen change a test failure. Assertions come from logs and from the worker control sockets —
sources that are stable, greppable, and meaningful.

**One launcher, one topology.** Every suite sources the same launcher and gets the same shape: two
players, one tracker, one rendezvous, three headless peers. Three peers is the **quorum floor** — a
thinner run measures a degraded system and would produce misleading results rather than a failure.

**A lock, not a convention.** The suites share a port block and the run directories, so concurrency is
made structurally impossible with an exclusive lock that standalone runs honour too.

**Distinct usernames, offline auth, pinned ports.** Duplicate logins get kicked; Mojang session auth
rejects dev accounts. These are the unglamorous details that decide whether a harness works at all.

**The stale-bake trap is documented in the harness, not left to be rediscovered.** The baked shared
world stores a certified genesis pinned to the rules version of the day it was baked. Bump that
constant and the engine correctly refuses the old genesis — but the symptom is "every ownership
assertion fails for no obvious reason". The suites now bail out immediately, naming the cause and the
re-bake command.

**Why this task is the multiplier.** Nine tasks across five categories carry a live-evidence clause
that this harness closes. Its own cost is small; its unblocking value is the largest in the project.

## Files

- `java/build-logic/src/main/kotlin/nodera.neoforge-mod.gradle.kts`
- `java/neoforge-mod/src/main/java/dev/nodera/mod/{NoderaMod,NoderaClientMod}.java`
- `scripts/run-tests.sh`, `scripts/lib/e2e-main.sh`, `scripts/e2e-*.sh`
- `.github/workflows/` (the live workflow, dispatch + nightly)

## Testing

See [`TESTING.md`](TESTING.md) for the full suite catalogue and how to read the artifacts. The
headless rehearsals of the same flows run on the ordinary gate.

## Acceptance criteria

1. ✅ `runClient` and `runServer` launch from Gradle on the pinned NeoForge.
2. ✅ Scripted host and joiner clients boot straight into a world or session with no GUI automation.
3. ✅ Every suite runs on one shared topology, serialised by a lock.
4. ✅ The suites pass end to end **in CI under a headless display** (**L-45** retired).

## Limitations

None open. **L-45** (no automated real-client acceptance in CI) is RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
