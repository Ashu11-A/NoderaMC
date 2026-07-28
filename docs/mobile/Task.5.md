# Mobile Task 5 — The phone reaches the network it was told to

<!-- AI-AGENT-INSTRUCTION: The rule this task exists to preserve: on Android, `Context.filesDir` is
     NOT `app_data_dir()`. filesDir is `/data/user/0/<pkg>/files`; Tauri's app_data_dir() is
     `/data/user/0/<pkg>`, and `settings::config_dir()` joins `nodera` to it. Any path shared
     between the Kotlin side and the Rust side must be derived from the same one of those, and a
     comment claiming they are equal is wrong. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** mobile · **Owns:** M-NET-1 … M-NET-4 · **Last audit:** 2026-07-27
**Depends on:** [mobile 3](Task.3.md), [app 9](../app/Task.9.md), [worker 2](../worker/Task.2.md)
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

Landed so far: the Kotlin side now derives the path the same way the Rust side does.

## Dependencies

- [app 9](../app/Task.9.md) owns the tracker stores that populate the services list.
- [worker 2](../worker/Task.2.md) owns which config keys apply live vs at spawn.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | The worker reads the services list the app writes | ✅ |
| 2 | The services list is re-read without a process restart | ⬜ |
| 3 | `envInt` honours system properties, so Android can set its ports | ⬜ |
| 4 | Restart-scoped settings either apply on Android or are hidden there | ⬜ |
| 5 | A foreground service, so the node survives the app leaving the screen | ⬜ |
| 6 | `minSdk` and the dex `--min-api` agree | ⬜ |
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
`#[cfg(desktop)]`. On a phone the worker is a daemon thread in the Activity's process, so
`network.rendezvous_endpoints`, `network.p2p_port` and `network.port_range` — all in
`RESTART_REQUIRED_KEYS` — can never be applied. The honest options are deliverable 3 + 4: make the
values reachable live where possible, and stop offering the ones that are not.

`envInt` is a second instance of the same class of bug as the path: it reads `System.getenv` only,
while every other reader falls back to `System.getProperty`, and Android configures the worker
exclusively through properties.

### Not a blocker, despite appearances

Sockets are fine. `INTERNET` and `ACCESS_NETWORK_STATE` are present;
`usesCleartextTraffic=false` in release governs HTTP stacks, not the hand-framed TCP the P2P lane
uses. The real lifetime constraint is the missing foreground service (deliverable 5), which is
tracked as M-2 and is why a phone vanishes mid-transfer when Android reclaims the app.

## Files

| Path | Role |
|---|---|
| `rust/nodera-app/android/kotlin/NoderaWorker.kt` | services-list path, worker boot |
| `rust/nodera-app/src/settings.rs` | `config_dir`, `sync_file_path`, `ANDROID_DATA_DIR` |
| `java/worker/src/main/java/dev/nodera/headless/HeadlessPeerMain.java` | `SyncedServices.load`, `envInt` |
| `rust/nodera-app/gen/android/app/src/main/AndroidManifest.xml` | permissions, deep link |
| `scripts/android-apk.sh` | dex min-api, manifest injection, kotlin copy |

## Testing

| Test | Proves |
|---|---|
| `scripts/android-e2e.sh` with a LAN tracker and **no** `NODERA_DEFAULT_TRACKERS` baked in | deliverable 1 — the phone can only reach the tracker through the services file |
| `adb shell run-as dev.nodera.app ls nodera/` | the file is where both sides agree |
| A store added in the app, then `logcat` showing the endpoint in use | deliverable 2 |

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
| M-NET-2 | `NODERA_CONTROL_PORT` / `NODERA_P2P_PORT` cannot be set on Android (`envInt` ignores properties) | deliverable 3 |
| M-NET-3 | `restart_worker` is a silent no-op on Android | deliverable 4 |
| M-NET-4 | `minSdk = 24` but the worker is dexed at `--min-api 26` | deliverable 6 |
