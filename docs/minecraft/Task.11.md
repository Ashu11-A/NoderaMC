# Minecraft Task 11 — The mod's GUI, rebuilt on the vanilla layout API

<!-- AI-AGENT-INSTRUCTION: The rule this task exists to preserve: no screen in this mod computes a
     widget position from an absolute pixel constant. Positions come from a layout object
     (HeaderAndFooterLayout / GridLayout / LinearLayout) and lists come from AbstractSelectionList
     subclasses, which bring their own scissor and scrollbar. A patch that reintroduces
     `bounds(width/2 - 100, height - 30, …)` is the defect this task closes. Keep this header's
     status accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** minecraft · **Owns:** MC-GUI-1 … MC-GUI-5 · **Last audit:** 2026-07-28
**Depends on:** [minecraft 10](Task.10.md)
**Consumed by:** —

---

## Goal

Every Nodera screen fits, scrolls, translates and survives a resize at any GUI scale; nothing is
drawn over anything else; each action has exactly one entry point; and the text on screen is a label,
not a paragraph.

## Status detail

Opened 2026-07-27 from a component-by-component audit against the 21.1.238 vanilla sources. The
mod has nine custom screens, four custom widgets and six injections into vanilla screens, and uses
**zero** layout objects: `GridLayout`, `LinearLayout`, `FrameLayout`, `HeaderAndFooterLayout`,
`TabNavigationBar`, `MultiLineTextWidget`, `StringWidget`, `CycleButton`, `Checkbox` and `Tooltip`
have no occurrences anywhere. One vanilla list (`NoderaWorldList`) exists; everything else is
absolute pixel arithmetic recomputed independently in `init()` and in `render()`.

That single fact produces all of the reported symptoms and twenty-nine more found alongside them.

### The two reported bugs, root-caused

**Duplicate entry points.** `TitleScreenAddon` replaces the Realms slot with "Nodera Network" →
`new NoderaMultiplayerScreen(screen)`, and `MultiplayerScreenAddon` swaps vanilla Multiplayer for
`new NoderaMultiplayerScreen(null)`. Vanilla builds all three title buttons in one method, so the
player sees "Multiplayer" and "Nodera Network" stacked adjacently, both landing on the same screen.
Three aggravating differences: the `null` parent makes Back construct a brand-new `TitleScreen`
(panorama reset); the vanilla route passes through Mojang's online-play `SafetyScreen` first; and the
injected button is built fresh without copying `active`, so it is clickable on accounts where vanilla
disabled multiplayer.

**Network tab spacing and the overlapping status line.** `PanelWidget` advances `x` cell by cell and
never compares it against its own right edge, calls no `enableScissor`, and `break`s out of its row
loop when the next row would overflow — no scrollbar, no "N more". A real tracker row is the raw
endpoint *including the `tcp://` scheme* (≈153 px) plus `offline` plus `no ack` ≈ **234 px**, against
a panel half-width of `(width-32)/2` = **224 px at GUI width 480**. Every row overflows into the
neighbouring panel at every realistic scale.

The footer is drawn by `render()` at the absolute `y = height - 66`, after `super.render()` has drawn
every widget, into a layout that reserved no pixels for it: `bodyBottom = height - 64`, so its glyph
box starts two pixels *inside* the body. On the Worlds tab vanilla's own `FOOTER_SEPARATOR` blits at
exactly `[height-64, height-62]`, straight through the text; on the Network tab `width/2` is the
centre of the 8 px gutter, so a ~300 px string straddles ~146 px into each panel.

## Dependencies

- [minecraft 10](Task.10.md) supplies the readiness predicate the world list must show and gate on.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | One entry point per action; the title-screen duplicate removed and `active` inherited | ⬜ |
| 2 | `NoderaMultiplayerScreen` rebuilt on `HeaderAndFooterLayout` + `TabNavigationBar` | ⬜ |
| 3 | `PanelWidget` replaced by `ObjectSelectionList` subclasses (scissor + scrollbar for free) | ⬜ |
| 4 | Endpoints displayed without their scheme and truncated to the column | ⬜ |
| 5 | `ShareWorldScreen`, `DeleteWorldScreen`, `NoderaCreateOptionsScreen` on `GridLayout` | ⬜ |
| 6 | Injected buttons placed inside the host screen's layout, not beside it | ⬜ |
| 7 | Every error/status paragraph via `MultiLineTextWidget` | ⬜ |
| 8 | Every user-facing string translated; no `Component.literal` English in a screen or HUD | ⬜ |
| 9 | Prose replaced by labels; tooltips carry the detail | ⬜ |
| 10 | `formatCountdown` fixed (300 s currently renders `0h5m`) | ⬜ |

## Design

### Standardise on what 21.1.238 already ships

Verified present in the sources jar and adopted wholesale: `HeaderAndFooterLayout` (a reserved footer
band alone kills the overlap), `TabNavigationBar` + `TabManager` (replacing two disabled `Button`s
used as a selected-tab indicator, which narration currently announces as *disabled*),
`ObjectSelectionList` / `ContainerObjectSelectionList` (scissor, scrollbar, separators),
`MultiLineTextWidget` / `FittingMultiLineTextWidget`, `CycleButton.onOffBuilder`, `Checkbox`,
`Tooltip`. The mod has no tooltips at all today, which is why the fix for prose-on-screen is not
simply deleting it: the detail moves into a tooltip, the label states the fact.

### The injections need the same discipline

Four of the six place a widget with absolute bounds beside a vanilla layout that repositions itself:
the create-world button overlaps `TabNavigationBar` at every GUI width below 688; the select-world
summary is drawn at `y = 8`, exactly where vanilla draws its own title; the pause-menu fallback slot
lands inside the disconnect band precisely when hosting (which is always, since Nodera publishes the
server so vanilla's LAN button is absent); and the disconnect-screen password button ignores a layout
that grows with the refusal message.

### Content pass

The lang file carries full sentences where it should carry labels — `"Share on creation: ON"`,
`"Visibility: Listed on tracker"`, `"The host's game is closed — the world is archived on the network
but not currently playable."` (92 chars, drawn unwrapped and clipped at both ends). `PieceMapView`
puts five statistics on one unwrapped line. Two dead lang pairs duplicate live keys, and two panel
titles are leftovers from a retired three-tab design.

## Files

| Path | Role |
|---|---|
| `.../client/multiplayer/NoderaMultiplayerScreen.java` | the screen the rebuild starts from |
| `.../client/multiplayer/PanelWidget.java` | deleted in favour of a selection list |
| `.../client/multiplayer/{NoderaWorldList,PieceMapWidget,WorldSearchBox}.java` | measured/truncated text |
| `.../client/{title,multiplayer,share,create,worldlist}/*Addon.java` | injections |
| `.../client/share/{ShareWorldScreen,DeleteWorldScreen}.java` | layout rebuild |
| `.../client/create/NoderaCreateOptionsScreen.java` | layout rebuild, shared options widget |
| `src/main/resources/assets/nodera/lang/en_us.json` | labels, dead keys removed |

## Testing

| Test | Proves |
|---|---|
| Screenshot runs at GUI scale 1–4 and window widths 427 / 480 / 640 / 854 | deliverables 2–6 |
| A headless assertion that no `Nodera*Screen` calls `bounds(` with a literal | the standing rule |
| `formatCountdown` unit test over 59 s / 300 s / 3600 s / 86400 s | deliverable 10 |
| A lang-completeness test: every `Component.translatable` key exists, no screen uses `literal` | deliverable 8 |

## Acceptance criteria

- [ ] The title screen offers Nodera exactly once.
- [ ] Every tracker and rendezvous row is fully visible or scrollable, at every GUI scale.
- [ ] No text is drawn over another widget on any screen at any scale.
- [ ] Every string on screen comes from the lang file.
- [ ] No screen carries a sentence longer than its widest widget.

## Limitations

| Id | Statement | Exit test |
|---|---|---|
| MC-GUI-1 | Two title-screen buttons open one screen, with different Back behaviour | deliverable 1 |
| MC-GUI-2 | Panel rows overflow their column and are clipped without a scrollbar | deliverables 3–4 |
| MC-GUI-3 | The status footer is drawn into the body rectangle | deliverable 2 |
| MC-GUI-4 | Injected widgets use absolute bounds beside vanilla layouts | deliverable 6 |
| MC-GUI-5 | Status cells, badges and HUD text are untranslated English literals | deliverable 8 |
