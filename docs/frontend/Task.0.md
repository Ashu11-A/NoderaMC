# Frontend — Category Charter

<!-- AI-AGENT-INSTRUCTION: This category owns EVERY user-facing surface: the Tauri desktop launcher,
     the native Android companion, and the public website. None of them is a peer. They hold no
     signing keys and serve nothing to the network — if you find yourself adding peer logic, network
     protocol, or consensus code here, stop; it belongs in the worker or the Rust services. Two rules
     carried in from the categories this one merges: (1) the app must never kill a worker it did not
     spawn; (2) if the phone ever stops running the real Java worker, this file must say so before any
     other document is updated. Keep control.rs a strict mirror of the worker's ControlProtocol. Keep
     the task index in agreement with ../ROADMAP.md §2. -->

**Category:** `frontend` · **Status:** 🚧 IN PROGRESS (10 of 20 tasks completed) ·
**Last audit:** 2026-08-05

---

## 1. What this category is

Everything a person looks at. Three surfaces, one node underneath:

| Surface | What it is | Where it lives |
|---|---|---|
| **Desktop launcher** | A Tauri shell around a React game launcher. Presses Play, starts Minecraft in a world, sits in the tray, supervises the bundled headless worker | `app/`, `app/ui/`, `library/rust/nodera-core` |
| **Android companion** | A native Jetpack Compose Material 3 activity hosting the **real Java peer worker** in-process behind a foreground service | `app/android/kotlin/`, `scripts/android-*.sh` |
| **Website** | The public site at [noderamc.org](https://noderamc.org) — the project's front door and the `https` hop the tracker-store deep link needs | `web/` |

Because the mod refuses to start without the worker, the desktop app is **the thing every player
actually runs**. It must therefore be boring, small, and reliable.

**Why one category.** These three were the **app** and **mobile** categories, and the site would
have become
a third folder. They share a Rust core, a settings document, a control-protocol mirror, a design
language and a release; splitting them meant the same decision was recorded in two registers and
drifted between them. One folder, one ledger, one limitation register.

## 2. What it is not

None of these surfaces is **a peer**. All network behaviour lives in the worker (Java) and the Rust
service binaries. They hold no signing keys, participate in no committee, and serve nothing to the
network. The desktop app supervises a process, coordinates launch/tunnel lifetime, and renders worker
state; the phone hosts that process; the website is static files.

This boundary is what keeps the frontend cheap to change: a UI mistake cannot corrupt a world, and
the dashboard's data path is already testable without any of these surfaces, because the worker's
`STATE` verb is asserted headlessly on the Java gate.

## 3. Architecture

```
desktop React launcher ─┐
                        ├─ nodera-core: worker link · settings · stores · launch coordinator
Android Compose + JNI ──┘                         │
                                                  │ 127.0.0.1:25610
Tauri shell: window · tray · autostart · supervisor ── Java worker

web/ (static, no runtime) ── noderamc.org ── nodera://tracker-store deep link
```

**Attach mode matters.** The supervisor can either spawn the bundled worker or **attach** to one that
was started externally — which is how the development script runs it. Quitting the app must leave an
externally started worker untouched: the app must never kill a process it did not spawn.

**On Android the worker is a thread in this process.** Android does not let an app spawn a VM, so
`NoderaWorker.kt` stages the dexed worker out of the APK's assets and calls `main` on a background
thread; `NoderaWorkerService` (a `dataSync` foreground service) owns it, so the node outlives the
screen going off.

## 4. Rules

1. **No substitute peer.** If the worker cannot run, the surface reports the node as offline. It does
   not quietly fall back to something smaller and keep calling itself a peer. (An earlier Android
   design did exactly that — a Rust discovery-plane peer — and it was removed when the worker was
   made to run.)
2. **Nothing invented on screen.** Every number comes from the worker's own state. Unknown renders as
   `—`, never as `0`.
3. **The OS decides, and is quoted.** Battery restrictions, storage permissions, folder access and
   login items are the platform's to grant. The app detects, explains, and links to the setting; it
   never asserts an outcome it did not verify with a real write or a real read.
4. **Android is native Compose.** Tauri owns Rust/NDK packaging, not the runtime view. Desktop React
   breakpoints never decide which Android capabilities exist.
5. **Material You means system dynamic colour.** Android 12+ reads the wallpaper palette through
   Material 3; older releases use one explicit fallback scheme.
6. **A setting the node cannot honour is badged, not faked.** The UI distinguishes "not enforced yet"
   from "not supported", using the worker's own reason.

## 5. Dependencies

**Depends on:** [`worker/Task.1.md`](../peer/Task.1.md) (the process it supervises),
[`worker/Task.2.md`](../peer/Task.2.md) (the control endpoint it reads).

**Consumed by:** players (it is the install target) and
[`minecraft/Task.7.md`](../minecraft/Task.7.md), whose gate assumes the app keeps the worker alive.

## 6. Task index

### Desktop launcher and shared core

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Tauri scaffold + worker supervisor | ✅ COMPLETED |
| [2](Task.2.md) | Live metrics dashboard | ✅ COMPLETED |
| [3](Task.3.md) | Per-OS packaging + CI | 🚧 IN PROGRESS |
| [4](Task.4.md) | End-to-end acceptance + cross-machine continuity | ⏳ BLOCKED |
| [5](Task.5.md) | Telemetry consent: first-run modal + Privacy screen | 🚧 IN PROGRESS |
| [6](Task.6.md) | The dashboard API and the live link | ✅ COMPLETED |
| [7](Task.7.md) | The client becomes the way in | ✅ COMPLETED |
| [8](Task.8.md) | One subject per screen | ✅ COMPLETED |
| [9](Task.9.md) | Tracker stores | ✅ DONE |
| [10](Task.10.md) | Practical screens, honest numbers | 🚧 IN PROGRESS |
| [11](Task.11.md) | A launcher, not a dashboard | 🚧 IN PROGRESS |

### Android

| Task | Title | Status |
|---|---|---|
| [12](Task.12.md) | The Android build, and the worker inside it | ✅ COMPLETED |
| [13](Task.13.md) | The interface: Material You, and what a phone may be asked | ✅ COMPLETED |
| [14](Task.14.md) | The phone in the mesh, and how it is proven | ✅ COMPLETED |
| [15](Task.15.md) | The settings the app can keep, and the verbs it never asks for | ✅ COMPLETED |
| [16](Task.16.md) | The phone reaches the network it was told to | 🚧 IN PROGRESS |

### The redesign, the site, and shipping them

| Task | Title | Status |
|---|---|---|
| [17](Task.17.md) | A launcher, redesigned | 🚧 IN PROGRESS |
| [18](Task.18.md) | The website | 🚧 IN PROGRESS |
| [19](Task.19.md) | One codebase, three exports | 🚧 IN PROGRESS |
| [20](Task.20.md) | Shipping it | 🚧 IN PROGRESS |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) ·
refactoring register: [`REFACTORING.md`](REFACTORING.md).

**Task numbering after the 2026-08-05 merge.** The app's `Task.1…11` kept their numbers; mobile's
`Task.1…5` became **12…16** (+11). A reference to "mobile task 3" in an old commit
message or issue is [Task 14](Task.14.md).

## 7. Files

| Path | Contents |
|---|---|
| `app/Cargo.toml` | Tauri, autostart plugin, tokio, serde — **workspace-excluded** |
| `app/tauri.conf.json` | Tray, autostart, single-instance, window and bundle config |
| `app/src/` | Thin Tauri command, tray, window and platform shell |
| `library/rust/nodera-core/` | Shared worker link, settings, stores, telemetry and launch coordinator |
| `app/ui/` | React + Vite desktop launcher |
| `app/android/kotlin/` | Native Compose Material 3 Android shell |
| `scripts/android-{toolchain,apk,e2e}.sh` | Provision, build and prove the APK |
| `web/` | `index.html`, `add-store.html`, `noderamc.caddy` — the static site |
| `scripts/deploy-site.sh` | Publishes `web/` to the VPS (superseded by [task 20](Task.20.md)) |

Package architecture: [`app/README.md`](../../app/README.md).

## 8. Conventions specific to this category

- **The Tauri crate is workspace-excluded.** Tauri's native webkit dependencies would break the
  headless `cargo test` gate, so it builds separately (`cd app && cargo test`). **Do not read the
  green workspace Rust gate as covering that crate** — that is exactly what [task 3](Task.3.md)'s CI
  job fixes. `library/rust/nodera-core` **is** in the root workspace and **is** covered.
- **`control.rs` is a strict mirror** of the worker's `ControlProtocol`: verbs, version, framing.
  Change all three mirrors in one commit and let the mod's gate classify skew.
- **The worker must work without the app** (development runs it bare), and **the app must degrade
  gracefully when the worker is down**: the tray shows offline, the dashboard shows the daemon as
  down, and there are no crash loops.
- **No logic in the UI worth testing beyond parsing.** If a panel needs a rule, the rule belongs in
  the worker where it can be asserted headlessly.
- **Per-platform behaviour gets per-platform acceptance.** One OS passing is not evidence about
  another, and a host build is not evidence about a phone.

## 9. Boundaries

* The Android worker's lifetime is the foreground service's lifetime; a foreground service extends a
  process's life rather than guaranteeing it.
* The APK targets one ABI at a time (`aarch64` by default).
* The LAN tunnel lane is present in the worker but untested on Android; a phone is not where an
  unmodified Minecraft runs.
* The website is **static**. It has no backend, makes no external requests, and must never become a
  service the network depends on — see [task 18](Task.18.md).
