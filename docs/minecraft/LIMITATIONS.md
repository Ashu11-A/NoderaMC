# Minecraft — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the minecraft category. "Permanent" is banned. Every §B row
     has an owning task and an EXIT TEST and retires only when that exit test is green — verify
     against the exit clause, never the prose "remaining" note. A newly discovered limitation enters
     §B as OPEN with a path, an owner, and an exit test BEFORE the discovering PR merges. Never delete
     a row: move it to LIMITATIONS.fixed.md with its evidence. L-45 (real-client acceptance in CI)
     is RETIRED: the harness exists and is green, so a row whose only gap was "needs a real client"
     now retires by ADDING A STAGE to a live suite, not by waiting. -->

**Category:** minecraft · **Last audit:** 2026-07-25 · Open or retiring rows: **5**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints (never denied, engineered around)

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-7 | Minecraft and NeoForge version churn breaks mixins | Pinned versions upgraded in single dedicated commits; a **minimal load-bearing mixin set** with events preferred everywhere else; every mixin documented in `COMPATIBILITY.md`; Minecraft-free core modules so churn cannot reach the engine | [1](Task.1.md), [2](Task.2.md), [6](Task.6.md) |

---

## §B — Staged capabilities (the burn-down list)

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-43 | No client multiplayer GUI beyond server-pushed surfaces. The screens are **built**: a tabbed Nodera-only multiplayer screen with a scrollable world list, search, health rows, status footer, live tracker and rendezvous tabs, a create-world torrent toggle with an independent password field, and an end-to-end join flow. What remains is the live pass with real tracker data in a GUI environment | [4](Task.4.md) | The multiplayer page lists torrent worlds with per-world player/chunk/reliability counters, red/gray health and a 24 h countdown; create-world offers torrent hosting with a password; verified in a real client | RETIRING |
| L-46 | The GUI redesign is proven by view-model tests and compile-clean screens, but the **presentation** is unverified live: "Open to Nodera" occupying the vanilla LAN slot, the single-player public-world badge's per-row placement (screen-level summary only until the world-list mixin lands), the tabbed multiplayer screen, and the piece-map grid filling as pieces arrive. All four data feeds are live-wired; what is unverified is what a player sees | [4](Task.4.md) | In a real client: one "Open to Nodera" button in the LAN slot, a live public-world count on the world list, three working multiplayer tabs, and a piece map that fills green as pieces arrive | OPEN |
| L-49 | The world-identity, authorship, and permission model is landed and enforced, but several halves ride the live mesh: the single-player world-list per-row player count needs the repository's **first mixin** in a GUI environment; live chunk and region validation over the worker mesh needs the committee stack running against real regions; remote-joiner operator grants; and password-change re-encryption must propagate over the network. Ban enforcement at join **and** on gossip ingest is done | [6](Task.6.md) | In a real client: a shared world shows a live player count on its row; a committee re-executes and commits a region delta over the worker mesh; a banned peer is refused at join; a re-keyed world's new ciphertext replaces the old across seeders | OPEN |
| L-50 | The entity lane's **live** half. Activation self-bootstraps and has been observed on a real server (entity lane live across regions with the mesh formed; 239 validated/ghost entities across 12 delegated regions with versions advancing; reopen resuming from the store head; a clean-slate pickup delivered exactly once). The scripted **pickup** and **mob** drives now pass in CI. The **pearl** drive's ghost half and the mob drive's revocation half are both blocked by L-60 — capture and revocation only run on the node whose lane owns the region, and a dedicated server owns none — so each reports SKIPPED naming it rather than asserting the impossible. What remains: L-60, and the certified genesis replacing the interim manifest end to end | [2](Task.2.md) | The scripted gameplay drives pass in CI: validated pickup credited exactly once, mob capture and revocation behaving, and the pearl ghost/materialize/teleport drive green | RETIRING |
| L-60 | **Ghost capture and revocation run only on the node whose lane owns the region.** `EntityCaptureBridge.captureJoin` acts only when `runtime.delegated(region)` — the *server's* lane — and under field-of-view ownership the plan hands every region to the PLAYERS' nodes ("no regions fall to this node" is in every dedicated-server log). So on a dedicated server the ghost lane neither captures newly delegated regions nor **refuses** a non-delegable entity in a dimension that never opted into `mobCaptureDimensions`: the region is simply kept by whoever owns it, unvalidated. Found by `e2e-mobs.sh` G2, which now reports SKIPPED with this reason instead of asserting something no node in that topology can do | [2](Task.2.md) | `e2e-mobs.sh` G2 asserts rather than skips: a non-delegable entity in an un-opted-in dimension revokes its region, names the reason, and the session plays on | OPEN |

---

## Reading guide for the implementing model

- **"Needs a real client" is no longer a reason to wait.** L-45 is retired: `e2e-live` runs the
  scripted suites under Xvfb on every dispatch and nightly. A live exit retires by adding a stage
  to a suite (or a suite to the workflow) and reading the run — the way L-52 did.
- **Verify a retirement against the row's exit test**, not against how small the prose "remaining"
  note looks. Rows have sat at RETIRING with green exits, and rows have looked finished while their
  headline claim was not yet true.
- **Events first, mixins last.** Any new mixin adds surface to A-7 and must justify why an event was
  not enough, in its own header and in `COMPATIBILITY.md`.
- **Run the live suites when you touch host, join, lane, or continuity surfaces.** The headless gate
  cannot see configuration-gated lifecycle paths, and most defects in this category were only
  catchable live.
