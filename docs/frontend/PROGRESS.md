# Frontend — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the frontend category — desktop launcher,
     Android companion and website. On every outcome-changing commit touching this category: update
     the §1 row, append a dated §2 milestone note naming the EVIDENCE (a test, a log line, a
     control-socket answer), then reconcile ../ROADMAP.md §2. Never rewrite an old note. The notes
     below §2 are the merged ledgers of the former app and mobile categories, kept
     verbatim; only task NUMBERS inside them were rewritten by the 2026-08-05 merge (mobile N is now
     frontend N+11). -->

**Category:** frontend · **Last audit:** 2026-08-10 · Tasks completed: **10 / 20**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md) · refactoring
register: [`REFACTORING.md`](REFACTORING.md).

---

## 1. Task status

### 1.1 Desktop launcher and shared core

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Scaffold + supervisor | ✅ COMPLETED | Attach mode; workspace-excluded crate |
| [2](Task.2.md) | Live metrics dashboard | ✅ COMPLETED | Real worker data; 65 crate tests; Home now splits worlds you administer from worlds you support ([worker 6](../peer/Task.6.md)) |
| [3](Task.3.md) | Packaging + CI | 🚧 IN PROGRESS | Build job green; installers and per-OS autostart remain |
| [4](Task.4.md) | End-to-end acceptance | 🚧 IN PROGRESS | Installer entry point + routed second network landed; the `e2e-live / cross-machine` run is the exit |
| [5](Task.5.md) | Telemetry consent | 🚧 IN PROGRESS | Modal + Privacy screen + 5 tests; a component test for button parity remains |
| [6](Task.6.md) | Dashboard API + live link | ✅ COMPLETED | `NODERA-WATCH` push (9 ms measured), typed Rust model, 92 crate tests |
| [7](Task.7.md) | The client becomes the way in | ✅ COMPLETED | LAN modal + Join a world + invitations + mod installer; 107 crate tests |
| [8](Task.8.md) | Screen redesign: one subject per screen | ✅ COMPLETED | 8 screens, each owning one subject; worker events drive the LAN prompt; generated licence manifest; 116 crate tests |
| [9](Task.9.md) | Tracker stores | ✅ COMPLETED | Trust lists added by URL or deep link; built-in store is deletable; the services sync file is the Android worker's only channel; verified on a physical device 2026-07-27 |
| [10](Task.10.md) | Practical screens, honest numbers | 🚧 IN PROGRESS | Cumulative traffic totals, per-screen scroll, one dashboard subscription, A-UX-4's desktop/Material role repair, and the UX honesty bundle (A-UX-1/2/3/5) landed; the content pass remains |
| [11](Task.11.md) | A launcher, not a dashboard | 🚧 IN PROGRESS | Adaptive cinematic desktop launcher + native Compose companion landed; physical Android startup/navigation/deep-link/Peers acceptance passed, while direct sign-in and full settings mutation remain |

### 1.2 Android

| Task | Title | Status | Evidence |
|---|---|---|---|
| [12](Task.12.md) | The Android build, and the worker inside it | ✅ COMPLETED | `scripts/android-apk.sh` → signed `build/nodera-release.apk` whose `assets/nodera-worker.jar` is the dexed `:peer` closure; installed and re-audited on Android 15 |
| [13](Task.13.md) | The interface: Material You, and what a phone may be asked | ✅ COMPLETED | Native Compose Material 3; dynamic wallpaper colour on 12+; bar under 600 dp, rail above |
| [14](Task.14.md) | The phone in the mesh, and how it is proven | ✅ COMPLETED | `scripts/e2e-android-mesh.sh` asserts the phone's own `total_received_bytes` moves after joining the Linux mesh |
| [15](Task.15.md) | The settings the app can keep, and the verbs it never asks for | ✅ COMPLETED (2026-07-28) | All four §Exit clauses pinned by `cargo test`; §3 is a living worker-verb census |
| [16](Task.16.md) | The phone reaches the network it was told to | 🚧 IN PROGRESS | Owns M-NET-1 … M-NET-4. Service-file path aligned; tracker stores follows dynamic Material 3 roles; P2P app-property handoff headless-green and M-NET-2 RETIRING; live re-read, Android restart, and touch acceptance remain |

The five rows above were the mobile category's `Task.1…5` before the 2026-08-05 merge. The eight rows
below are
that category's own sub-deliverable ledger, preserved because the milestone notes in §2 cite it:

| # | Sub-deliverable | Status | Evidence |
|---|---|---|---|
| 1 | Android build pipeline | ✅ COMPLETED | `scripts/android-apk.sh` → signed `build/nodera-release.apk`, installed on a Xiaomi 2210129SG |
| 2 | The Java worker runs on ART | ✅ COMPLETED | `NODERA-PROBE 2` → `NODERA-OK 2 0.1.0` from the device; `self_route 10.0.0.104:25620` |
| 3 | Native Material You | ✅ COMPLETED | Compose Material 3 reads Android 12+ wallpaper colour; fallback scheme below 12 |
| 4 | Adaptive native layouts | ✅ COMPLETED | `NavigationBar` under 600 dp, `NavigationRail` above; content panes choose their own split width |
| 5 | First-run setup + storage + battery | ✅ COMPLETED | SAF picker returns `/storage/emulated/0/Documents`, write-probed; battery dialog names the vendor |
| 6 | Native navigation | ✅ COMPLETED | Compose `BackHandler` walks Licences → About → Settings → Home |
| 7 | The phone in the mesh, tested | ✅ COMPLETED | `scripts/e2e-android-mesh.sh` — asserts the phone's own `total_received_bytes` moves after joining the Linux mesh |
| 8 | The services list the worker actually reads | 🚧 IN PROGRESS | [Task 16](Task.16.md): service-file path aligned; tracker stores follows dynamic Material 3 roles; P2P app-property handoff headless-green and M-NET-2 RETIRING; live re-read, Android restart, foreground service, and touch acceptance remain |

### 1.3 The redesign, the site, and shipping them

| Task | Title | Status | Notes |
|---|---|---|---|
| [17](Task.17.md) | A launcher, redesigned | 🚧 IN PROGRESS | Opened 2026-08-05. Sidebar rail, obsidian/purple system, Theme screen. Implementation lands in the same pull request as this file |
| [18](Task.18.md) | The website | 🚧 IN PROGRESS | Opened 2026-08-05. React site at noderamc.org, subsuming `web/index.html` and keeping `web/add-store.html` behaviour |
| [19](Task.19.md) | One codebase, three exports | 🚧 IN PROGRESS | Opened 2026-08-05. `web/` and the app's React tree unified; Android shares the core and the worker, not the React tree |
| [20](Task.20.md) | Shipping it | 🚧 IN PROGRESS | Opened 2026-08-05. Site image from GitHub Actions, pulled by the VPS behind Caddy, replacing the SSH push |

---

## 2. Milestone notes (newest first)

### 2026-08-10 — The job installs the app, and the joining peer is somewhere else

Issue #189, L-47. Two thirds of the product's own sentence were green and had been for months; the
missing third was that **nothing installed anything** and **both sides shared a loopback**. Task 4's
header had also been claiming ⏳ BLOCKED on two things that were already discharged — worker 3's
announce timer (deliverable 7 ✅) and minecraft 1 (✅ COMPLETED, driving real clients under Xvfb) —
which is corrected here, because a stale blocker stops people looking.

*The installer.* `scripts/install-app.sh` builds the real `.deb` through `scripts/release.sh
--component app`, installs it with `apt-get`, and exports `NODERA_E2E_WORKER_BIN` /
`NODERA_E2E_APP_BIN` from `dpkg -L`. The asset name comes from `scripts/lib/release.sh` (the one
naming table; never a glob, never a name in YAML) and the installed paths come from the package, so
neither can drift. `TestPaths` honours the two variables and the live stack launches what was
installed. The companion job's `cd app && cargo build --release` is gone.

*The second network.* `CrossMachineScenario` (`cross-machine`, tags `live`) builds two Docker bridge
networks joined by a router container; the joining worker runs on the far one with its **default
route deleted**, and the host stack binds the host bridge's address specifically rather than
`0.0.0.0`, so it is not listening on the far bridge at all. The decisive stage is the negative one:
X3 stops the router and requires the far peer to lose all reach, then restarts it and requires the
reach back. Every route assertion is on `NODERA-STATE.self_route` — the node's own account of itself
— never on "it connected", which is the false green `docs/frontend/Task.4.md` names.

Evidence: `CrossMachineWiringTest` fails before the harness learned to take an address from a system
property (`expected: "172.31.60.1"`, 3 tests / 1 failed) and passes after (3/3); `:testing:test`
49 passed / 0 failed / 0 skipped; the routed topology was measured by hand on this machine — far peer
to a host-side service **exit 0 with payload** while the router ran, **exit 124** with it stopped,
**exit 0** again on restart, and the peer's own loopback carrying nothing. The installed
`nodera-headless` was booted inside the scenario's Alpine JRE image and announced, found seeders and
replicated a world archive, which is why the image carries no build step.

Not done, and named rather than implied: **the exit run itself.** `e2e-live / cross-machine` is
nightly-and-dispatch and the reference box could not host it — its Docker is rootless, so bridge
gateways are addresses in a user namespace and no container can reach a host service (the scenario
now says exactly that and skips), and the machine had 2 GiB free against the scenario's 4. Task 4
deliverable 5 also stays 🚧: the joining side is a real *peer*, not a real *client*. L-47 is
therefore RETIRING, not RETIRED.

### 2026-08-05 — The enforcement table reads as a table, and four buttons become one

Plan 11 phase 4 (issue #213). `settings.rs`'s `ENFORCEMENT` is what decides the badge beside every
control, and each row was a six-line expansion that put "how is this enforced" four lines from the
key it belongs to; `const fn live/local/never` put it back on one line and took 74 lines with it.
`TrackerStores.tsx`'s `PrimaryButton`/`SecondaryButton`/`GhostButton`/`DangerButton` are one
`StoreButton` with a variant — the four class strings survive verbatim, because
`tests/tracker-stores-style.test.mjs` asserts every `--tracker-store-*` declaration reaches the built
CSS, which is also why the screen still cannot simply adopt `components.tsx`'s `Button`.

Evidence: `cargo test -p nodera-core` (287 passed **on 2026-08-05**; the crate has grown since, and
the current figure is README's gated cell, not this one), `cargo clippy -p nodera-core --all-targets
-D warnings` clean, `tsc --noEmit` clean over `TrackerStores.tsx`. `REFACTORING.md` gains two Completed
rows and two new candidates, including the three-times-written "a launch failure closes its tunnel"
in `NoderaCore::start_play`.

### 2026-08-05 — Two categories become one, and three lanes open against it

The `app` and `mobile` categories are now one, `frontend`, covering every user-facing
surface: the Tauri desktop launcher, the Android companion, and the public website. The app tasks
kept their numbers; the mobile tasks shifted by eleven (`mobile 1…5` → `frontend 12…16`). The two
ledgers, both testing guides, both limitation registers and both refactoring registers were merged
rather than summarised — every row and every dated note above and below this one came from one of
them.

**No status moved in this commit.** Not a limitation, not a task, not a test count. A merge that also
retires rows makes it impossible to tell which change the evidence belonged to.

Four tasks open here as real specifications rather than placeholders, each honest that its
implementation lands in the same pull request: [17](Task.17.md) rebuilds the desktop UI from a
reference design and adds a Theme screen; [18](Task.18.md) is the public site;
[19](Task.19.md) unifies `web/` with the app's React tree — and records the constraint that Android
is native Compose, so "three exports" means desktop and web share React while Android shares the
core, the worker and the protocol; [20](Task.20.md) replaces the SSH push with a published container
image the VPS pulls.

### 2026-08-01 — Native settings and launch cleanup survive concurrent lifecycle changes

Trackers and stores now share one Compose page with effective-service provenance and explicit
removal impact; onboarding is a four-step storage/background/privacy decision rather than one dense
form. Typed settings mutations stop one screen from replacing another screen's newer values, the
background store refresh replaces only entries unchanged since its fetch began, and configuration
status invalidation is atomic. Activity recreation preserves unsaved forms and pending store links;
upgrades migrate state from the former `filesDir` root. Explicit desktop leave now reports `closing`
until tunnel cleanup proves absence.

Evidence: 270 `nodera-core` tests, 31 frontend tests, Tauri shell test/clippy, isolated
`./gradlew check --rerun-tasks`, and a full worker-dex `scripts/android-apk.sh --debug` build all
pass. Physical rerun remains pending because no ADB device was connected (`10.0.0.104:5555`
refused the documented Wi-Fi connection).

### 2026-08-01 — Native Android survives physical startup and reports the worker, not a second peer

Android 15 physical testing proved static JNI dashboard events, native navigation, tracker-store
deep-link routing, truthful nullable rates, telemetry status and Peers self-test. Three live-only
defects were fixed: Android's denied `getFileStore` no longer kills worker identity persistence;
Peers verifies the Java worker identity instead of announcing a second Rust identity; and commons
presence is excluded from world replication. Evidence: installed 201 MiB debug APK, Online with 2/2
trackers, self-test passed, and `scripts/android-e2e.sh` **5/5**. L-94 remains RETIRING until Network
and Storage edits plus store add/remove survive restart on-device.

Post-audit hardening makes those settings surfaces evidence-based under races: every configuration
push invalidates the previous worker verdict, stale in-flight replies cannot restore it, Compose
polls through the control timeout, pending telemetry remains revocable offline, and Peers discards
self-test results from an older identity/tracker generation.

### 2026-08-01 — Every app screen is rebuilt around Play or node configuration

Desktop uses Play / Library / Discover, a 12-column 1,680 px canvas, wide settings grids, paginated
data surfaces, new bundled typography, and an obsidian/green launcher system. Discover now enters the
same launch coordinator as Play. `LaunchCoordinator` retains correlated state, stable target ids and
tunnel ownership outside React, rejects concurrent attempts, invalidates leave/spawn races, and says
when a delegated launcher is only a handoff.

Android is native Compose Material 3: dynamic wallpaper colour, bar/rail navigation, adaptive world
grids and settings list-detail, native Network/tracker-store/Storage/Peers/Diagnostics/licence screens,
and first-run onboarding. Unknown probes no longer render successful battery or network states.

Evidence: `./gradlew check`; full `cargo test`; **270** `nodera-core` tests; app shell test/clippy;
**31/31** frontend tests; `scripts/android-apk.sh --debug --skip-worker` produced and verified
`build/nodera-debug.apk`. L-94 moves OPEN → RETIRING pending physical touch/deep-link acceptance.

### 2026-08-01 — Four-step setup and unified services pass the full host gate and hardened E2E

Native onboarding now separates purpose, verified world storage, battery optimization, and explicit
telemetry consent. Settings unifies direct trackers and subscribed stores, preserves in-progress
edits across Activity recreation, exposes whole-row switch semantics, and restores an offered store
after recreation. The corrected Android data-root contract migrates legacy settings and identity
files rather than treating an upgrade as a fresh install; worker-authorized app-private and
app-specific external roots carry concrete storage choices.

Evidence: full Rust workspace and app test/fmt/clippy gates, **270** shared-core tests, **31**
frontend tests, isolated `./gradlew check --rerun-tasks`, and full `scripts/android-apk.sh --debug`
with a newly dexed worker. The resulting `build/nodera-debug.apk` is 201 MiB. On-device,
`scripts/android-e2e.sh --no-install` against a fresh tracker passed **5/5** under the single
130-second deadline, the independent querier returning worker `a3da8287-…` at `10.0.0.104:40675`.

### 2026-08-01 — Physical Android audit: worker online, one identity, five E2E checks

A debug APK was installed over the existing app on Xiaomi 2210129SG (Android 15 / API 35). First
launch exposed Android denying `Files.getFileStore` inside app-private storage; the shared atomic
writer now attempts owner-only creation directly when store inspection is forbidden. The worker then
persisted identity, bound `10.0.0.104`, served control on `127.0.0.1:25610`, and reported both the
local tracker and published tracker reachable.

The Peers screen also exposed a second app-owned identity. Status and self-test now query the commons
namespace for the Java worker's `NODERA-STATE.node_id`, never signing another announce. Finally,
replication excludes the synthetic commons id instead of trying to fetch it as a world archive.
Evidence: `scripts/android-e2e.sh --no-install --tracker 10.0.0.101:25600` **5 passed, 0 failed**;
independent `nodera-query` returned worker `a3da8287-…` at `10.0.0.104:39957`; post-fix log says
`Replication sweep: 2 tracker(s) list no worlds` and carries no exception or crash.

The E2E proof now takes a successful pre-launch tracker baseline and refuses a retained copy of the
same UUID, wraps both adb and device probes in one wall-clock deadline, and matches the independent
query's UUID token exactly. Its budget spans two 60-second commons rounds after a physical run
showed the old 60-second edge lose the retry race. A cached tracker row can no longer make a broken
fresh launch pass. That hardened run reached **4/5** before its former 60-second budget expired; the
corrected single 130-second deadline has since passed **5/5** on a reconnected device.

### 2026-08-01 — Android presentation becomes native Material You end to end

`MainActivity` no longer hosts the application in a WebView. Native Compose Material 3 now owns
onboarding, Home, adaptive Worlds, structured Activity, and Appearance / Network / tracker stores /
Storage / Battery / Peers / Privacy / Diagnostics / About / licences. Phone uses a navigation bar;
tablets use a rail plus grids or list-detail only when remaining pane width supports it. Dynamic
colour comes from Android's wallpaper on 12+.

Review hardening binds tracker-store confirmation to the immutable previewed URL, adds system-back
hierarchy, preserves unlimited connection limits and exact sweep seconds, makes onboarding
scroll-safe, and prevents unknown battery/network probes from painting success.

Evidence: `scripts/android-apk.sh --debug --skip-worker` compiled Compose/Kotlin/Rust, aligned,
signed and verified `build/nodera-debug.apk`; its source guards cover preview identity, back handling,
numeric preservation and non-clickable status semantics. App L-94 is RETIRING pending physical touch
acceptance; no device claim is made from a host build.

### 2026-07-30 — The tracker-store deep link gets somewhere it can be linked from

App [task 9](Task.9.md) shipped a working `nodera://tracker-store?url=…` handler and assumed a
publisher could put that href on a page. Most cannot: GitHub's markdown sanitiser — and every
sanitiser built the same way — strips any scheme but `http`/`https`/`mailto`, so the project's own
README button was a dead image. The link was never broken; there was nowhere to put it.

`https://noderamc.org/add-store?url=…` is the hop that was missing, and it is a **static page, not a
service**: `web/add-store.html`, served by Caddy from `web/noderamc.caddy`, deployed by
`scripts/deploy-site.sh`. It keeps this task's own line — a non-https index is refused before it is
offered, the address is shown exactly as received, and the scheme is invoked only from a real click,
because a page that redirects on load is a drive-by intent. Visitors without the app get the address
and a copy button rather than a button that silently does nothing.

Evidence: live on 2026-07-30, `noderamc.org` and `www.noderamc.org` both `200` with Let's Encrypt
certificates issued through the host's existing `acme_dns cloudflare`; the apex answered **525**
before this (Cloudflare proxied the domain to a Caddy with no site block for it). Android still
needs one extra tap through the browser — App Links want a stable release signing key, recorded as
the follow-up in [`Task.9.md`](Task.9.md).

### 2026-07-28 — Android worker startup waits for both lifecycle halves

Issue #86 review hardening replaces Activity-timed worker boot with a one-shot Rust gate: Android
context binding and Tauri setup's persisted-property handoff may arrive in either order, but both
must finish before Kotlin starts `HeadlessPeerMain`. The Java bridge class is retained as a JNI global
reference, so a Rust-created thread never depends on Android's bootstrap class loader finding an app
class. Evidence: **188** `nodera-app` tests, Android-target `cargo check`, frontend production build,
and a signed 185 MiB debug APK all pass.

### 2026-07-28 — The app stops claiming more than it knows (A-UX-1/2/3/5)

Issue #98 closed the four Task 10 honesty rows. Each was a gap between what the app *had* and what it
*said*, so each is now pinned by a check that fails when the claim drifts again.

**A-UX-1** — stale figures are marked. `isStale(link)` is true only when a picture exists (`has_data`)
and the status is neither `live` nor `polling`: without the first half an app that never connected
would call its em-dashes stale, without the second a live app would call live numbers dated. The
desktop shell renders the notice above every screen showing worker-derived figures (Overview, Worlds,
a world, Peers) and the phone shell mirrors it on both figure-bearing tabs, in the same words.
Settings and About are excluded on purpose — marking screens that describe the app is noise, not
honesty.

**A-UX-2** — `appearance.notifications` is declared `Enforcement::Never` with its reason, because this
build raises no notification at all. Reporting `Live` was exactly the defect the enforcement table
exists to prevent. Per the L-56 precedent the toggle stays and is badged rather than deleted, so a
saved preference is not silently dropped.

**A-UX-3** — verified, not rebuilt: the restart banner already restarted an app-owned worker and, in
attach mode, said `Restart it where you started it.` instead of offering to kill a process the app
does not own. What was missing was a check keeping it that way.

**A-UX-5** — `pause_reason` and `settings_fault` got callers (a node that quietly pauses looks like one
that crashed; a settings screen showing defaults must say the real file is untouched). `dashboard_world`,
`open_share_file`, and `get_unenforced_settings` were deleted along with their now-unreachable helpers,
and the `nodera://pause` event was dropped because the pushed dashboard already carries that fact. The
new gate walks all **47** registered commands against every `.ts`/`.tsx` file under `src/`, so the next
orphan fails the build rather than accumulating.

Evidence: 5 tests in `ui/tests/ux-honesty.test.mjs` plus
`appearance_notifications_is_declared_unenforced_with_a_reason`; **187** `nodera-app` Rust tests green
(net −1: two tests for deleted code removed, one direct assertion added), `cargo check --all-targets`
clean, and the production `tsc && vite build` green. All four rows moved to `LIMITATIONS.fixed.md`.

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
jscpd flags **zero** duplicated blocks in `app/`, so the register is manual candidates
only (the `lib.rs::run` god-function, `settings.rs`'s dual responsibility, the repeated wire→view
conversions in `api::model`).

### 2026-07-28 — The bytecode guard catches a real regression (issue #94)

M-5's guard is not theoretical: a Java-21 type-pattern switch in `dev.nodera.protocol.wire.MessageRouter.answerFor`
re-entered the worker's closure and `scripts/check-android-bytecode.sh` rejected it (the same
`SwitchBootstraps.typeSwitch` class of crash as M-8, latent until the first encode). The switch is
rewritten to an `instanceof` chain in the `network` category, and a new Java-level mirror
(`AndroidTypeSwitchGuardTest` in `:transport`) now catches the same slip in `./gradlew check`, not
only in the dex. The guard reports *0 invoke-custom sites, 0 SwitchBootstraps references*. M-5 stays
OPEN — the script is still the only whole-closure guard — but the in-gate test narrows the blind spot
for the module that regressed. No physical device was needed: this is a bytecode property.

### 2026-07-28 — Mobile port controls and deterministic worker boot

Issue #86 review hardening adds the missing mobile Network controls for random P2P assignment or a
validated fixed range, persisted through the same settings command that refreshes Android's private
property file. Worker startup no longer assumes `MainActivity.onCreate` runs after Rust setup: a
one-shot gate waits for both context and successful property handoff in either order, with a retained
JNI bridge class safe to invoke from the setup thread. Evidence: **188** app tests, Android-target
`cargo check`, `bun run build`, `./gradlew check`, and `scripts/android-apk.sh --debug` all green; the
signed debug APK is 185 MiB. `adb devices` is empty, so M-NET-2 remains RETIRING.

### 2026-07-28 — Android-selected P2P ports reach worker state (headless proof)

Issue #86 closes the code gap without pretending the physical exit ran. Rust derives desktop env and
Android property bytes from one encoder; Kotlin applies only `NODERA_P2P_PORT` and
`NODERA_P2P_PORT_RANGE` after Tauri reloads persisted settings; Java keeps environment precedence and
uses the property fallback only for P2P. Control remains outside the handoff, so app and worker stay
on the same endpoint. `AndroidPortPropertyTest` starts the real worker with a property-only P2P port
and reads that port back from `NODERA-STATE.self_route`; `nodera-app`'s 188 tests include two
desktop/property parity guards. `scripts/android-e2e.sh --expect-p2p-port` carries the device-side
assertion. M-NET-2 moves OPEN → RETIRING because no physical phone result was produced.

### 2026-07-28 — Reused tracker stores follows Material You

The shared tracker-store screen no longer carries desktop `--text`, `--surface`, and `--brand-*`
roles into the phone layout. `mobile/Settings.tsx` selects a mobile semantic map backed by generated
Material 3 roles, including primary controls, surface-container cards and dialogs, outline variants,
error containers, and scrim. Changing source colour now reaches the whole screen.

Evidence is host-side and bundle-level: `built tracker-store CSS resolves desktop and mobile shell
roles` verifies Vite's emitted selectors, not source strings. Task 16 deliverable 7 remains open until
both reused desktop screens receive physical touch acceptance.

### 2026-07-28 — Documentation sweep: Task 15 closed, Task 16 re-audited, M-9 filed

A full status reconciliation against the current tree.

* **Task 15 (settings + worker-verb census) → ✅ COMPLETED.** All four §Exit clauses are met and each
  is pinned by `cargo test -p nodera-app`: damaged-file preservation (`Stored { Document, Absent,
  Damaged }`), the metered-policy truth table (incl. metered-hotspot and unmetered-SIM), the
  worker-key census (`config.rs` asserts every key the app sends is one `applyConfig` recognises),
  and the no-private-address default. The §3 verb census is a living register of future work in the
  owning categories, not incomplete work here — the deliverable was the census itself.
* **Task 16 re-audited — still 🚧.** Deliverable 1 (services-list path) stays green
  (`NoderaWorker.kt:92-95`). M-NET-1 … M-NET-4 each re-verified against code: `SyncedServices.load`
  boot-only (`HeadlessPeerMain.java:103`); `envInt` getenv-only (`:630`); `restart_worker`'s
  consumer `daemon::supervise` is `#[cfg(desktop)]`; `minSdk = 24`
  (`gen/android/app/build.gradle.kts:22`) vs `--min-api 26` (`scripts/android-apk.sh:156`).
* **New limitation M-9** (build pipeline): the generated Gradle project now `compileSdk`/`targetSdk
  = 36` (`gen/android/app/build.gradle.kts:17,23`) but `scripts/android-toolchain.sh:33` installs
  only platform `android-34`. A host provisioned solely by the toolchain is one SDK-pull away from a
  failed build. Owned by [Task.12](Task.12.md).
* **Refactoring register added** ([`REFACTORING.md`](REFACTORING.md)): jscpd finds **0 clones** in
  `app/android/` (the Kotlin module is clean). jscpd does not scan shell, so the three
  `scripts/android-*.sh` were reviewed manually — they share logging helpers, version pins and the
  `NODERA_ROOT` preamble worth sourcing from one library; the top sequencing item (centralising the
  version pins) is also the elimination path for M-9.

No limitations retired this pass — every open M-* row was re-confirmed still live in code.

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

### 2026-07-27 — The services file the phone was told to read

The design names the synchronised services list as "the ONLY way the worker learns about a tracker
on Android". It had never once been read on a device: the Rust side writes
`/data/user/0/<pkg>/nodera/nodera-services.list` and `NoderaWorker.kt` pointed the worker at
`/data/user/0/<pkg>/files/nodera-services.list` — `filesDir` is one level below `app_data_dir()`,
and `config_dir()` appends `nodera/`. `api/storage.rs` already documents that exact trap for
`storage-pick.json`; the services list had no equivalent workaround. The network came up anyway
only because `network.default_trackers` also arrives over the live `NODERA-CONFIG` push.

The Kotlin side now derives the path the same way the Rust side does. Opened as
[`Task.16.md`](Task.16.md); the read-once behaviour, the `envInt` property gap, the no-op restart
button and the missing foreground service remain.

### 2026-07-27 — The phone exchanges real data with the Linux peers, and what stood in the way

`scripts/e2e-android-mesh.sh` found a defect on its first run that every earlier check had missed —
which is the entire argument for asserting on bytes rather than on status:

```
FATAL EXCEPTION: nodera-peer-state-0f0b34b4
java.lang.BootstrapMethodError: Exception from call site #6 bootstrap method
    at dev.nodera.protocol.codec.MessageCodec.encodeInto(MessageCodec.java:564)
Caused by: java.lang.ClassCastException: java.lang.Class cannot be cast to java.lang.Object
```

**Java 21 type-pattern switches are unusable on Android.** They compile to an `invokedynamic` on
`java.lang.runtime.SwitchBootstraps`, and:

* at `--min-api 26` D8 keeps the call site, because invokedynamic is native from API 26 — and ART's
  own `SwitchBootstraps` throws the **first time the call site executes**;
* below 26 D8 tries to desugar, cannot find `SwitchBootstraps` (Android does not ship it and
  `desugar_jdk_libs` does not provide it), and silently replaces the instruction with a stub that
  throws `Instruction is unrepresentable in DEX V35: invoke-dynamic`.

Both are **latent**: the worker boots, announces, serves state and answers the control socket, then
dies on its first encoded message. On a phone that takes the whole app with it, because the worker
shares the process. It is exactly the failure mode that a "did it connect" test cannot see.

Ten methods across engine, peer, transport and worker were rewritten as `instanceof` chains. The
transport's wire contract is unchanged, proven by the golden fixtures round-tripping byte-exactly in
both implementations. `scripts/check-android-bytecode.sh` now fails the build if an
`invoke-custom` site ever reappears in the dexed closure.

Result, both directions, from each node's own counters:

```
phone       received=21534  sent=18429  peers=1
linux peer  received=4515   sent=21908  peers=1
            peer 0f0b34b4-208e-4705-8ec1-45fa2b8681aa  10.0.0.104:25620
```

**A correction worth recording.** Earlier notes in this file said the `SwitchBootstraps` story was a
misdiagnosis and that only the D8 version was real. That was an overcorrection: the D8 version was
one real blocker, and ART's missing `SwitchBootstraps` is a second, independent one. The first hid
the second, because nothing had executed a type-switch until the mesh join.

### 2026-07-27 — A suite that asks whether the phone actually receives anything

`scripts/e2e-android-mesh.sh` puts the phone in a mesh with the Linux peers and asserts the one
thing that cannot be inferred from a screen: the **phone's own** `total_received_bytes` moving after
it joins, plus a non-empty membership view. Both directions are checked separately, because a NAT
can let traffic flow one way while "connected" hides it.

Everything about the phone is observed through `adb` — its control socket for state, logcat for the
worker's own account of itself — so no assertion depends on what the app draws.

Two supporting changes:

* The launcher gained `NODERA_SERVICE_BIND_ADDR` / `NODERA_SERVICE_ADVERTISE_ADDR`. The tracker and
  rendezvous configs hard-coded `127.0.0.1`, which no off-box peer can open a socket to. Defaults are
  unchanged, so every existing suite is byte-identical.
* `run-tests.sh` grew an `OPTIONAL_SUITES` list. This suite needs a physical phone, and a default
  batch that fails on every machine without one would train people to ignore the batch.

Guards verified against the live device: an unreachable serial and a USB-only serial each fail with
their own reason, and the phone-side helpers read `node_id`, `self_route` (`10.0.0.104:25620` — a LAN
route, not loopback), `total_received_bytes` and the tracker list straight from `NODERA-STATE`.

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

Four surfaces landed, all on top of [worker 7](../peer/Task.7.md).

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

**The API.** `app/src/api/` is now the whole data path: `model` (typed, `Option`
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
it on every restart. The fix is [worker 6](../peer/Task.6.md); the app's part was to stop asking a
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

### 2026-07-26 — The phone runs the real Java worker, not a smaller substitute

The worker was believed unportable to Android, and the reasons given were wrong in a way worth
recording, because both were stated with confidence:

* *"Java 21 type-pattern switches compile to `SwitchBootstraps.typeSwitch`, which ART cannot run and
  D8 cannot desugar."* The obstacle was the **D8 version**. Build-tools 34 ships R8 8.2, which
  refuses Java 21 class files outright — `Unsupported class file major version 65` — and that error
  was read as a language-support problem. D8 from build-tools 35 dexes the entire closure (core,
  transport, storage, engine, peer, worker, BouncyCastle, fastutil, caffeine, rocksdbjni, zstd-jni)
  without complaint, and ART runs it.
* *"rocksdbjni and zstd-jni ship desktop-only natives."* They do. They are never loaded on the path
  the worker takes: the dex payload strips every foreign `.so` and the worker still reaches
  `online`.

One genuine incompatibility existed and is now fixed properly:
`Thread.ofVirtual()` **exists** on ART and throws when started
(`NullPointerException … ThreadGroup.add`), so `dev.nodera.core.concurrent.Threads` probes the
capability by starting one and seeing whether it runs, rather than testing a version.

Evidence, from the device:

```
[main] INFO NoderaWorker - Nodera peer worker 0.1.0 online — node NodeId[5b33c37b-…]
       listening 10.0.0.104:25620, control 127.0.0.1:25610, 1 tracker(s) / 1 rendezvous
```

### 2026-07-26 — Three defects found only by running it on a phone

* **`std::fs::read("/dev/urandom")`** in `peer/identity.rs`. `fs::read` reads to EOF and
  `/dev/urandom` has none, so creating the device's identity allocated ~300 MB/s until Android killed
  the process — a crash a few seconds after launch, with the peer never announcing. Invisible on
  desktop, where an identity file already existed. Fixed with `read_exact(&mut [u8; 32])`, pinned by
  `a_fresh_seed_is_bounded_and_unpredictable`.
* **`dirs_config()` had no Android arm**, so every path resolved to `"."` = `/`, which is
  unwritable. Settings could not be saved, which made the first-run flow reappear on every launch and
  the storage choice never stick. The data directory is now injected from `app_data_dir()` and the
  settings handle re-read once it is known.
* **R8 stripped the JNI entry points.** `NoderaStorage.pick` is called only from Rust, which R8
  cannot see, so it was renamed and the folder picker failed with
  `NoSuchMethodError: no static method …pick()V`. Keep rules are injected by the build script.

### 2026-07-26 — What Android will and will not allow, said out loud

The folder picker is the system's own (`ACTION_OPEN_DOCUMENT_TREE`), the grant is persisted, and the
chosen tree is mapped back to a filesystem path **and then written to**. That probe is the feature:
a SAF grant does not give a `java.io.File`-based worker access to shared storage on Android 11+, so
the app reports the refusal in plain words instead of saving a setting that silently stores nothing.
On the test device `/storage/emulated/0/Documents` probed writable.

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
