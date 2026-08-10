# Frontend — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the frontend category — desktop launcher, Android companion
     and website. "Permanent" is banned. Every §B row has an owning task and an EXIT TEST. Never
     delete a row — move it to LIMITATIONS.fixed.md with its evidence. L-56 is the register's own
     precedent for the rule "badge honestly rather than delete": removing an unsupportable setting
     would silently drop values users already saved AND hide that the limitation is known. Rows
     beginning `M-` came from the former mobile register on 2026-08-05; their statuses were
     carried across unchanged. -->

**Category:** frontend · **Last audit:** 2026-08-10 · Open or retiring rows: **12**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-9 | Tray, autostart, installer, and permission behaviour differ per operating system and fail quietly on all of them | Per-platform acceptance with no extrapolation from one OS to another; installers produced and verified per target; the app degrades visibly (tray offline, daemon down) rather than silently | [3](Task.3.md), [4](Task.4.md) |

---

## §B — Staged capabilities

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-47 | There is no automated **installer-based, cross-machine** acceptance. The halves that exist are green in CI: a companion job builds the app end to end, runs the gate both ways (worker present ⇒ Minecraft starts; absent ⇒ an actionable abort), and proves hosted-world survival with a real daemon and a SIGKILLed stand-in game process. What is missing is the sentence a player would actually say — install the app, host a world, close Minecraft, and have it still be listed **and joinable from a second, separately networked side**. *2026-08-10:* the installer half and the separately-networked half both landed — `scripts/install-app.sh` makes the installed `.deb` the job's entry point, and `CrossMachineScenario` puts the joining peer in a container on a second Docker network whose only path to this machine is a router the run then stops, so a loopback shortcut cannot survive the run. What is left is the exit RUN itself: `e2e-live / cross-machine` is nightly-and-dispatch, and the reference box could not host it (rootless Docker has no host-reachable bridge gateway, and the machine had 2 GiB free against the scenario's 4). The joining side is also still a real *peer* rather than a real *client* — Task 4 deliverable 5 | [4](Task.4.md) | A CI job installs the app, verifies the gate both ways, hosts a world, closes Minecraft, and joins that world from a second machine or separately networked instance | RETIRING — installer entry point and the routed second network landed with their negative twin; the live run is the exit |
| L-56 | **Two connection settings can never be honoured as specified.** Settings → Network exposes a per-world connection cap and an unlimited-connections-only filter. Neither is implementable against the architecture as it stands, and both are **kept in the UI on purpose**, permanently badged with the worker's own reason rather than deleted — removing them would silently drop settings users already saved and would hide that the limitation is known. *Per-world caps:* the socket transport has no world dimension at all — a socket is not owned by a world — so making it real needs either a world dimension on the transport or a world→manifest accounting index, i.e. an architectural change rather than a settings change. *Unlimited-connections-only:* no peer advertises a connection cap anywhere on the wire, so there is nothing to filter peers on; it would need a new field in the frozen membership family, mirrored in the Rust codec. The worker returns both as `rejected` with these reasons and the app renders a distinct muted "not supported" badge — deliberately **not** the amber "not enforced yet", which would imply the feature is coming | [3](Task.3.md) | Either the transport gains per-world attribution and a peer-advertised cap field, or both controls are removed from the UI **with their saved values migrated** | OPEN |
| L-91 | **The direct launch route cannot sign in.** Play assembles a full Minecraft command line — version-chain resolution through `inheritsFrom`, classpath ordering, natives extraction, the complete placeholder table, a probed Java runtime, `--quickPlayMultiplayer` — but starting the game needs a complete Microsoft account token/profile. A client-id environment variable alone is explicitly insufficient and never enables Direct. No approved credential/token flow exists in this repository, so planning chooses Prism, `servers.dat`, or address before opening a tunnel rather than failing during sign-in | [11](Task.11.md) | An approved Microsoft flow yields a complete online account; Direct spawns the JVM and opens the world with no other launcher installed | OPEN |
| L-92 | **The offline account is test-only.** `launch::auth::Account::offline` fills `${auth_*}` placeholders so command assembly can be exercised without login, but production planning never treats it as usable authentication. A LAN-opened vanilla world authenticates joiners against Mojang and would refuse it | [11](Task.11.md) | Either L-91 supplies a real account and offline remains unreachable outside tests, or offline-mode targets are supported end to end and documented | OPEN |
| L-93 | **The `servers.dat` route is not auto-connect.** For a player using the official launcher, Play writes the tunnel address into their Multiplayer list and opens the launcher: one click from the world rather than none. The official launcher has no command-line way to join a server, so this is the ceiling for that launcher, and the interface says so in those words rather than implying the game will open in the world. Players with Prism or MultiMC get the real thing — `--server` becomes `--quickPlayMultiplayer` and the game lands in the world | [11](Task.11.md) | Either the official launcher gains a join argument, or L-91 is resolved and these players get the direct route | OPEN |
| L-94 | **The rebuilt native Android settings need complete physical touch acceptance.** Android 15 proves startup, bar navigation, Peers identity/self-test, Privacy rendering, and warm-start deep-link routing. Host gates now cover the unified tracker/store page, four-step onboarding, typed field mutations, verified storage choices and visible battery optimization, but those final interaction/result paths have not run on the reconnected phone | [11](Task.11.md) | Network and Storage are edited on a phone; a tracker-store deep link is previewed, added and removed; process restart preserves values; the hardened 130-second E2E passes | RETIRING — host build and earlier physical smoke green; mutation/restart plus hardened E2E pending |
| L-95 | A custom appearance applies to the **desktop window only**. The phone renders in Compose against the system palette and cannot read CSS, so a theme authored here has no effect there. The value is stored, never dropped, and the screen says so on its face rather than implying a preference that travels | [17](Task.17.md) | The Android companion reads `appearance.themes` and renders at least the accent and surface roles from the selected appearance, or the register records that it structurally cannot | OPEN |
| L-96 | A stored appearance whose CSS the parser rejects is **dropped silently at startup**. Nothing is overwritten and the base scheme renders correctly, but the reason is only shown when the Theme screen is opened — so a theme that stopped working after a webview upgrade looks like a theme that was never saved | [17](Task.17.md) | Applying a stored appearance whose CSS fails to parse raises the same fault channel the settings document uses, so the reason is visible without opening the editor | OPEN |
| M-1 | **An arbitrary shared-storage folder may be picked but unusable.** Android 11+ withholds raw `File` access to shared storage regardless of the SAF grant, and the worker writes with `java.io.File`. The app detects and reports this (`NoderaStorage.kt:171-191` probes with a real write); it cannot remove it without a storage layer that speaks `content://`. | [13](Task.13.md) | A folder outside app-specific storage is picked and the worker writes a world archive into it | OPEN |
| M-3 | **One ABI per APK.** The build targets `aarch64`; `--target` switches it, but nothing produces a universal APK or an App Bundle. | [12](Task.12.md) | One artifact installs on arm64 and armv7 devices | OPEN |
| M-4 | **The LAN tunnel lane is untested on Android.** The worker joins the multicast group on the phone (`Watching 224.0.2.60:4445`), but no test drives a real game through it from a handset. | [16](Task.16.md) | An unmodified Minecraft client reaches a LAN world through a phone-hosted tunnel | OPEN |
| M-NET-2 | **The app-to-worker P2P port path has no physical-phone exit evidence yet.** Mobile Settings exposes random/fixed P2P ports; Rust writes the validated values to `nodera-worker.properties`; a context/setup gate starts Kotlin only after the handoff; Java reads `NODERA_P2P_PORT` environment-first/property-second. Control is intentionally excluded so Android's Rust client and Java worker agree on `25610`. `AndroidPortPropertyTest` proves a real worker reports the requested property port in `NODERA-STATE.self_route`; five app tests cover parity, control isolation, replacement and both startup orders. A signed debug APK builds, but no physical phone selected a one-port range and passed the exact state assertion. | [16](Task.16.md) | The phone binds a P2P port chosen in settings | RETIRING — headless proof green; physical state proof pending |

**On the `M-` rows' owners.** The former mobile register had no Owner column, which is why three of
these rows had no named owner to carry across. Two are stated by the task files themselves and are
reproduced: [Task 16](Task.16.md) declares `**Owns:** M-NET-1 … M-NET-4`, and [Task 12](Task.12.md)
owns `scripts/android-apk.sh`, which is where `--target` and the one-ABI decision live (M-3). **M-1
and M-4 gained their owners in this merge** rather than being carried across: M-1 is the storage
question [Task 13](Task.13.md) asks at first run, and M-4 is Android network reach, which is
[Task 16](Task.16.md)'s subject. Neither assignment changes a status, an exit test, or the wording
of a row.

---

## §C — Known trade-offs (recorded, owned, not hidden)

<!-- AI-AGENT-INSTRUCTION: These are accepted costs with named owners, not §B rows. Do not present the
     green workspace Rust gate as covering the Tauri shell crate while the first row stands. -->

| Trade-off | Consequence | Owner |
|---|---|---|
| The crate is **workspace-excluded** from the headless `cargo test` gate (Tauri's native webkit dependencies would burden every unrelated Rust change) | CI does not compile the app until the dedicated build job covers every target. **Never read the green workspace Rust gate as covering this crate.** Its tests run via `cd app && cargo test` | [3](Task.3.md) |
| The app supervises a **Java** worker | Deliberate: Option B is locked by the single-engine rule ([`../peer/Task.0.md`](../peer/Task.0.md)). A Rust-native lightweight seeder mode remains a possible later addition, never a validator | [`../peer/`](../peer/Task.0.md) |
| The app is not a peer | It holds no signing keys and serves nothing to the network. Any behaviour that touches the network belongs in the worker or the Rust services — if you are adding peer logic here, stop | [1](Task.1.md) |

---

## Reading guide for the implementing model

- **Badge honestly rather than delete.** L-56 is the precedent: a control the node cannot honour keeps
  its saved value and gains a reason, because deleting it would both discard user data and conceal a
  known gap.
- **Do not put rules in the UI.** If a panel needs a decision, the decision belongs in the worker where
  it can be asserted headlessly on the Java gate.
- **Per-platform behaviour gets per-platform acceptance.** One OS passing is not evidence about
  another, and a host build is not evidence about a phone.
- **A physical exit stays physical.** M-NET-2 and L-94 are RETIRING, not RETIRED, because their exit
  tests name a device and a device has not run them. A green host build is not that device.
