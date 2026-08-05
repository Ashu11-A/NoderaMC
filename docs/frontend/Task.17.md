# Frontend Task 17 — A launcher, redesigned

<!-- AI-AGENT-INSTRUCTION: Three rules this task exists to hold. (1) NAVIGATION LIVES IN THE LEFT
     RAIL, not in the top bar — a top bar that regains destinations re-opens the defect this task was
     opened for. (2) A theme is DATA, not code: every colour a screen renders must resolve through a
     CSS custom property, so a user-defined theme can change it without a rebuild; a hard-coded hex
     in a component is a colour no theme can reach. (3) A saved theme is the user's document — it is
     never silently rewritten, and an unreadable one is preserved and reported the way a damaged
     settings file is (see Task 15 §1). Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** frontend · **Owns:** — · **Last audit:** 2026-08-05
**Depends on:** [frontend 11](Task.11.md)
**Consumed by:** [frontend 19](Task.19.md)

---

## Goal

The desktop launcher looks like a launcher people already know how to use: a dark, purple-accented
surface with its navigation in a **left sidebar rail** instead of across the top, rebuilt against a
reference design rather than assembled screen by screen. And the appearance stops being ours alone —
a **Theme** screen lets a person define a theme, edit the CSS custom properties directly, and save
named appearances they can switch between.

## Status detail

**Opened 2026-08-05, and the implementation lands in this same pull request.** Nothing below is
claimed as green. The task file exists ahead of the merge because the rest of this category's
documents already reference the redesign, and a category whose ledger describes work with no task
file is the shape that let the former **app** and **mobile** registers drift apart in the first
place.

What is true before this task starts, and is what it is being built on:

- [Task 11](Task.11.md) landed Play / Library / Discover, the 12-column 1,680 px canvas, the bundled
  Space Grotesk + Inter typography, and the obsidian/Ender-green visual system. The rail already
  exists as a concept there; what it does not do is own navigation.
- `app/ui/tests/design-tokens.test.mjs` already asserts that **every styling class written in
  `src/**` resolves to a rule in the shipped stylesheet**. That check is the reason a theme lane can
  be attempted at all: it is the existing, general form of "a component named a token the theme did
  not declare", which is precisely the failure a user-editable theme multiplies.

## Dependencies

- [Task 11](Task.11.md) — the launcher shell, the canvas, the token system and the Play lane this
  redesign re-skins. This task changes the presentation of what Task 11 built; it does not re-open
  the launch coordinator, the `Remedy` contract, or the route table.
- The reference design ("MISTY", a Minecraft-launcher concept) is an input, not a dependency: it is
  a picture, and nothing in this repository imports from it.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Navigation moves out of the top bar into a persistent left sidebar rail | 🚧 |
| 2 | The dark, purple-accented surface system, replacing the obsidian/green roles | 🚧 |
| 3 | Every screen re-laid against the reference composition, with no screen losing a subject | 🚧 |
| 4 | The top bar keeps only what is not navigation: the single link readout, and the window chrome | 🚧 |
| 5 | **Theme** screen — a list of appearances, with the active one marked | 🚧 |
| 6 | A property editor: the CSS custom properties, by name, with their current values | 🚧 |
| 7 | Save / rename / duplicate / delete a named custom appearance, persisted in the settings document | 🚧 |
| 8 | Built-in appearances are read-only and duplicable, never editable in place | 🚧 |
| 9 | An unreadable or partial saved theme is preserved and reported, never overwritten | 🚧 |

## Design

**Navigation belongs on the side because the window is wide.** The canvas is up to 1,680 px and the
content is card grids; a horizontal strip of destinations spends the axis that is scarce (vertical)
to save the one that is plentiful. A rail also makes the destination list *stable* — it is visible
while you are inside a screen, which a top bar with a selected tab only pretends to be.

**One link readout, still.** [Task 8](Task.8.md) removed a second vocabulary for the link state and
[Task 11](Task.11.md) kept it. Moving navigation out of the top bar leaves that bar with one job, and
that job is not "describe a healthy connection". The rule does not change: it states facts, and adds
words only when something is wrong.

**A theme is data.** The only way a user-defined appearance can work is if every colour a component
renders is looked up rather than written down. That is the same constraint `design-tokens.test.mjs`
already enforces from the other direction — it fails when a component names a token the stylesheet
does not declare. A theme screen makes the stylesheet's declarations a *document*, so the existing
check becomes the thing that stops a saved theme from producing an invisible screen.

**The properties are edited by name, not through a colour wheel with a friendly label.** The audience
for a theme editor is a person who already knows what `--surface-2` means, or who is copying values
from somebody who does. A palette picker that maps five swatches onto forty properties is the design
Android was criticised for in [Task 11](Task.11.md) — "Material You in name only" — and inventing a
second, lossy vocabulary for the real one would repeat it on the desktop.

**A built-in theme cannot be edited in place.** Duplicate-then-edit, because a person who breaks the
default theme has no readable screen left to fix it from. This is the one place where taking an
action away is the safer design.

**A saved theme is the user's document.** [Task 15](Task.15.md) §1 established what this category
does with a file it cannot parse: preserve it, report it through a fault channel, and refuse the
first save rather than making the loss permanent. A theme file gets the same treatment. A theme that
is *readable but incomplete* is a different case and is filled from the active built-in, with the
missing properties named on screen — a half-defined theme must not resolve to transparent text.

**The CSP is unchanged.** No remote fonts, no remote images, no fetched stylesheets. A theme is
custom-property values; it is not a mechanism for loading anything.

## Files

Expected to change, named so the next reader can find the work rather than as a claim that it landed:

| Path | Role |
|---|---|
| `app/ui/src/App.tsx` | the shell: rail instead of top-bar destinations |
| `app/ui/src/styles.css` | the property declarations a theme overrides |
| `app/ui/src/components.tsx` | controls, cards and dialogs re-skinned against the new roles |
| `app/ui/src/Settings.tsx` | the Theme destination |
| `library/rust/nodera-core/src/settings.rs` | where named appearances are persisted |
| `app/ui/tests/design-tokens.test.mjs` | extended: a saved theme resolves every property a screen uses |

## Testing

The tests this task will be **closed by** — none of them is green yet, and none is asserted anywhere
in this repository today:

- **Every class still resolves.** `design-tokens.test.mjs` runs over the redesigned sources and the
  rebuilt stylesheet unchanged in intent: no class written in `src/**` may be absent from `dist`.
- **Navigation is in the rail.** A source-text check that the destination list is rendered by the
  rail component and that the top bar renders no destination — the general form, so a destination
  added later cannot quietly reappear above.
- **A saved theme resolves every property the screens use.** The set of custom properties read by
  `src/**` is derived, and a persisted theme is asserted to cover it or to be completed from the
  active built-in.
- **A damaged theme document is preserved and reported**, mirroring the settings-document assertions
  in `nodera-core`.
- **A built-in appearance cannot be saved over.**
- **Light and dark contrast**, the way `launcher-review.test.mjs` already checks the light theme —
  extended to whatever appearances ship built in.
- `tsc --noEmit` and the production `vite build`, which are this frontend's standing gate.

Human protocol, because a redesign is not provable from a bundle: open the app, walk every
destination from the rail, define a theme, restart the app, and confirm the appearance survived. That
walk is what moves this task's status, not the checks above on their own.

## Acceptance criteria

1. ⬜ Every destination is reachable from the left rail, and none is in the top bar.
2. ⬜ Each screen from [Task 8](Task.8.md) still answers exactly one question after the re-lay.
3. ⬜ A user can define, name, save, and switch between appearances.
4. ⬜ Editing a custom property changes the running interface without a rebuild.
5. ⬜ A built-in appearance is duplicable and not editable in place.
6. ⬜ A damaged or partial theme file costs no settings and blanks no screen.
7. ⬜ The design-token check is green over the redesigned sources.

## Limitations

Owns none yet. A row is registered here if the redesign cannot deliver something the current build
does — for example if an appearance cannot be honoured on a platform, in which case the rule this
category already holds applies: **badge it, do not fake it, and do not delete the saved value**
(the L-56 precedent, [`LIMITATIONS.md`](LIMITATIONS.md)).
