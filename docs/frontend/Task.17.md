# Frontend Task 17 — A launcher, redesigned

<!-- AI-AGENT-INSTRUCTION: Three rules this task exists to hold. (1) NAVIGATION LIVES IN THE LEFT
     RAIL, not in the top bar — a top bar that regains destinations re-opens the defect this task was
     opened for. (2) A theme is DATA, not code: every colour a screen renders must resolve through a
     CSS custom property, so a user-defined theme can change it without a rebuild; a hard-coded hex
     in a component is a colour no theme can reach. (3) A saved theme is the user's document — it is
     never silently rewritten, and an unreadable one is preserved and reported the way a damaged
     settings file is (see Task 15 §1). Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS — built, not yet walked
**Category:** frontend · **Owns:** L-95, L-96 · **Last audit:** 2026-08-05
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

**Opened and implemented on 2026-08-05, in eight commits on `feat/cs-app-redesign`.** The frontend
gate — `tsc`, the production `vite build`, and `node --test "tests/*.test.mjs"` — is green at every
one of them, and the suite went from **31 tests to 40**: two existing files edited in five literal
places, twenty-nine tests untouched, nine new across two new files.

What is **not** claimed: the human walk. A redesign is not provable from a bundle, and the protocol
under [Testing](#testing) — open the app, walk every destination from the rail, define a theme,
restart, confirm it survived — is what moves this status to ✅. Until then this row says built, not
walked.

What it was built on:

- [Task 11](Task.11.md) landed Play / Library / Discover, the 12-column 1,680 px canvas, the bundled
  Space Grotesk + Inter typography, and the obsidian/Ender-green visual system. The rail already
  existed as a concept there; what it did not do is own navigation. It does now.
- `app/ui/tests/design-tokens.test.mjs` already asserts that **every styling class written in
  `src/**` resolves to a rule in the shipped stylesheet**. That check is the reason a theme lane
  could be attempted at all: it is the existing, general form of "a component named a token the
  theme did not declare", which is precisely the failure a user-editable theme multiplies.

Three things this work found that were not style, and fixed:

1. **The exact piece map was invisible.** `PieceCanvas` fills its background with `--surface-2` and
   then paints held cells — and the exact branch never set a fill colour, so it painted them in the
   background it had just filled with. The aggregated branch sets the fill inside its own loop,
   which is why the bug only showed on the path most manifests take (2,048 pieces or fewer). Every
   "exact map: one cell per piece" this app has drawn was a blank rectangle.
2. **A failed directory query rendered as an empty network.** `browseNetwork`'s `.catch` cleared the
   world list, so a tracker outage produced a confident "Nothing found" on the one screen whose
   whole job is reporting what other people are running. A `loading` boolean cannot express the
   difference; it is now `answered: boolean | null`.
3. **`prefers-reduced-motion` had to be unlayered.** Written inside `@layer base`, as the plan for
   this work specified, it would have lost to the unlayered `:root` block it overrides and done
   nothing — an accessibility preference honoured in the source and ignored in the browser.

## Dependencies

- [Task 11](Task.11.md) — the launcher shell, the canvas, the token system and the Play lane this
  redesign re-skins. This task changes the presentation of what Task 11 built; it does not re-open
  the launch coordinator, the `Remedy` contract, or the route table.
- The reference design ("MISTY", a Minecraft-launcher concept) is an input, not a dependency: it is
  a picture, and nothing in this repository imports from it.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Navigation moves out of the top bar into a persistent left sidebar rail | ✅ |
| 2 | The dark, purple-accented surface system, replacing the obsidian/green roles | ✅ |
| 3 | Every screen re-laid against the reference composition, with no screen losing a subject | ✅ |
| 4 | The top bar keeps only what is not navigation: the single link readout, and the window chrome | ✅ |
| 5 | **Theme** screen — a list of appearances, with the active one marked | ✅ |
| 6 | A property editor: the CSS custom properties, by name, with their current values | ✅ |
| 7 | Save / rename / duplicate / delete a named custom appearance, persisted in the settings document | ✅ |
| 8 | Built-in appearances are read-only and duplicable, never editable in place | ✅ |
| 9 | An unreadable or partial saved theme is preserved and reported, never overwritten | 🚧 |

Deliverable 4 was **overtaken, and the outcome is stronger than the row asks for.** There is no top
bar left to keep anything in. Once the destinations moved out, the strip had one occupant — a node
readout that was `hidden … narrow:flex`, i.e. invisible below 900 px, so a peer-to-peer launcher
said nothing about its own peer on a small window. That readout is now the rail's identity block
and its popover, reachable at every width the window can be, and `--top-nav-height` is deleted.
Window chrome is untouched: adopting the reference's undecorated window means `decorations: false`
plus hand-built controls per platform, which is a change to `app/tauri.conf.json` and therefore
drags `app/capabilities/*.json` in with it. Out of scope, and recorded as a native-shell follow-up.

Deliverable 9 is the one open row; see [Limitations](#limitations).

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
| `app/ui/src/App.tsx` | the shell: the rail instead of `TopNav`, `UTILITIES` beside `DESTINATIONS`, the escape hatch |
| `app/ui/src/Rail.tsx` | **new** — the rail, its identity block and its popover |
| `app/ui/src/Theme.tsx` | **new** — the screen: list, editor, contrast report, import/export |
| `app/ui/src/themetokens.ts` | **new** — the token allowlist and the grammar each value must satisfy |
| `app/ui/src/themecss.ts` | **new** — the CSS-mode rewriter |
| `app/ui/src/customtheme.ts` | **new** — application, and the escape hatch's inline armour |
| `app/ui/src/styles.css` | the palette a theme overrides, and the `@layer nodera.guard` block |
| `app/ui/src/components.tsx` | controls, cards and dialogs re-skinned; `Button` gains `shape` |
| `app/ui/src/Settings.tsx` | grouped sidebar, and the deep link into the Theme screen |
| `app/ui/src/PlayScreen.tsx`, `Worlds.tsx`, `World.tsx`, `Network.tsx` | re-laid against panels 03, 04 and 06 |
| `library/rust/nodera-core/src/settings.rs` | `appearance.themes`, and its one `ENFORCEMENT` row |
| `app/ui/tests/shell-navigation.test.mjs` | **new** — 4 tests: the rail, its active state, its unknown state, and Settings keeping its own navigation |
| `app/ui/tests/theme-screen.test.mjs` | **new** — 5 tests: the rewriter, the guard, the escape hatch, the keep-before-save rule, the patch semantics |

**Deliberately not touched**, and worth stating because each was considered: `theme.ts` (it stays
free of any dependency on the settings document, so it remains the single writer of `dataset.theme`
and stays shareable), `TrackerStores.tsx` (its seventeen `--tracker-store-*` mappings are all
`var(--app-token)` references, so the repalette flows through it with no edit at all),
`app/tauri.conf.json` and `app/capabilities/`, and `main.tsx` (the bundled typefaces stand — Cera
Pro is commercial and cannot ship, and Space Grotesk already carries the reference's wide geometric
bowls at display sizes).

## Testing

`bun run --cwd app/ui build` is the gate: `tsc`, the production `vite build`, and
`node --test "tests/*.test.mjs"` in one command. **40 pass, 0 fail.** Plus
`cargo test -p nodera-core` for the settings change.

Green, and asserted in this repository:

- **Every class still resolves.** `design-tokens.test.mjs` runs over the redesigned sources and the
  rebuilt stylesheet unchanged in intent: no class written in `src/**` may be absent from `dist`.
  Two literals in it moved — `--bg` and `--brand-1` — and the ban on `#ff4d8d|#a855f7|#22d3ee` did
  not. That ban is now a live tripwire rather than a formality: `#a855f7` is Tailwind's
  `violet-500`, one hue step from the new brand, so reaching for a built-in Tailwind purple while
  working on this palette fails the build.
- **Navigation is in the rail.** `shell-navigation.test.mjs`: `App.tsx` renders `<Rail`, contains no
  `function TopNav`, and `styles.css` declares no `--top-nav-height`. `--bg-rail` — declared in both
  schemes, mapped into `@theme inline`, and read by nothing since the day it was written — is
  proven to reach production CSS from a class selector.
- **The current destination is marked more than one way.** `aria-current="page"`, a named
  `ACTIVE_MARK` bar, and the tint plus fill. The reference marks it by tinting one glyph, which is
  invisible to anyone who cannot separate violet from grey by hue.
- **The rail says it has not heard, rather than saying zero.** Its health dot has four states, and
  the literal `Not heard from yet` is pinned: "we have not heard" and "we heard, and it is broken"
  are different facts about the network.
- **Settings keeps its own navigation** — the explicit guard that the global rail did not swallow a
  ten-section sidebar.
- **The theme rewriter is a rewriter.** `theme-screen.test.mjs` pins `.cssRules` and `selectorText`
  present, `.replace(/</` and `innerHTML` absent, the at-rule allowlist and property denylist by
  name, and `CSSImportRule`/`CSSFontFaceRule` dropped by class.
- **The guard layer ships and is last**, `[data-nodera-escape]` and `[data-nodera-truth]` are inside
  it, and user CSS is wrapped in `@layer nodera.user` — which is what actually makes the guard
  unbypassable.
- **The escape hatch cannot be styled away**: rendered on every path, portalled out of `#root`, and
  its own styles re-asserted inline at `!important`.
- **A theme is not persisted until it is kept**: `saveSettings` appears exactly once in `Theme.tsx`,
  inside the keep handler.
- **A custom theme is a patch, and says where it stops applying.**
- **Light-theme accent and focus** — `launcher-review.test.mjs`, three literals changed, three left
  alone.
- **Rust:** `enforcement_covers_every_settings_key_exactly_once` still passes, which is what proves
  `appearance.themes` is declared rather than merely stored.

Not asserted, and named rather than implied: a saved theme is **not** checked against the set of
properties the screens read. It does not need to be — a custom theme is a *patch*, so a token it
does not set falls through to the base scheme's own declaration, and there is no state in which a
half-defined appearance resolves to transparent text. That is a stronger property than the check
this task originally planned, and it is why the check is absent rather than pending.

Human protocol, because a redesign is not provable from a bundle: open the app, walk every
destination from the rail, define a theme, restart the app, and confirm the appearance survived.
**That walk has not happened**, and it is what moves this task's status.

## Acceptance criteria

1. ✅ Every destination is reachable from the left rail, and none is in the top bar — there is no
   top bar.
2. ✅ Each screen still answers exactly one question after the re-lay, and each keeps its unknown
   state: `Worlds.tsx`'s `known ? … : UNKNOWN` tiles, `players()`'s "player count unknown", the Play
   traffic guard, and — newly — the piece map's loading state and Discover's three-state poll.
3. ✅ A user can define, name, save, duplicate, delete and switch between appearances, and import or
   export one as JSON with the rewriter's drop-list shown before anything is added.
4. ✅ Editing a token repaints the running window immediately; nothing is rebuilt and nothing is
   fetched.
5. ✅ A built-in appearance is duplicable and not editable in place: the built-in row is a selection,
   and **New** starts from the scheme the window is currently using.
6. ✅ A damaged or partial theme file costs no settings and blanks no screen. Three mechanisms:
   `#[serde(default)]` on every field, so an older or partial document loads; patch semantics, so an
   unset token falls through; and `@layer nodera.guard`, so the window stays operable even when the
   theme is actively hostile.
7. ✅ The design-token check is green over the redesigned sources.
8. ⬜ The human walk, above.

## Limitations

Two rows, repeated here from [`LIMITATIONS.md`](LIMITATIONS.md) §B, which is where they are
normative. They were staged in this file first because the register is edited by several lanes at
once and a row that arrives through a merge conflict is a row nobody reads; they were lifted across
on merge, and renumbered from L-91/L-92 to **L-95/L-96** because the frontend register already held
L-91 … L-94 from the mobile merge.

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-95 | A custom appearance applies to the **desktop window only**. The phone renders in Compose against the system palette and cannot read CSS, so a theme authored here has no effect there. The value is stored, never dropped, and the screen says so on its face rather than implying a preference that travels | [17](Task.17.md) | The Android companion reads `appearance.themes` and renders at least the accent and surface roles from the selected appearance, or the register records that it structurally cannot | OPEN |
| L-96 | A stored appearance whose CSS the parser rejects is **dropped silently at startup**. Nothing is overwritten and the base scheme renders correctly, but the reason is only shown when the Theme screen is opened — so a theme that stopped working after a webview upgrade looks like a theme that was never saved | [17](Task.17.md) | Applying a stored appearance whose CSS fails to parse raises the same fault channel the settings document uses, so the reason is visible without opening the editor | OPEN |

The L-56 precedent holds over both: **badge it, do not fake it, and do not delete the saved value.**
