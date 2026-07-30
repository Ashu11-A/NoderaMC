# Mobile Task 5 — The phone reaches the network it was told to

<!-- AI-AGENT-INSTRUCTION: The rule this task exists to preserve: on Android, `Context.filesDir` is
     NOT `app_data_dir()`. filesDir is `/data/user/0/<pkg>/files`; Tauri's app_data_dir() is
     `/data/user/0/<pkg>`, and `settings::config_dir()` joins `nodera` to it. Any path shared
     between the Kotlin side and the Rust side must be derived from the same one of those, and a
     comment claiming they are equal is wrong. Keep this header's status accurate.
     2026-07-28 audit: deliverable 1 is green (path fix landed in NoderaWorker.kt). Deliverable 3's
     code and headless proof are green: the mobile Network screen persists P2P settings, Rust writes
     an allowlisted property file, a two-signal gate starts Kotlin only after context and setup are
     ready, and AndroidPortPropertyTest observes the selected port in self_route.
     M-NET-2 stays RETIRING until that exact assertion passes on a physical phone. Deliverables 2,
     4-7 remain OPEN: SyncedServices.load is boot-only; restart_worker has no Android consumer;
     minSdk 24 still disagrees with --min-api 26. -->

**Status:** 🚧 IN PROGRESS
**Category:** mobile · **Owns:** M-NET-1 … M-NET-4 · **Last audit:** 2026-07-28
**Depends on:** [mobile 3](Task.3.md), [app 9](../app/Task.9.md), [worker 2](../peer/Task.2.md)
**Consumed by:** —

---

## Goal

A phone joins the Nodera network through the services it was actually configured with — the trackers
and relays the user added, delivered by the channel the design nominates — and its network settings
can be changed without reinstalling the app.

## Status detail

Opened 2026-07-27. The audit's verdict: **the phone is a real node** — real dex (5 multidex files,
no `.class` left), the real `HeadlessPeerMain` on ART, a real signed identity, real sockets, and real
writes under `<filesDir>/worker/.nodera/` (`worlds.dat`, `world-keys/`, `worker-identity.bin`). The
`~/.nodera` a desktop user looks for does not exist on a phone by design: `user.home` is redirected
at `NoderaWorker.kt`.

But the channel the design calls "the ONLY way the worker learns about a tracker on Android" had
**never once been read on a device**:

| Side | Path |
|---|---|
| Rust writes | `/data/user/0/<pkg>/nodera/nodera-services.list` |
| Kotlin told the worker to read | `/data/user/0/<pkg>/files/nodera-services.list` |

Two divergences at once — `filesDir` is one level below `app_data_dir()`, and the Rust side appends
`nodera/`. The comment in `NoderaWorker.kt` asserted the two were the same directory; `api/storage.rs`
already documents that they are not, and works around it for `storage-pick.json`. The network came up
regardless only because `network.default_trackers` also arrives over `NODERA-CONFIG`, which is live.

Landed so far: the Kotlin side now derives the path the same way the Rust side does
(`NoderaWorker.kt:92-95` sets `NODERA_SERVICES_FILE` to `dataDir/nodera/nodera-services.list`).
The shared tracker-store screen also selects a Material 3 semantic-role mapping on mobile, so its
controls, cards, errors, inputs, and dialogs follow the generated source colour instead of forcing
desktop palette variables through the mobile shell. This advances deliverable 7; physical touch
acceptance of both reused screens remains open.

**2026-07-28 re-audit.** Deliverable 1 stays green; deliverable 3 is headless-green with its physical
exit pending; deliverables 2 and 4–7 remain open. The network limitations are:

* **D2 / M-NET-1** — `SyncedServices.load` still runs once at `HeadlessPeerMain.java:103`; no
  re-read path exists.
* **D3 / M-NET-2 — RETIRING.** The app now serialises the same validated P2P port pair used by the
  desktop spawn environment into `dataDir/nodera/nodera-worker.properties`; Kotlin allowlists only
  `NODERA_P2P_PORT` and `NODERA_P2P_PORT_RANGE`, and the worker reads the integer through the existing
  environment-first/property-second setting seam. Control deliberately stays environment-only:
  Android's Rust client and Java worker therefore remain together on `25610`, while a desktop
  `NODERA_CONTROL_PORT` still reaches both through their shared environment. `AndroidPortPropertyTest`
  starts a real worker JVM with only `-DNODERA_P2P_PORT=...` and observes that exact value in
  `NODERA-STATE.self_route`. Mobile Settings now exposes the random-port toggle and validated range.
  Rust tests pin identical desktop/property encoding, prohibit a control key, replace changed
  property files, and prove either Activity/setup order starts once. The physical-phone exit has not
  run, so the row is not retired.
* **D4 / M-NET-3** — `restart_worker`'s only consumer (`daemon::supervise`) is still `#[cfg(desktop)]`.
* **D6 / M-NET-4** — `gen/android/app/build.gradle.kts:22` is still `minSdk = 24` while
  `scripts/android-apk.sh:156` dexes at `--min-api 26`.

## Dependencies

- [app 9](../app/Task.9.md) owns the tracker stores that populate the services list.
- [worker 2](../peer/Task.2.md) owns which config keys apply live vs at spawn.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | The worker reads the services list the app writes | ✅ |
| 2 | The services list is re-read without a process restart | ⬜ (M-NET-1) |
| 3 | Android applies the app's P2P port through a system property without moving control | 🚧 headless green; physical exit pending (M-NET-2) |
| 4 | Restart-scoped settings either apply on Android or are hidden there | ⬜ (M-NET-3) |
| 5 | A foreground service, so the node survives the app leaving the screen | ⬜ (M-2) |
| 6 | `minSdk` and the dex `--min-api` agree | ⬜ (M-NET-4) |
| 7 | The two desktop screens reused on mobile are usable by touch | ⬜ |

## Design

### Why one wrong path was invisible for so long

Nothing fails when `SyncedServices.load` finds no file: it falls back to `127.0.0.1:25600`, which on
a handset is the handset. The node announces to itself, finds nobody, and looks like a network
problem. Meanwhile the *other* channel — `network.default_trackers` pushed over the control socket on
every link connect — quietly did the job, so the feature appeared to work and its own tests
(desktop-only) passed. This is the shape to watch for: a config key with a writer and no reader, in a
system where a second path happens to cover for it.

### Why the restart button does nothing here

`restart_worker` notifies a `RestartSignal` whose only consumer, `daemon::supervise`, is inside
`#[cfg(desktop)]`. On a phone the worker is a daemon thread in the Activity's process. The P2P range
now applies on a full app-process stop and relaunch because Rust rewrites the handoff on save and at
startup; the in-app Restart button still cannot cycle that thread. Rendezvous has the same process
restart requirement. Deliverable 4 must either implement an honest Android restart or stop offering
that button there.

### Why worker boot has two signals

`MainActivity.onCreate` and Tauri's Rust setup hook are independent lifecycle paths; returning from
`super.onCreate` does not prove persisted settings were loaded and written. Rust now gates startup on
both the JNI-bound application context and setup's successful property write, then signals only as
the setup hook's final action. The bridge class captured on the Java thread is retained globally, so
the setup-first order does not ask Android's bootstrap class loader to resolve an app class from a
Rust-created thread.

The integer bug was the same class as the path bug: the P2P integer used `System.getenv` only while
the range already used the property-aware reader. The fix is deliberately asymmetric. P2P is an
allowlisted Android property; control is not, because the Rust control client has no Java-property
channel and configuring only one end would strand the app from its worker.

### Not a blocker, despite appearances

Sockets are fine. `INTERNET` and `ACCESS_NETWORK_STATE` are present;
`usesCleartextTraffic=false` in release governs HTTP stacks, not the hand-framed TCP the P2P lane
uses. The real lifetime constraint is the missing foreground service (deliverable 5), which is
tracked as M-2 and is why a phone vanishes mid-transfer when Android reclaims the app.

## Files

| Path | Role |
|---|---|
| `app/android/kotlin/NoderaWorker.kt` | services-list path, P2P-property allowlist, worker boot |
| `peer/src/main/java/dev/nodera/headless/HeadlessPeerMain.java:103` | `SyncedServices.load` (boot-only — M-NET-1) |
| `peer/src/main/java/dev/nodera/headless/HeadlessPeerMain.java` | environment-first P2P integer/property fallback; environment-only control integer |
| `peer/src/test/java/dev/nodera/headless/AndroidPortPropertyTest.java` | real worker process reports the property-selected port in `self_route` |
| `app/src/daemon.rs` | one port encoder for desktop env and Android property handoff |
| `app/src/android/worker.rs` | one-shot context/setup startup gate |
| `app/android/kotlin/MainActivity.kt` | binds context; never infers setup completion |
| `app/ui/src/mobile/Settings.tsx` | random-port and validated fixed-range controls |
| `app/src/settings.rs` | `config_dir`, `sync_file_path`, `ANDROID_DATA_DIR` |
| `app/src/daemon.rs` | `supervise` — the desktop-only restart consumer (M-NET-3) |
| `app/gen/android/app/build.gradle.kts:22` | `minSdk = 24` (M-NET-4) |
| `scripts/android-apk.sh:156` | `--min-api 26` dex floor (M-NET-4) |
| `app/ui/src/TrackerStores.tsx` | shared desktop/mobile semantic roles |
| `app/ui/src/mobile/Settings.tsx` | selects the Material 3 role mapping |

## Testing

| Test | Proves |
|---|---|
| `scripts/android-e2e.sh` with a LAN tracker and **no** `NODERA_DEFAULT_TRACKERS` baked in | deliverable 1 — the phone can only reach the tracker through the services file |
| `adb shell run-as dev.nodera.app ls nodera/` | the file is where both sides agree |
| A store added in the app, then `logcat` showing the endpoint in use | deliverable 2 (M-NET-1 — not yet green) |
| `built tracker-store CSS resolves desktop and mobile shell roles` | generated Material 3 roles reach the reused tracker-store controls, cards, inputs, and dialogs |
| `AndroidPortPropertyTest.p2pSystemPropertyAppearsInWorkerStateSelfRoute` | property-only worker boot selects and reports the requested P2P port |
| `daemon::tests::{android_properties_match_the_fixed_port_settings_sent_on_desktop,android_properties_cannot_move_the_control_endpoint}` | desktop env compatibility and control-port agreement |
| `daemon::tests::first_launch_and_changed_settings_replace_the_android_property_handoff` | first-launch write and later setting changes replace stale bytes |
| `android::peer::tests::*first_launch*` | Activity-first and setup-first orders wait for both signals and start once |
| `scripts/android-apk.sh --debug` | frontend, Android Rust target, Kotlin bridge and APK packaging compile together |
| `scripts/android-e2e.sh --expect-p2p-port PORT` after selecting a one-port range and fully relaunching | exact M-NET-2 physical exit (not yet run) |

## Acceptance criteria

- [x] The path the worker is told to read is the path the app writes.
- [ ] Adding a tracker store on a phone changes which tracker the worker dials, without restarting
      the app.
- [ ] No setting is offered on Android that Android cannot apply.
- [ ] The node stays announced with the app in the background.

## Limitations

| Id | Statement | Exit test |
|---|---|---|
| M-NET-1 | The services list is read once at worker boot | deliverable 2 |
| M-NET-2 | Android P2P property path is headless-green; physical selected-port proof remains | deliverable 3 |
| M-NET-3 | `restart_worker` is a silent no-op on Android | deliverable 4 |
| M-NET-4 | `minSdk = 24` but the worker is dexed at `--min-api 26` | deliverable 6 |
