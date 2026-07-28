# App — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the app category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE, then reconcile ../ROADMAP.md §2. Never rewrite an old note. -->

**Category:** app · **Last audit:** 2026-07-28 · Tasks completed: **6 / 10**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Scaffold + supervisor | ✅ COMPLETED | Attach mode; workspace-excluded crate |
| [2](Task.2.md) | Live metrics dashboard | ✅ COMPLETED | Real worker data; 65 crate tests; Home now splits worlds you administer from worlds you support ([worker 6](../worker/Task.6.md)) |
| [3](Task.3.md) | Packaging + CI | 🚧 IN PROGRESS | Build job green; installers and per-OS autostart remain |
| [4](Task.4.md) | End-to-end acceptance | ⏳ BLOCKED | Gate-both-ways green; cross-machine half needs worker 3 + minecraft 1 |
| [5](Task.5.md) | Telemetry consent | 🚧 IN PROGRESS | Modal + Privacy screen + 5 tests; a component test for button parity remains |
| [6](Task.6.md) | Dashboard API + live link | ✅ COMPLETED | `NODERA-WATCH` push (9 ms measured), typed Rust model, 92 crate tests |
| [7](Task.7.md) | The client becomes the way in | ✅ COMPLETED | LAN modal + Join a world + invitations + mod installer; 107 crate tests |
| [8](Task.8.md) | Screen redesign: one subject per screen | ✅ COMPLETED | 8 screens, each owning one subject; worker events drive the LAN prompt; generated licence manifest; 116 crate tests |
| [9](Task.9.md) | Tracker stores | ✅ COMPLETED | Trust lists added by URL or deep link; built-in store is deletable; the services sync file is the Android worker's only channel; verified on a physical device 2026-07-27 |
| [10](Task.10.md) | Practical screens, honest numbers | 🚧 IN PROGRESS | Cumulative traffic totals, per-screen scroll, one dashboard subscription, and A-UX-4's desktop/Material role repair landed; content and settings-honesty work remains |

---

## 2. Milestone notes (newest first)

### 2026-07-28 — Android worker startup waits for both lifecycle halves

Issue #86 review hardening replaces Activity-timed worker boot with a one-shot Rust gate: Android
context binding and Tauri setup's persisted-property handoff may arrive in either order, but both
must finish before Kotlin starts `HeadlessPeerMain`. The Java bridge class is retained as a JNI global
reference, so a Rust-created thread never depends on Android's bootstrap class loader finding an app
class. Evidence: **188** `nodera-app` tests, Android-target `cargo check`, frontend production build,
and a signed 185 MiB debug APK all pass.

### 2026-07-28 — One port encoder serves desktop env and Android properties

Issue #86 factors P2P bind settings into one encoder: desktop supervision still sends the same
environment pairs, while Android writes those pairs to the private property handoff consumed before
the in-process worker starts. Control is absent by contract, preventing an app/worker endpoint split.
Evidence: all **188** `nodera-app` tests green, including the two new parity/control guards. Mobile
owns the physical `self_route` exit and keeps M-NET-2 RETIRING until it runs.

### 2026-07-28 — Tracker stores follows both shells, proven from the bundle

Review found that replacing undefined desktop utilities was not enough: the same component is
rendered inside the Material 3 mobile shell, where desktop `--text`, `--surface`, and `--brand-*`
roles bypassed the user's dynamic source colour. The shared component now consumes semantic custom
properties selected at its root. Desktop resolves them to the app palette; mobile resolves controls,
cards, errors, inputs, dialog surfaces, and scrim to generated `--md-sys-color-*` roles.

The prior test only scanned literal `className` strings, which could pass while Tailwind emitted no
selector. It now runs after the production Vite build and verifies actual CSS rules for every desktop
and mobile role mapping, every consumed surface/control role, and both shell padding selectors.
Evidence: `built tracker-store CSS resolves desktop and mobile shell roles`, 187 app Rust tests, 408
Rust workspace tests, and the full Gradle gate. A-UX-4 remains retired on stronger evidence.

### 2026-07-28 — Tracker stores uses the generated palette and the right frame

The tracker-store screen's obsolete `text-fg*`, `*-accent*`, and `text-warning` utilities were
replaced with colours exported by `styles.css`. Desktop now wraps the route in the same
`max-w-[1100px] px-[26px] pt-5 pb-10` frame used by its peer screens. Padding stays outside the
shared component, so mobile retains its existing `px-4` frame without receiving desktop padding.

Evidence: `tracker stores uses generated colours and layout-specific page padding` passed inside
the production `bun run build`; all 187 app Rust tests and the full Gradle gate also passed. A-UX-4
moved to `LIMITATIONS.fixed.md`; Task 10 remains in progress.

### 2026-07-28 — Documentation sweep: statuses, test count, refactoring register

A category-wide audit reconciled every task header against the tree. **No code changed and no
limitation retired**: L-47 and L-56 stay OPEN (their exit tests are an architectural decision and a
cross-machine CI job, neither green), and A-UX-1…A-UX-5 stay OPEN (Task 10's content and
settings-honesty passes are still ⬜).

The test count was wrong in three places at once: TESTING.md carried **63**, Task 2's prose carried
**56**, and the actual source holds **183** `#[test]` / `#[tokio::test]` functions (grep-verified;
the crate was **not** re-run on this date — see TESTING.md). The ledger under-counted completed tasks
(5 → 6: Task 9 was missing from the §1 table) and Task 0's header still read "2 of 5".

Task 1's Files section named a `tray.rs` that does not exist — the tray is built by `build_tray` in
`lib.rs`. Corrected. A new [`REFACTORING.md`](REFACTORING.md) records the crate's structural debt:
jscpd flags **zero** duplicated blocks in `rust/nodera-app/`, so the register is manual candidates
only (the `lib.rs::run` god-function, `settings.rs`'s dual responsibility, the repeated wire→view
conversions in `api::model`).

### 2026-07-27 — Numbers that stop resetting, screens that start at the top

A screen-by-screen audit found no fake screens — all ten desktop screens reach a real backend and
eight reach the worker. The defects were honesty and ergonomics.

"Uploaded / Downloaded" reset several times a session because `total_sent_bytes` is a `LongAdder`
born with the worker's JVM, and the supervisor restarts that JVM whenever a restart-scoped setting
is saved. `DashboardStore` now banks the last reading of an ending lifetime and persists the bank to
`traffic-totals.json`, recording the last reading it saw so a restarted *app* can tell a worker that
was already running from a new one. Rates moved to a ≥1 s window, held between windows — the top bar
was dividing each 250 ms push on its own and flickering to `0 B/s` four times a second.

The "frozen screen" was one `overflow-y-auto` div with no `key`: React kept the same DOM node across
navigation and `scrollTop` belongs to the node. Keyed per screen, plus an explicit reset on the two
screens with internal tabs, and the same fix on the mobile shell. `useDashboard` was also subscribed
twice, giving two listeners and two initial fetches.

Evidence: 5 new `api::store` tests (13 green in that module), `tsc --noEmit` clean.

### 2026-07-26 — The screens were rebuilt around one subject each

Three agents inventoried the control plane, the Rust API and the screens before anything was
changed. The screen audit is what the redesign was built from, and its findings were specific: the
dashboard carried four unrelated subjects in one scroll; three of the world view's six tabs showed
node-wide data under a per-world heading; the app had **two different vocabularies for the same link
state** ("Connected"/"Alone" in the top bar and "Live"/"Polling" forty pixels below, able to
disagree on screen); and several rows were sentences invented in the component rather than data.

**One subject per screen.** Overview (this peer), Worlds (what this machine puts on the network,
including the LAN decisions), Join a world, Peers (connections + trackers + rendezvous — node-wide
facts that were previously tabs under a world), Peer console, Minecraft mod. About and Settings are
pinned to the bottom of the rail behind a rule, because they are about the *application*; everything
above is about the *node*.

**The "Live" pill is gone**, along with the second link vocabulary. There is now one readout, in the
top bar, and it states facts — `updated 3s ago` — adding words only when something is wrong. A
healthy link is shown by its facts, not described.

**Mocked values removed.** The world view's "What the person on the other end gets" card was four
invented sentences and is deleted. The directory's "Live game / Shared world" category was made up
from a piece count; it now shows the pieces and the health the tracker actually reports. The privacy
disclosure fetched the collector's real answer, labelled the section "read from the collector", and
then rendered a bundled list anyway — it now renders what the collector said.

**New: the Peer console** — the worker's own output as a screen rather than a drawer, with a filter,
follow-scroll, copy, restart, and the process facts that make the log make sense: resident memory
(and what share of the machine that is), CPU, pid.

**New: About** — this build's version, licence, protocol version and folders, plus every direct
dependency with its licence. The list is generated by `scripts/collect-licenses.py` from the
manifests the build reads and embedded at compile time; 44 packages, of which 4 have no licence
readable locally and are shown as unknown rather than guessed.

Unused surface retired: `fetchUnenforcedSettings`, `formatLimit`, `onPauseToggled`. `toggle_pause`
was dead and is now the Overview's pause control.

### 2026-07-26 — The app is how you use the network, not a window onto it

Four surfaces landed, all on top of [worker 7](../worker/Task.7.md).

**The LAN prompt.** Opening a world to LAN raises a modal offering three answers, not two: share,
decline, and *not now*. The third changes nothing and is the reason the dashboard also carries a
permanent LAN card — a prompt you can only answer once has to be answered immediately, which is the
design that makes people click the wrong button.

**Join a world.** A directory of what other people are sharing, searchable by name *or* id (a friend
sends you one or the other), with a Join that returns a loopback address and tells you to paste it
into Minecraft's Direct Connection. The copy repeats one thing deliberately: **joining does not
download the world.** Every other client in this shape means "fetch the content" by Join.

**Invitations.** A Share tab per world mints a `nodera:?xt=urn:nodera:…` link carrying the id, the
trackers and rendezvous services this node is really configured with, and the world's public key —
and no content and no password. Copyable, saveable as a `.nodera` file, and a pasted one can be
opened from the browse screen.

**The mod installer.** The app finds Minecraft installations and installs the jar it shipped with —
never a download, because the mod and the worker must be the same build to speak the same protocol.
It lists every installation rather than guessing one, and refuses to remove anything that is not a
Nodera jar.

### 2026-07-26 — The dashboard is rebuilt on an API, and the worker pushes into it

A screen of zeros was reported: shared worlds 0, players 0, peers 0, relay latency **0 ms**, and both
rates 0 B/s, none of them changing. Nothing in the plumbing was broken. The screen had no way to say
"I do not know", so it said "zero" — and `0 ms` was a `-1` from the worker, which means *unreachable
or never probed*, rendered as an instant handshake.

**The API.** `rust/nodera-app/src/api/` is now the whole data path: `model` (typed, `Option`
wherever unknown is a real answer, and a `link` block on every payload), `store` (one revisioned
picture, rates measured against a real clock), `link` (the connection), `commands` (what React
calls). `metrics.rs` stays as the wire mirror and nothing else reads it directly.

**The link.** The worker grew `NODERA-WATCH`: it holds the connection open and writes when its own
state changes, with a 10 s keepalive so silence is evidence rather than ambiguity. Measured against
the built worker: a world hosted on one connection reached a watcher on another in **9 ms**, and an
idle node sent one line in the first second and then nothing. Polling survives only as the fallback
for a worker too old to know the verb, and the UI says which of the two it is getting.

**The screen.** A status band states the link's condition before any number appears — live/polling/
connecting/offline, how old the picture is, the accepted-snapshot count (so motion is visible when
the values are still), and the failure in the words of whatever failed. Tiles render "—" and a
reason instead of a zero. The world view gained an **Ownership** pane with a proof button, carefully
labelled: it shows the key is on this machine, and is not a verification.

Evidence: `ControlWatchStreamTest` (6 Java) and 22 new Rust tests, including six that drive the link
over a real socket — the offline→online edge fires once per connection, an unreadable line surfaces
instead of freezing the screen, and an absent worker never claims data.

### 2026-07-26 — "The app shows no data" was the worker forgetting, and Home now asks the right question

The app was read end to end against a running worker looking for the broken link, and there was not
one: `control.rs` connects, `NODERA-STATE` parses, the metrics pump emits, the UI renders. What the
worker was sending was `"connected_worlds": []`, because it held its world set in memory and dropped
it on every restart. The fix is [worker 6](../worker/Task.6.md); the app's part was to stop asking a
question it could not answer correctly.

**Home used to split on `seeding`** — "am I hosting this?" — under a heading that reads "Your worlds".
Those are different questions, and the difference is not cosmetic: a world fetched from somebody else
and re-shared here appeared as yours, and a world you created stopped being yours the moment you
stopped hosting it. Home now splits on `owned`, which the worker answers from the world's private key,
so the two lists are **worlds you administer** and **worlds you support**. Neither can be inferred
from the other, so both fields cross the wire.

The world detail view gained the world's public key and who administers it, and a supported world this
node happens to host says so rather than looking like a plain seeder.

Evidence: `metrics.rs` gained two tests — one pinning that hosting is not authorship in both
directions, one pinning that a worker too old to report ownership owns nothing rather than claiming
everything.

### 2026-07-26 — The disclosure answers even when nothing answers it (L-78 RETIRED)

The row said the offline fallback "can lie by being stale". Reading the code, it was worse: there
was no fallback. `collected_schema` returned an **error** when the collector could not be reached,
so a person asking "what is collected?" with no route to the ingest service got a blank screen at
exactly the moment they were deciding whether to consent.

The registry moved out of the ingest **binary** and into the `nodera-telemetry` **library**, which
the app now depends on by path. That is the part worth keeping: a privacy notice maintained in two
places drifts, and the stale copy is always the one people read. One source now renders both the
service's live answer and the app's bundled fallback.

Every copy carries `registry_version` — the repository `VERSION`, stamped by `build.rs` — and
`disclosure_source` (`service` or `bundled`). A fallback that cannot say how old it is looks exactly
like a current one, which is the specific way this row said it could lie.

`the_disclosure_falls_back_to_the_bundled_registry_when_the_service_is_unreachable` and
`the_bundled_copy_says_it_is_bundled` are the exit clause. `TelemetryRegistryMirrorTest` still passes
against the real binary, so the Java emitter and the Rust registry did not drift while this moved. An
**unconfigured** collector stays an error: nothing to disclose is not the same as cannot reach.

### 2026-07-25 — The question gets asked, once, with neither answer favoured

The first-run modal and the Privacy card landed (`src/telemetry.rs`, `ui/src/Consent.tsx`; 61 crate
tests, 5 of them telemetry).

Two things were deliberately made harder than the easy version:

- **Neither button is primary.** Same size, same border, same hover, no pre-selection. A dialog
  engineered to make "yes" easier produces consent worth nothing, and this project's pitch is
  precisely that players are not a central operator's product.
- **"Already asked" is a file, not a settings field.** It survives a settings reset, because *we
  have already asked this person* is a fact about them rather than a preference of theirs. It is
  written by both answers — and by a failed push too, so a busy node cannot cause a re-ask.

The category's existing badge discipline carried straight over: the toggle shows what the **worker**
confirmed, and a worker that predates the verb gets a distinct "worker cannot" badge rather than
being rendered as "off". One is a choice; the other is a worker to restart.

### 2026-07-25 — The app gains the only place a person is asked

[Task 5](Task.5.md) puts the telemetry question in the companion app, and it inherits the discipline
this category established with the config lane in the same week: **the badge shows what the worker
confirmed, never what the app intended.** A consent toggle reading "on" while the worker never
received the decision would be the worst possible version of that bug.

Three rules are written into the task file as refusable constraints rather than as guidance: neither
answer is pre-selected or styled as preferred; declining is final and the modal never returns; and
the app never sends telemetry itself — it tells the worker, which owns the record.

The "what is collected" disclosure reads the registry from the ingest service the user actually
reports to, rather than from prose maintained beside it. A privacy notice kept separately from the
enforcement drifts, and it always drifts in the uncomfortable direction. Registered **L-78** for the
offline fallback, which can be stale and is labelled as such.

### 2026-07-25 — The configuration lane and honest badges

The app gained a settings surface backed by the worker's `CONFIG` verb, and with it a decision worth
recording: **a setting the node cannot honour is badged, not faked.** Results come back per key as
applied, rejected with a reason, or restart-required, and the UI renders each distinctly.

Two connection settings are permanently unsupportable against the current architecture — a per-world
connection cap (the socket transport has no world dimension at all) and an unlimited-connections-only
filter (no peer advertises a connection cap on the wire). Both are **kept in the UI on purpose**, with
a muted "not supported" badge carrying the worker's own reason, deliberately distinct from the amber
"not enforced yet" which would imply the feature is coming. Deleting them would silently drop values
users had already saved and would hide that the limitation is known (**L-56**).

Same period, from the storage side: relocating the archive directory stopped being restart-only, and
the restart button the app offers is shown **only when it supervises the worker** — in attach mode it
says to restart it where it was started, because the app must not kill a process it did not spawn.

### 2026-07-24 — The dashboard stops showing zeros

An audit of the tracker → rendezvous → worker → mod → companion chain found capabilities that were
built and tested but never connected, and two of them surfaced here.

**Per-peer throughput was hardcoded to zero.** `PeerTrafficMeter` gave the Peers tab real totals and
rates.

**The piece map had no source at all.** The seam that installs a piece-map source had never been
called anywhere, so "View pieces" always opened an empty grid. A `PIECES` control verb, a parser, and
a feed connected it — and the grid gained scrolling, which it had silently lacked (it simply stopped
drawing at the bottom edge, with nothing said), plus a "peers sharing this world" count distinct from
complete seeders.

The app was rebuilt in the same pass as a torrent-client-shaped **Info / State / Peers / Trackers /
Pieces** tab set inside VPN-client connection chrome — the mental model players already have for a
background process moving data on their behalf. Crate tests grew to cover bitmap decoding against
Java's `BitSet` byte order, bounded/short/undecodable bitmaps, additive-field tolerance, the log ring,
and system sampling.

### 2026-07-23 — The companion CI job; L-47's first form RETIRED

The job builds the app end to end, runs the gate both ways, and proves hosted-world survival with a
real daemon and a SIGKILLed stand-in game process. It immediately paid for itself by surfacing four
packaging gaps that only a **clean-checkout** build could reveal: distribution staging, a gitignored
UI build, gitignored bundle icons, and a gitignored tray icon. All four had the same shape — files
that worked locally because they happened to exist on disk.

A narrower row of the same id stays open for the genuinely cross-machine half.

### 2026-07-21 — Live metrics

A one-second pump probes the worker for liveness, fetches `STATE`, and emits real chunks, bytes,
peers, and world data to the dashboard. The parser uses defaults for unknown fields, because worker
`STATE` fields are additive: a worker newer than its dashboard must show fewer panels, never an error.

### 2026-07-20 — Scaffold and supervisor

Tray, window, single-instance guard, autostart registration, and an **attach-aware** supervisor. The
attach semantics were a correctness decision, not a convenience: two supervisors both believing they
own one worker would fight over its lifecycle, so quitting stops only a worker the app itself spawned.
`scripts/dev.sh --with-app` runs the app in attach mode against an externally started worker.

The crate was excluded from the headless Rust workspace gate because Tauri's native webkit
dependencies would burden every unrelated Rust change — a justified exclusion whose cost (CI never
compiling the app) became a named deliverable rather than an unnoticed gap.
