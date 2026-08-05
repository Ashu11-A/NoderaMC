# Frontend Task 10 — Practical screens, honest numbers

<!-- AI-AGENT-INSTRUCTION: Two rules this task exists to preserve. (1) A control that claims to be
     in force must have a read site; a setting with no consumer is declared unenforced, never
     "live". (2) A screen states what a thing IS and what the user can DO; the explanation of why
     the system works that way belongs in docs/, not on the screen. Reverting either re-opens the
     defects listed below. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** frontend · **Owns:** A-UX-1 … A-UX-5 · **Last audit:** 2026-07-28
**Depends on:** [frontend 8](Task.8.md), [frontend 9](Task.9.md), [worker 2](../peer/Task.2.md)
**Consumed by:** [frontend 16](Task.16.md)

---

## Goal

Every screen in the companion app shows live worker data or says plainly that it cannot, reads in
seconds rather than paragraphs, starts at its own top, and never claims a setting is in force when
nothing reads it. The traffic figures describe the node, not the current worker process.

## Status detail

Opened 2026-07-27 from a full screen-by-screen audit. The headline finding is **not** that screens
are fake — all ten desktop screens reach a real backend, and eight of them reach the worker. The
defects are honesty and ergonomics:

Landed so far:

- **Traffic is cumulative.** `DashboardStore` banks the last reading of a worker lifetime that is
  ending and adds it to everything reported afterwards, persisting the bank to
  `~/.config/nodera/traffic-totals.json`, so neither a worker restart nor closing the app resets
  "Uploaded / Downloaded". Rates are measured over a ≥1 s window and held between windows instead of
  being recomputed from every 250 ms push. 5 new tests.
- **Scroll position is per screen.** The shell's single scrollport is keyed by screen, and the two
  screens with internal tabs reset it explicitly on a tab change. Same fix on the mobile shell.
- **One dashboard subscription.** `useDashboard` was called twice — once in `App`, once in
  `DesktopApp` — giving two `nodera://dashboard` listeners and two initial fetches, and re-rendering
  both components on every push.
- **Tracker stores now resolves semantic roles through its shell.** Desktop maps the shared roles to
  the generated app palette; mobile maps them to Material 3 `primary`, `surface-container-*`,
  `outline-*`, `error-*`, and `scrim` roles, so changing the Material You source colour repaints
  controls, cards, errors, inputs, and dialogs. Desktop supplies the established
  `max-w-[1100px] px-[26px] pt-5 pb-10` frame while mobile keeps only its existing `px-4` frame. The
  regression runs after Vite and verifies the emitted CSS rules for both mappings and both padding
  selectors.

Remaining: the content pass (deliverables 5–6) and the settings-honesty pass (deliverables 8–9).

## Dependencies

- [worker 2](../peer/Task.2.md) owns the `NODERA-CONFIG` key list and which keys apply live.
- [frontend 9](Task.9.md) owns the tracker-stores screen this task restyles.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Traffic totals survive a worker restart and an app restart | ✅ |
| 2 | Rates measured on a ≥1 s window, held between windows | ✅ |
| 3 | Scroll resets per screen and per internal tab, desktop and mobile | ✅ |
| 4 | Single dashboard subscription | ✅ |
| 5 | Explanatory prose deleted from screens; labels state the fact | ⬜ |
| 6 | Every screen distinguishes *live* from *last known* when the link is down | ⬜ |
| 7 | `TrackerStores` uses colour tokens that exist, and has page padding | ✅ |
| 8 | `appearance.notifications` is either implemented or declared unenforced | ⬜ |
| 9 | Restart-scoped settings offer to restart the worker instead of asking the user to | ⬜ |

## Design

### The traffic reset was not a rendering bug

`total_sent_bytes` is a `LongAdder` born with the worker's JVM. The supervisor restarts that JVM on
every crash *and* whenever a restart-scoped setting is saved, so the tiles legitimately went to zero
several times a session. The store already *detected* the reset — it reported a rate of 0 rather than
a negative spike — but threw the pre-restart total away. Banking it is the whole fix; the UI needed
no change, because `Traffic` already carried `Option<u64>`.

The persisted bank records the last reading it has seen as well as the bank itself, which is how a
restarted *app* tells "the worker I am attaching to is the one that was already running" (counter at
or above the last reading, bank nothing) from "this is a new process" (counter below it, bank).

### Why one scrollport froze screens

`body { overflow: hidden }`, one `overflow-y-auto` div, and no `key` on it. React reconciles that div
as the same DOM node across navigation, and `scrollTop` is a property of the node, not of what is
rendered inside it. A short screen opened after a long one inherited an offset it could not clamp
(both `Settings` and `World` set `min-h-full`), so the sticky tab bar stayed pinned above blank space
and the page read as stuck. A `key` makes it a new node at zero; tab changes inside a screen keep the
node deliberately and therefore have to reset it by hand.

### "Some changes apply when the peer worker restarts" is honest — and useless

Three keys genuinely require a restart, because they are passed to the worker as environment
variables at spawn: `network.rendezvous_endpoints`, `network.use_random_port`, `network.port_range`.
The message is true. What makes it a complaint is that the app already owns the restart signal
(`daemon.rs` `RestartSignal`) and asks the user to go and find the terminal instead. Deliverable 9
turns the banner into an action.

Separately, `appearance.notifications` is declared `Enforcement::Local`, which `resolve` reports as
`Live` unconditionally — and there is no read site, no `tauri-plugin-notification` dependency and no
capability entry. That is a control that says it is working and does nothing; deliverable 8 fixes the
claim or the code, not the wording.

### Content rule for the redesign

Each card answers *what is it* and *what can I do*. A sentence that explains the architecture, the
threat model, or why a default was chosen goes to `docs/`. Concretely this removes: the two telemetry
policy lists rendered three times between `Consent.tsx` and the privacy card, the LAN modal's
three-bullet feature list, the tracker-store header and empty-state essays, the content-addressing
disclosure in Settings, and the sentence-length `hint=`/`sub=` strings on cards and stat tiles.

## Files

| Path | Role |
|---|---|
| `app/src/api/store.rs` | banked totals, rate window |
| `app/src/lib.rs` | `DashboardStore::restored()` |
| `app/ui/src/App.tsx` | scrollport key, single subscription |
| `app/ui/src/TrackerStores.tsx` | shared shell-semantic colour roles |
| `app/ui/src/mobile/Settings.tsx` | selects the Material 3 role mapping |
| `app/ui/tests/tracker-stores-style.test.mjs` | emitted desktop/mobile CSS and padding contract |
| `app/ui/tests/ux-honesty.test.mjs` | A-UX-1/2/3/5 exit tests over the sources |
| `app/ui/src/api.ts` | `isStale(link)` — a picture exists but is not current |
| `app/ui/src/Overview.tsx` | shows the worker's pause reason while paused |
| `app/ui/src/Settings.tsx` | settings-fault banner, notifications badge, restart banner |
| `app/ui/src/components.tsx` | `SCROLLPORT_ID`, `resetScrollport` |
| `app/ui/src/{Settings,World}.tsx` | tab-change scroll reset |
| `app/ui/src/mobile/{MobileApp,Settings}.tsx` | same, mobile shell |
| `app/src/settings.rs` | enforcement declarations (deliverable 8) |

## Testing

| Test | Proves |
|---|---|
| `api::store::tests::totals_survive_a_worker_restart` | deliverable 1 |
| `api::store::tests::totals_survive_the_application_itself` | deliverable 1 |
| `api::store::tests::attaching_to_the_worker_that_was_already_running_banks_nothing` | no double count |
| `api::store::tests::a_rate_is_held_between_windows_instead_of_flickering_to_zero` | deliverable 2 |
| `built tracker-store CSS resolves desktop and mobile shell roles` | deliverable 7; A-UX-4 exit |
| `a screen showing worker figures marks them as last-known when the link is down` | deliverable 6; A-UX-1 exit |
| `the notifications toggle is badged with why it is not in force` | deliverable 8; A-UX-2 exit (screen half) |
| `settings::tests::appearance_notifications_is_declared_unenforced_with_a_reason` | deliverable 8; A-UX-2 exit (declaration half) |
| `the restart banner restarts the worker, or says why it cannot` | deliverable 9; A-UX-3 exit |
| `every registered command has a frontend caller` | A-UX-5 exit; fails on any future orphan, not just the six |
| `the six A-UX-5 commands are each resolved, and stay resolved` | A-UX-5 exit, named row by row |
| `tsc --noEmit` over `ui/` | the shell changes type-check |

## Acceptance criteria

- [x] Restarting the worker from Settings leaves "Uploaded" and "Downloaded" unchanged.
- [x] The top-bar rate updates about once a second and does not flicker to `0 B/s` while a transfer
      is running.
- [x] Scrolling the Console to the bottom and opening About shows About from its top.
- [ ] No screen contains a paragraph that could be moved to `docs/` without losing an action.
- [x] With the worker stopped, every screen marks its numbers as last-known rather than current.
- [x] No setting reports itself as in force without a read site.

## Limitations

| Id | Statement | Exit test |
|---|---|---|
| A-UX-1 | **RETIRED 2026-07-28:** screens rendered stale numbers unmarked when the link was down; only the top bar changed | deliverable 6; `isStale(link)` + `StaleDataNotice` on both shells, `a screen showing worker figures marks them as last-known when the link is down` green |
| A-UX-2 | **RETIRED 2026-07-28:** `appearance.notifications` reported as live and had no consumer | deliverable 8; declared `Enforcement::Never` with its reason and badged on screen, `appearance_notifications_is_declared_unenforced_with_a_reason` green |
| A-UX-3 | **RETIRED 2026-07-28:** restart-scoped settings asked the user to restart the worker by hand | deliverable 9; the banner already restarted an app-owned worker and explains attach mode, now pinned by `the restart banner restarts the worker, or says why it cannot` |
| A-UX-4 | **RETIRED 2026-07-28:** `TrackerStores` used undefined desktop tokens, ignored Material 3 roles on mobile, and rendered without desktop padding | deliverable 7; post-build desktop/mobile CSS and padding regression green |
| A-UX-5 | **RETIRED 2026-07-28:** six registered commands had no desktop caller (`settings_fault`, `pause_reason`, `dashboard_world`, `open_share_file`, `get_unenforced_settings`, `nodera://pause`) | a caller or a deletion for each; `pause_reason` and `settings_fault` got callers, the rest were deleted, and `every registered command has a frontend caller` now walks all 47 |
