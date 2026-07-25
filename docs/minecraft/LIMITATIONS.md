# Minecraft — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the minecraft category. "Permanent" is banned. Every §B row
     has an owning task and an EXIT TEST and retires only when that exit test is green — verify
     against the exit clause, never the prose "remaining" note. A newly discovered limitation enters
     §B as OPEN with a path, an owner, and an exit test BEFORE the discovering PR merges. Never delete
     a row: move it to LIMITATIONS.fixed.md with its evidence. Note that L-45 gates most of the
     others — do not duplicate it into per-task rows. -->

**Category:** minecraft · **Last audit:** 2026-07-25 · Open or retiring rows: **7**

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
| L-45 | **No automated real-client acceptance in CI.** Both halves are built and both have passed by hand: dev runs launch from Gradle on the reconciled NeoForge pin, and eight scripted suites drive real clients against the real tracker and rendezvous binaries — the continuity series passed all seven stages on a live display (share → listing → second client in-world → 11.2 MB archive on the network → host killed → joiner recovered, re-opened, and re-hosted in **3 seconds**). En route the series caught four real defects that no headless test could have. What remains is folding the suites into CI under a headless display | [1](Task.1.md) | The suites' stages pass end to end in CI: share → a second client joins through tracker and rendezvous → host killed → the joiner recovers the world from the network | RETIRING |
| L-46 | The GUI redesign is proven by view-model tests and compile-clean screens, but the **presentation** is unverified live: "Open to Nodera" occupying the vanilla LAN slot, the single-player public-world badge's per-row placement (screen-level summary only until the world-list mixin lands), the tabbed multiplayer screen, and the piece-map grid filling as pieces arrive. All four data feeds are live-wired; what is unverified is what a player sees | [4](Task.4.md) | In a real client: one "Open to Nodera" button in the LAN slot, a live public-world count on the world list, three working multiplayer tabs, and a piece map that fills green as pieces arrive | OPEN |
| L-49 | The world-identity, authorship, and permission model is landed and enforced, but several halves ride the live mesh: the single-player world-list per-row player count needs the repository's **first mixin** in a GUI environment; live chunk and region validation over the worker mesh needs the committee stack running against real regions; remote-joiner operator grants; and password-change re-encryption must propagate over the network. Ban enforcement at join **and** on gossip ingest is done | [6](Task.6.md) | In a real client: a shared world shows a live player count on its row; a committee re-executes and commits a region delta over the worker mesh; a banned peer is refused at join; a re-keyed world's new ciphertext replaces the old across seeders | OPEN |
| L-50 | The entity lane's **live** half. Activation self-bootstraps and has been observed on a real server (entity lane live across regions with the mesh formed; 239 validated/ghost entities across 12 delegated regions with versions advancing; reopen resuming from the store head; a clean-slate pickup delivered exactly once). What remains is the scripted pickup, mob, and **pearl** gameplay drives running in CI, and the certified genesis replacing the interim manifest end to end | [2](Task.2.md) | The scripted gameplay drives pass in CI: validated pickup credited exactly once, mob capture and revocation behaving, and the pearl ghost/materialize/teleport drive green | RETIRING |
| L-51 | **Password re-key has no live exit.** The pipeline is landed and proven headlessly: a control verb carries a freshly packed archive plus the new password and the signed identity; the worker re-encrypts under a fresh salt (new key ⇒ new ciphertext ⇒ new manifest root ⇒ bumped version), re-signs the identity, re-announces, and evicts the superseded ciphertext. No old password is escrowed — the plaintext save on the author's own machine is the source. The share-screen path deliberately does **not** re-run ordinary share activation, which would seed a *plaintext* archive as the newest version and let joiners bypass the password; failure is surfaced honestly and the world keeps its old password. What remains is the live pass | [5](Task.5.md) | In a real client: the author changes the password from the share screen, the re-keyed ciphertext replaces the old across seeders, and joiners must supply the new password | RETIRING |
| L-52 | **No password gate on the live Minecraft join path.** The world password protects the archived **content** plane, not who connects to the live game: a listed world's game route is public. Operator access is still authenticated by key, but the *connection* itself is ungated | [5](Task.5.md) | A password-protected world refuses a joiner at the live game server unless they supply the password | OPEN |

---

## Reading guide for the implementing model

- **L-45 gates most of the others.** Do not duplicate it into per-task rows; a task whose only gap is
  "needs a real client" says so and points here.
- **Verify a retirement against the row's exit test**, not against how small the prose "remaining"
  note looks. Rows have sat at RETIRING with green exits, and rows have looked finished while their
  headline claim was not yet true.
- **Events first, mixins last.** Any new mixin adds surface to A-7 and must justify why an event was
  not enough, in its own header and in `COMPATIBILITY.md`.
- **Run the live suites when you touch host, join, lane, or continuity surfaces.** The headless gate
  cannot see configuration-gated lifecycle paths, and most defects in this category were only
  catchable live.
