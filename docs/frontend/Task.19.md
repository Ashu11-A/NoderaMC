# Frontend Task 19 — One codebase, three exports

<!-- AI-AGENT-INSTRUCTION: The honest claim this task must keep making. "Three exports" is DESKTOP +
     WEB sharing a React tree, and ANDROID sharing the Rust core, the Java worker and the control
     protocol — NOT the React tree. Android is native Jetpack Compose: `app/ui/src/m3/` and
     `app/ui/src/mobile/` were DELETED in commit 671b6b8 and `app/ui/src` has been flat ever since.
     Any wording that implies one React tree renders on a phone is false and re-opens the defect
     Task 11 closed ("Material You in name only"). The other rule: `nodera-core` must never depend on
     `tauri` — three front ends read it now, and an `AppHandle` in that crate is a thing only one of
     them can use. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** frontend · **Owns:** — · **Last audit:** 2026-08-05
**Depends on:** [frontend 17](Task.17.md), [frontend 18](Task.18.md)
**Consumed by:** [frontend 20](Task.20.md)

---

## Goal

One source tree that builds the desktop launcher, the website, and the Android app — where "one
tree" means what it can honestly mean for each of them, and the file says which.

## What "three exports" actually means

This is the claim the task is most likely to be summarised into something false, so it is stated
before anything else, and it was **verified against the repository** on 2026-08-05 rather than
assumed:

| Export | Shares | Does not share |
|---|---|---|
| **Desktop** (`app/` + `app/ui/`) | the React tree, the design tokens, `library/rust/nodera-core` | — |
| **Website** (`web/`) | the React tree and the design tokens — this is the work | `nodera-core`: a static site has no worker to link to |
| **Android** (`app/android/kotlin/`) | `library/rust/nodera-core` (through two JNI calls), the Java worker, the control protocol, the settings document | **the React tree** |

**Android is native Compose, and that is not a detail to be smoothed over.** Commit `671b6b8`
deleted `app/ui/src/m3/{components,motion,theme}` and `app/ui/src/mobile/{About,Battery,MobileApp,
Settings,Setup,Storage,nav}` — 2,504 lines of React that had been imitating Material 3 inside a
WebView. `app/ui/src` has had no subdirectories since. The phone's interface is
`app/android/kotlin/ui/{Components,Model,Onboarding,Screens,Settings,Theme}.kt`, and its colours come
from `dynamicDarkColorScheme(context)` — a wallpaper palette a WebView cannot read, which was the
whole reason for the rewrite.

So: **desktop and web share React. Android shares the core, the worker and the protocol.** Three
exports from one codebase, with two different seams, honestly named.

## Status detail

**Opened 2026-08-05, and the implementation lands in this same pull request.** Nothing below is
claimed as built.

What is already true, and is what makes this task small rather than speculative:

- `library/rust/nodera-core` was extracted by [Task 11](Task.11.md) and is inside the root cargo
  workspace, so `cargo test --workspace` covers it — 270 tests at the last run.
- The seams are already traits the shell implements: `api::link::Sink`, `api::events::EventSink`,
  `browser::LinkOpener`. A third consumer implements traits rather than forking a crate.
- `app/ui/` is a plain Vite + React + Tailwind v4 project with a flat `src/`, one stylesheet, and one
  primitive catalogue (`components.tsx`, 842 lines). There is no framework coupling to unpick.
- `app/ui/tests/design-tokens.test.mjs` already asserts every class written in `src/**` resolves in
  the shipped stylesheet — which is the check a shared component library needs in order to be shared
  safely.

## Dependencies

- [Task 17](Task.17.md) — the redesign decides what the shared components *are*. Unifying the trees
  first would mean unifying a component set that is about to be replaced.
- [Task 18](Task.18.md) — the site has to exist in React before it can share anything with the app.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | One package layout in which the app and the site are two build targets, not two projects | 🚧 |
| 2 | A shared primitive/token layer both targets import, with no duplicated token strings | 🚧 |
| 3 | The desktop build output unchanged in behaviour — `app/ui/dist` still what Tauri bundles | 🚧 |
| 4 | The site build output a directory of static files, per [task 18](Task.18.md) | 🚧 |
| 5 | Tauri-only code kept out of the shared layer, so the site never imports `@tauri-apps/api` | 🚧 |
| 6 | One `bun run test` covering both targets, replacing two independent gates | 🚧 |
| 7 | Android's seam documented in one place, so the next reader does not look for its React | 🚧 |

## Design

**The seam is `@tauri-apps/api`, and it is the whole problem.** The desktop tree calls `invoke` and
`listen`; a static site has nothing to invoke. A shared component that reaches for the Tauri bridge
is a component the site cannot render, and the failure is a build error at best and a blank panel at
worst. The split is therefore by *capability*, not by *screen*: presentational components and tokens
are shared; anything that talks to a worker stays in the app target behind the interfaces that
already exist.

**Sharing the tokens is most of the value.** The launcher and the site being visibly one product is
the point, and the cheap way to get it wrong is two stylesheets that agree today. One token layer,
one `design-tokens` check over both targets, and a theme from [task 17](Task.17.md) that the site can
also render.

**Do not build a cross-platform imitation layer.** This category's refactoring register already
refuses to recommend recombining native Compose and desktop React
([`REFACTORING.md`](REFACTORING.md)), and that refusal binds this task: shared behaviour goes into
`nodera-core`, not into a component abstraction pretending Compose and React are the same thing. The
2,504 deleted lines are the evidence for what the alternative costs.

**`nodera-core` must never depend on `tauri`.** [Task 11](Task.11.md) already established this and
found the trap: `cfg!(desktop)` is emitted by `tauri_build`, so outside the shell crate it is not an
error — it is `false`. Two gates came along with the extraction and would have told every desktop
user their worker could not be restarted. A third consumer makes that class of bug more likely, not
less.

**One gate, or the second target rots.** The Tauri crate's workspace exclusion is this repository's
worked example: a target CI does not build is a target that silently breaks, which is exactly what
[task 3](Task.3.md) exists to fix. A unified tree with two build commands and one of them not in CI
would recreate it.

## Files

| Path | Role |
|---|---|
| `app/ui/` | the desktop target, and today the only React tree |
| `web/` | the site target after [task 18](Task.18.md) |
| `app/ui/src/{components.tsx,styles.css}` | the primitives and tokens that become shared |
| `app/ui/tests/` | the gates that must cover both targets |
| `library/rust/nodera-core/` | shared by desktop and Android; **not** by the site |
| `app/android/kotlin/ui/` | the Android interface — Kotlin, and staying that way |

## Testing

The tests this task will be **closed by**:

- **The site target imports nothing Tauri-only.** A check over the site's emitted module graph, not
  over its source imports — a transitive import through a shared component is exactly the case a
  source grep misses.
- **The design-token check runs over both targets**, so a token that exists for one and not the other
  fails the build.
- **The desktop bundle is unchanged in behaviour** — the existing `design-tokens`, `ux-honesty`,
  `launcher-review` and `tracker-stores-style` suites all still pass over the reorganised tree. They
  are the regression net for this move, and none of them may be weakened to accommodate it.
- **One command builds and tests both**, and CI runs that command.
- **Android is untouched**: `scripts/android-apk.sh --debug --skip-worker` still builds, which is the
  cheapest proof that a React reorganisation did not reach the phone.

## Acceptance criteria

1. ⬜ One command builds the desktop bundle and the static site from one tree.
2. ⬜ The two targets share their primitives and tokens, with no duplicated token strings.
3. ⬜ The site's emitted graph contains nothing Tauri-only.
4. ⬜ Every existing frontend suite still passes, unweakened.
5. ⬜ CI builds both targets.
6. ⬜ No document in this repository claims the Android app renders the React tree.

## Limitations

Owns none yet. The row most likely to be registered here is the honest ceiling of the claim: the
Android target shares no view code, so a screen added to the launcher and the site does not appear on
the phone until somebody writes it in Compose. That is a property of the decision, not a defect — it
becomes a register row only if it starts producing surfaces that disagree, and its exit test would be
a check that the two settings surfaces expose the same keys rather than a shared component.
