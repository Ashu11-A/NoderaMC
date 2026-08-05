# App Task 11 — A launcher, not a dashboard

<!-- AI-AGENT-INSTRUCTION: Three rules this task exists to preserve. (1) The app has ONE front page
     and its job is getting into a world; every subsystem screen lives under Settings and stays
     there. (2) `nodera-core` must never depend on `tauri` — two front ends read it, and a single
     `AppHandle` in that crate is a thing only one of them can use. (3) Play states which route it
     will take BEFORE the button is pressed, and every failure carries a `Remedy` enum rather than
     prose. Reverting any of the three re-opens the defects below. Keep this header accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** app · **Owns:** L-91 … L-94 · **Last audit:** 2026-08-01
**Depends on:** [app 10](Task.10.md), [app 9](Task.9.md)

---

## Goal

The companion app is the thing a player opens to play. Pressing one button joins a world and starts
Minecraft in it. Everything about the machinery — trackers, stores, peers, the worker console, the
mod installer — is reachable, and none of it is in the way. On Android the same node runs behind a
genuinely native Material You interface rather than a webview imitating one.

## What was wrong

The rail carried nine destinations, one per subsystem: Overview, Worlds, Join a world, Tracker
stores, Peers, Peer console, Minecraft mod, About, Settings. That is an accurate map of the software
and a useless one for a player. Three consequences, all visible on screen:

- **There was no Play.** `join_world` opened a tunnel and returned `127.0.0.1:<port>`, and the player
  was told to type it into Minecraft's Direct Connect box. The launcher's job was left undone.
- **Worlds was a report.** One card carried a completeness bar, a percentage, a three-column figure
  table and a tracker-visibility footer — eleven numbers for a thing a person is trying to
  *recognise*.
- **Android was Material You in name only.** The same React bundle, with M3 imitated in CSS and an
  accent colour picked from five swatches, because a WebView cannot read the wallpaper. The app said
  so honestly, which did not make it Material You.

## What landed

### One core, two shells

`library/rust/nodera-core` holds everything the app *is*; `app/` keeps only what needs a webview. The
seams are traits the shell implements — `api::link::Sink`, `api::events::EventSink` (both predate the
split) and the new `browser::LinkOpener`. `NoderaCore` is one handle with the command bodies as
methods, and `start_shared_loops` starts the six loops that are identical on both platforms, so a
front end cannot forget one.

Two things this surfaced. The core is now inside the root workspace, so `cargo test --workspace`
covers 12,240 lines it had never compiled — 109 tests before, 674 after. And `cfg!(desktop)` is
emitted by `tauri_build`, so outside the shell crate it is not an error, it is `false`: two gates in
`daemon.rs` came along and would have told every desktop user their worker could not be restarted.
They are `SUPERVISES_A_WORKER` now, spelled in `target_os`.

### The launch lane

`nodera-core/src/launch/`, desktop-only and structurally so. Four routes, best first, and the app
says which one it will take before the button is pressed:

| Tier | What it does | Lands you |
|---|---|---|
| Prism / MultiMC | `--launch <instance> --server <addr>`, forwarded as `--quickPlayMultiplayer` | in the world |
| Direct | this app assembles the command line and spawns the JVM | in the world — L-91 |
| `servers.dat` | writes the address into the Multiplayer list, opens the official launcher | one click away — L-93 |
| Address | shows `127.0.0.1:<port>` | the floor, always available |

The route is chosen **before** the tunnel is asked for, so "no installation" and "no mod" cost
nothing. `LaunchCoordinator` owns one correlated attempt across React remounts, rejects a concurrent
Play, invalidates stale task events, and owns tunnel cleanup. Direct launches close with the game;
delegated launchers cannot expose game lifetime, so their handoff stays visible with an explicit
Close connection action. Explicit leave reports `closing` until the worker proves the tunnel absent,
never `exited` while cleanup is still running. Stable target ids distinguish profiles whose display
names or game folders collide. Failures carry a `Remedy` enum, so rewording an error cannot make its
action vanish.

### The desktop, redesigned

Play / Library / Discover, and a gear. Overview dissolved: its one irreplaceable sentence — why
sharing is paused, which is the difference between a node that stopped and one that looks like it
crashed — is a line in the hero. Worlds is a grid of generated pictures, hashed from the world id, so
a world looks the same on every machine and the art is something you learn rather than decoration.
CSS-only, because the CSP forbids a remote image and permits an inline custom property.

Every screen now uses a centred 12-column canvas up to 1,680 px rather than an 860/1,100 px
left-aligned cap. Settings becomes a 3+9 sidebar/content composition and its configuration cards form
two-column grids on wide windows. Discover, Peers, tracker stores, and dependency inventories gain
bounded pagination; large piece maps become truthful contiguous-range density buckets. The visual
system is replaced rather than rearranged: bundled Space Grotesk + Inter, obsidian surfaces,
Ender-green action roles, semantic amber/blue/red, new controls, tables, dialogs, cards, and focus
roles. Consent and LAN prompts use the same focus-trapping `Modal`.

### Android, natively

`MainActivity` is a `ComponentActivity`; explicit Material 3 `NavigationBar` / `NavigationRail`
adapt at 600 dp, while screen grids and settings list-detail layouts adapt again on tablets. The
palette comes from `dynamicDarkColorScheme(context)`. Tauri stays the **build system** —
`cargo tauri android build` owns NDK cross-compilation and `jniLibs` staging — and stops being the
runtime.

The bridge is two JNI calls, not fifty-two: `nativeStart` and a verb-and-JSON `nativeInvoke`. A wrong
JNI signature is not a compile error, it is a `NoSuchMethodError` on a device, so there is one place
to get right. Events go the other way, pushed into a `SharedFlow` — polling would have re-introduced
the cadence this codebase spent a release removing.

Physical Android 15 testing proved that push path: the dashboard moved from Connecting to Online
after Rust called Kotlin's static event sink. It also fixed three boundaries only ART exposed:
owner-only identity creation when `getFileStore` is denied, one shared `dataDir` contract, and Peers
querying the worker's reported identity instead of creating an app-owned second peer.

There is no Play button on the phone. There is no Java Minecraft client on Android, so the launch
module is compiled out entirely and a Compose screen cannot call it by accident. What the phone is,
and what its Home screen says, is a node that keeps other people's worlds alive.

Network, tracker stores, Storage, Battery, Peers, Privacy, Diagnostics, About, licences, onboarding,
and system-back navigation are native Compose surfaces. Settings values are parsed into controls;
unknown battery/network reads never render as success, numeric values round-trip without clamping,
and a store deep link confirms the exact URL that was previewed. Saving worker settings invalidates
the prior enforcement verdict before the debounced push, generation-checks its reply, and refreshes
the badge through the worker timeout. Privacy keeps pending local consent distinct from confirmed
worker consent and allows offline withdrawal; Peers reloads and clears stale self-tests when worker
identity or tracker state changes.

Trackers and stores now share one native page: direct endpoints, subscribed publishers, effective
tracker/rendezvous provenance, preview-before-trust, and removal impact are visible together. First
run is four scroll-safe steps covering purpose, write-probed storage, Android background policy, and
an explicit telemetry answer. Android saves each owned settings field atomically, preserves unsaved
forms across recreation, restores pending deep links, migrates the former `filesDir/nodera` state to
the corrected `dataDir/nodera` root, and gives switches row-level accessibility semantics.

## Exit tests

Source-text and built-CSS rules, in `app/ui/tests/`:

- **`design-tokens.test.mjs`** — every styling class written in `src/**` resolves to a rule in the
  shipped stylesheet. This is the general form of the consent modal's bug, where four class names
  named tokens the theme did not declare and the first screen a new user sees rendered transparent.
  It also proves the hero's own declarations reach `dist`, and reads the Rust `LaunchPhase` and
  `Remedy` enums to prove every variant has a word on the Play screen.
- **`ux-honesty.test.mjs`** — the staleness rule is derived now rather than listing four screen names
  that drifted every time one moved: the shell exports `WORKER_FIGURE_SCREENS` and the test asserts
  every screen module touching the dashboard is in it.
- **`launcher-review.test.mjs`** — stable target identity, Discover→Play, state restoration ordering,
  visible close failures, accessible dialogs/search, wide world composition, bounded piece maps, and
  light-theme contrast.
- **`scripts/android-apk.sh`** asserts what it patches and the native review invariants — every
  JNI-reachable class has an R8 `-keep` rule, Compose lands in generated Gradle, `MainActivity` is
  `singleTask`, deep-link preview identity is bound, back handling exists, and numeric settings are
  not truncated.
- **`nodera-core` launch tests** cover strict ids, stable targets, concurrent launch refusal,
  leave-during-launch invalidation, bounded ambiguous-CONNECT cleanup, delegated handoff, and shutdown
  ownership. **270 tests** pass in the shared core; the frontend passes **31** tests.

Human protocols for the launch lane and the Android build are in [TESTING.md](TESTING.md).

## What is not done

[L-91](LIMITATIONS.md) (no Microsoft credential, so the direct route is gated), L-92 (the offline
account is development-only), and L-93 (`servers.dat` is one click, not auto-connect). L-94 is
RETIRING: physical startup, navigation, deep-link routing and Peers self-test are green; the unified
tracker/store page, four-step onboarding, storage change, and battery controls are host-build green
but still need final touch/restart evidence on the phone.
