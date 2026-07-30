# App Task 7 — The Client Becomes the Way In

<!-- AI-AGENT-INSTRUCTION: The sentence that must never be lost from this screen's copy: joining a
     world here does NOT download it. Every other client in this genre means "fetch the content" by
     Join; here it opens a loopback port onto somebody's running game. Second rule: the LAN prompt
     has three answers (share / decline / not now) and "not now" must always leave the world
     shareable from the dashboard card. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** app · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [app 6](Task.6.md), [worker 7](../peer/Task.7.md), [worker 6](../peer/Task.6.md)
**Consumed by:** [app 3](Task.3.md), [minecraft 7](../minecraft/Task.7.md)

---

## Goal

Make the companion app the thing a player uses this network *through*: it asks whether to share a
world they just opened to LAN, it browses and joins other people's worlds, it produces invitations,
and it installs the Minecraft mod.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | The LAN modal: share / decline / not now, raised when a world is opened | ✅ |
| 2 | A persistent LAN card on the dashboard, so "later" is a real option | ✅ |
| 3 | **Join a world** — the directory, search by name or id, and Join | ✅ |
| 4 | The connect address, copyable, with what to do with it | ✅ |
| 5 | Invitations: mint, copy, save as a `.nodera` file, open a pasted link | ✅ |
| 6 | **Minecraft mod** — find installations, install, update, remove | ✅ |
| 7 | `api::network` + `api::modinstall` — the Rust half, typed and tested | ✅ |

## Design

**Three answers, not two.** The modal offers *Share it*, *Don't share this world*, and *Not now*. The
first two are decisions the worker records; the third dismisses the dialog and changes nothing. The
distinction is the whole reason the dashboard also carries a permanent LAN card: a prompt you can
only answer once is a prompt that must be answered immediately, which is the design that makes people
click the wrong button. Here, dismissing costs nothing — the world stays listed and one click away.

**A modal that follows the world, not the session.** It reappears for a *new* world and vanishes when
the world it was asking about closes. Dismissals are remembered per port and forgotten when that port
goes away, so reopening a world asks again — which is right, because reopening is a new decision.

**Join does not download.** This is stated on the screen, in the button's neighbourhood, and in the
card that appears afterwards. In every other client that looks like this, Join starts a transfer;
here it opens a port on your own machine that leads to somebody's running game, and the next thing
you do is paste that port into Minecraft. Without saying so, people wait for a progress bar that will
never appear.

**Searching covers names and ids.** A friend sends you one or the other: a world name to look for, or
an invitation whose id you pasted. Matching only names would fail exactly the case where somebody
sent you something precise.

**The directory is polled, slowly, and only while the screen is open.** It is a query against
services that may be down, and pushing it would mean every node re-fetching a catalogue several times
a second for a screen nobody has open. That is the opposite trade from the dashboard, which is pushed
— because the dashboard describes this node and the directory describes everyone else.

**The installer only ever installs the jar it shipped with.** No download. The app and the mod are
built and versioned together, and fetching a jar at install time would let the mod be a different
build from the worker it must speak to — plus put a code-fetching path into a desktop app for no
benefit. It also never removes a file it did not recognise as ours: a delete button pointed at a
folder full of other people's mods is a foot-gun.

**Every installation is listed, and none is chosen.** People run several launchers, profiles and
instances. An installer that guessed would write the jar somewhere the player does not launch from —
the most confusing possible failure, because nothing goes wrong until the game starts without it.

## Files

- `app/src/api/network.rs` — LAN decisions, directory, join/leave, share links
- `app/src/api/modinstall.rs` — finding Minecraft, installing, removing
- `app/ui/src/{Lan,Browse,ModInstall}.tsx`, `network.ts`
- `app/ui/src/World.tsx` — the Share tab
- `app/ui/src/App.tsx` — the rail gains *Join a world* and *Minecraft mod*

## Testing

- `api::network` (5) — a world id is read out of a pasted link, the magnet spelling reads the same,
  anything that is not an invitation is refused **before** a round trip, invitation files round-trip
  through disk, and an outcome carries the worker's own words rather than a bare failure.
- `api::modinstall` (6) — only Nodera jars are recognised (somebody else's mod must survive an
  uninstall), the installed name is versioned so two builds never collide, an install reports whether
  it already has *this* build, a folder with no `mods` directory is still a candidate, and installing
  without a bundled jar says so rather than failing obscurely.
- `api::model` (3) — the LAN block: a world is not shared until somebody says so, a machine that
  cannot watch says so, and a worker too old to know about LAN reports it unsupported.

## Acceptance criteria

1. ✅ Opening a world to LAN raises a prompt in the app, and declining leaves it shareable later.
2. ✅ A player can find somebody else's world by name or id and join it.
3. ✅ Joining yields a loopback address, and the UI says what to do with it.
4. ✅ An invitation can be copied or saved, and a pasted one can be opened.
5. ✅ The mod can be installed into a found Minecraft without leaving the app.
6. ✅ Nothing in the app implies that joining transfers a world.
