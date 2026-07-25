# App Task 4 — End-to-End Acceptance + Cross-Machine Continuity

<!-- AI-AGENT-INSTRUCTION: This task's value is that it tests the CLAIM PLAYERS ARE MADE — install,
     gate both ways, host, close Minecraft, still joinable from elsewhere. A test that runs both sides
     in one process does not prove cross-machine continuity and must not be recorded as satisfying it.
     Keep this header's status accurate. -->

**Status:** ⏳ BLOCKED (needs the worker's own announce timer and the joining-client harness)
**Category:** app · **Owns:** L-47 (shared) · **Last audit:** 2026-07-25
**Depends on:** [worker 3](../worker/Task.3.md), [minecraft 1](../minecraft/Task.1.md)
**Consumed by:** the project's product claim

---

## Goal

One automated job that exercises the promise the product makes to a player:

1. Install the app.
2. Verify the mod's gate **both ways** — worker present ⇒ Minecraft starts; worker absent ⇒ an
   actionable abort.
3. Host a world, close Minecraft, and show from a **second machine or instance** that the world is
   still listed and still joinable, because the worker kept it alive.

## Status detail

Blocked, and the blockers are specific rather than vague. The gate-both-ways half and the
crash-survival half are already green in CI: a companion job builds the app, runs the gate test in
both directions, and proves hosted-world survival with a real daemon and a SIGKILLed stand-in game
process.

What remains is the genuinely **cross-machine** half, which needs two things that belong to other
categories: the worker owning its announce and rendezvous-registration timers independent of any game
process ([`worker/Task.3.md`](../worker/Task.3.md)), and a scripted joining client
([`minecraft/Task.1.md`](../minecraft/Task.1.md)).

## Dependencies

- [worker 3](../worker/Task.3.md) — announce and seeding that persist without a game.
- [minecraft 1](../minecraft/Task.1.md) — the harness that drives the joining side.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | CI job: build the app, stage the worker, run the gate both ways | ✅ |
| 2 | Hosted-world survival across a SIGKILLed game process | ✅ |
| 3 | Installer-based install as the job's entry point | 🚧 |
| 4 | Cross-machine (or cross-instance, separately networked) continuity assertion | ⏳ |
| 5 | The joining side driven by a real client | ⏳ |

## Design

**Test the sentence, not the components.** Every component of this claim is already tested
individually: the gate, the worker's survival, the archive plane, the tracker listing. What is not
tested is the *sentence a player would say* — "I closed Minecraft and my friend could still join". A
composed test is the only thing that catches a gap between two correct components.

**Cross-machine means separately networked.** Running both sides in one process, or even in two
processes on one loopback, silently satisfies the assertion while skipping the part that actually
fails in the field: NAT, real routes, and a tracker that must be reachable from both. If the CI
environment cannot give two networks, the honest form is two hosts on a shared runner network with the
loopback shortcut explicitly disabled — and that limitation stated in the job.

**Install, do not `cargo run`.** The install path is where icons, autostart, bundled runtimes, and
permissions actually break. A job that skips the installer tests the code and not the product.

**Both directions of the gate matter equally.** "Minecraft starts when the worker is present" is the
happy path; "Minecraft aborts with an actionable message when it is absent" is the one a user will
actually meet, and a stack trace there is a support burden rather than a diagnosis.

## Files

- `.github/workflows/` (the companion job)
- `java/neoforge-mod/.../common/CompanionGate.java` (the gate under test)
- `scripts/` (the joining-client drive)

## Testing

- Gate both ways, from an installed app.
- Host → close Minecraft → the world remains listed and joinable from the second side.
- The worker's announce continues on its own timer with no game process alive.

## Acceptance criteria

1. ✅ The gate is verified in both directions in CI.
2. ✅ A hosted world survives a SIGKILLed game process with its seeding intact.
3. 🚧 The job starts from a real installer.
4. ⏳ A world hosted on one side stays listed and **joinable from the other** after Minecraft closes.
5. ⏳ The joining side is driven by a real client rather than a control-socket assertion alone.

## Limitations

- **L-47** — the automated installer-plus-continuity acceptance. See
  [`LIMITATIONS.md`](LIMITATIONS.md). Shared with [`worker/Task.3.md`](../worker/Task.3.md): one job,
  two owners.
