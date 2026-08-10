# Frontend — Testing

<!-- AI-AGENT-INSTRUCTION: This file covers THREE surfaces and they are tested by different things.
     The Tauri shell crate is WORKSPACE-EXCLUDED: `cargo test` at the root does NOT cover it, and
     presenting the green workspace gate as covering it is a lie the register exists to prevent —
     run it explicitly. `library/rust/nodera-core` IS in the root workspace. The desktop dashboard's
     data path is asserted on the JAVA gate (the worker's STATE verb); keep it that way — no logic in
     the UI worth testing beyond parsing. For Part B, two rules: every command must have been run, in
     that form, on a real device — no illustrative invocations; and any claim about the worker must be
     evidenced by a control-socket answer FROM THE DEVICE, never by what the app draws. Keep §0's
     device and every count and Last run current. Part B's section numbers are cited from Java
     sources (`docs/frontend/TESTING.md §3.2`) — do not renumber them. -->

**Category:** frontend

| Surface | Last run | Result |
|---|---|---|
| Desktop launcher + shared core | 2026-08-06 | **371 tests** (296 shared core + 2 shell, one intentionally ignored + 73 frontend): 370 passed, 1 ignored |
| Android | last build 2026-08-01 · last physical run 2026-08-01 | `scripts/android-e2e.sh` **5 passed, 0 failed** on a Xiaomi 2210129SG (Android 15 / API 35) |
| Website | 2026-08-06 | **144 passed, 0 failed** — `scripts/build-site.sh` runs the suite and now counts it |

```bash
cd app && cargo test        # REQUIRED — root workspace does not cover the Tauri shell
cargo test                  # includes library/rust/nodera-core
scripts/build-app-ui.sh     # type-check + bundle + emitted-CSS contracts, counted and stamped
scripts/build-site.sh       # the website, same treatment
scripts/android-apk.sh      # the phone, end to end, including the dexed worker
scripts/android-e2e.sh      # the only one that proves the phone is still a node
```

Both frontend counts above are **measured, not typed**. `cd app/ui && bun run build` ends in
`node --test "tests/*.test.mjs"`, which answers a glob that matches no file with `# pass 0` and
**exit 0** — so until 2026-08-06 the whole of this row could have gone to zero without one thing
turning red, and the `app/ui/tests` figure below had drifted to less than half of the truth. The
build scripts now pipe the suite through `tee`, read the count with `scripts/test-totals.sh --tap`,
and hold it to README's module-status table with `scripts/test-counts.sh --check <package>`. Both
totals also reach the test badges. Re-stamp with `scripts/test-counts.sh --write <package>` in the
same commit as the test that moved the number.

Counting a suite still assumes something ran it, and the two build scripts above each name the one
package they build — so a third package with a real suite in it was executed by nothing, produced no
count, and was reported `skipped` for ever while `--check` asked it only for a README row. That is
now a failure: `scripts/test-counts.sh --runners` names the script that both runs and counts each
package declaring a `tests/*.test.mjs`, `--check` refuses a package no script does that for, and
`app/ui/tests/layout-workspace.test.mjs` holds the answer to `layout.properties`. Adding a fourth
frontend package therefore means adding the build script that runs its suite, in the same commit.

---

## Part A — Desktop launcher and shared core

### A1. The testing strategy

The app is deliberately the thinnest layer in the project, and its test strategy follows from that:

| Concern | Where it is tested | Why |
|---|---|---|
| The **numbers** on the dashboard | The Java gate, via the worker's `STATE` verb | They are produced by the worker; asserting them here would test the wrong component |
| **Parsing** those numbers | Here | Byte order, tolerance, and error surfacing are genuinely the app's responsibility |
| **Supervisor lifecycle** | Here | Spawn, attach, stop — including the rule that quitting never kills a worker the app did not spawn |
| **Packaging and install** | CI ([`Task.3.md`](Task.3.md)) | Only a clean-checkout build catches gitignored build inputs |
| **The product claim** | CI ([`Task.4.md`](Task.4.md)) | Install → gate both ways → host → close Minecraft → still joinable |
| **Generated UI roles and shell padding** | Post-build Node regression over Vite's emitted CSS | Tailwind silently omits unknown utilities; mobile must resolve through dynamic Material 3 roles; desktop and mobile intentionally frame the shared component differently |

### A2. Crate coverage

- **Piece-bitmap decode** matching Java's `BitSet` byte order, plus bounded, short, and undecodable
  bitmaps. A mis-decoded bitmap renders a *plausible but wrong* picture, which is worse than rendering
  nothing — hence the explicit byte-order contract test.
- **Additive-field tolerance** against a golden `STATE` JSON: unknown fields use serde defaults, so a
  worker newer than its dashboard degrades to fewer panels rather than to an error.
- **Control-socket error surfacing** and read timeout.
- **`Settings → WorkerConfig` golden JSON** — the cross-language configuration-key contract, pinned on
  both sides so a renamed key fails a test rather than silently doing nothing.
- **Worker-environment spawn pairs** and a **power-state truth table**.
- **Enforcement invariants:** every badge state is covered, and the "live only if confirmed" rule
  holds — a control may not be shown as enforced unless the worker confirmed it.
- **Log ring** and **system sampling**.

#### Test counts (run 2026-08-06)

A run count, not a source estimate. Shared behavior moved to root-workspace `nodera-core`; the Tauri
shell now has only platform integration tests. Frontend tests run after production Vite output exists.

The two frontend rows are the ones a build script re-measures and a gate holds to README; they are
not to be hand-edited here. `app/ui/tests` read 31 for months against a suite of 70, which is how a
count typed into a document behaves — and why nothing but a measurement belongs in this column.

The `nodera-core` row is measured too, by the same gate: `scripts/test-counts.sh --check` enumerates
every workspace crate with `cargo test -- --list` and holds it to README's module table. This copy
of that cell is what drifted — it read 270 against a measured 296 until 2026-08-06 — because the
sentence above was written about the two rows nobody had gated yet, and the row that did have a gate
was left to be typed by hand. Take README's cell as the value; this table is a convenience.

| Suite | Tests |
|---|---:|
| `library/rust/nodera-core` | 296 |
| `app` Tauri shell | 2 (1 ignored: opens a real browser) |
| `app/ui/tests` | 73 |
| `web/tests` | 144 |
| **Total** | **515** |

### A3. Manual smoke, per increment

```bash
scripts/dev.sh --with-app      # attach mode against an externally started worker
```

Checks:

1. The dashboard shows live data, not zeros.
2. Quitting the app leaves the externally started worker **running** (attach semantics).
3. Stopping the worker makes the tray show offline and the dashboard show the daemon down — with no
   crash loop.

### A4. Conventions

- **Never claim the workspace gate covers this crate.** It does not, and saying so would hide a real
  coverage gap that [`Task.3.md`](Task.3.md) exists to close.
- **No logic in the UI worth testing beyond parsing.** If a panel needs a rule, put the rule in the
  worker where it is asserted headlessly.
- **Three protocol mirrors, one commit.** A control-protocol change lands in the worker's
  `ControlProtocol`, the mod's `CompanionProtocol`, and this crate's `control.rs` together, with the
  version bumped — the mod's gate then classifies skew as "update the app" or "update the mod".
- **Per-platform behaviour gets per-platform acceptance.** One OS passing is not evidence about
  another.

### A5. CI

The companion job builds the app end to end from a clean checkout (UI build plus cargo release, with
the worker distribution staged and bundled) and runs the gate in both directions. Its clean-checkout
property is the point: the four packaging gaps it found on its first run were all files that worked
locally and were gitignored.

### A6. The launch lane

`nodera-core/src/launch/` is unit-tested against fixtures — synthetic `versions/*.json` files written
into a temp directory — so the parsers run on a machine with no Minecraft installed. That covers what
actually breaks in a launcher: `inheritsFrom` resolution, classpath ordering, natives never reaching
the classpath, the placeholder table, and the `has_quick_plays_support` gate that decides whether the
player lands in the world or in the Multiplayer menu.

What it cannot cover is a real game starting. That is a human protocol:

1. Two sides. Two machines, or `scripts/dev.sh --play --with-app` with `NODERA_APP_MULTI=1`.
2. Host: open a world to LAN from inside Minecraft, then Share when the app asks.
3. Guest: **Discover** → the world appears → press **Play**. The shell returns to Play and restores
   the coordinator's current correlated phase.
4. Watch all four, not one:
    - the `nodera://launch` phases arrive in order — `resolving`, `joining`, `preparing`, `spawning`,
      `running`;
   - `ss -ltnp` shows the loopback port bound between `joining` and `preparing`;
   - `ps -ef | grep quickPlayMultiplayer` shows the flag **and the port matches**;
   - the game window opens straight into the world, with no Multiplayer menu.
5. Direct route: quit the game; `exited` fires and the port disappears. Delegated launcher route:
    the screen says it handed off because launcher lifetime is not game lifetime; press **Close
    connection**, observe `closing` while cleanup runs, then verify `exited` and the port disappears.

Each failure path is scriptable through `NODERA_MINECRAFT_DIR`, and each must produce its own remedy
button rather than a generic error:

| Break this | Expect |
|---|---|
| rename the Nodera jar out of `mods/` | `install-mod` |
| point `NODERA_MINECRAFT_DIR` at an empty directory | `pick-install` |
| point `JAVA_HOME` at a Java 17 | `install-java`, naming the version the profile requires |


---

## Part B — Android: building, debugging and testing

> **The live suites are Java scenarios now.** Every `scripts/e2e-<id>.sh` became `dev.nodera.testkit.scenario.<Id>Scenario` and runs through one command:
> `scripts/nodera-test.sh run <id>` (`list` shows them all). The stages, evidence strings and timeouts were carried over, so a report maps onto an old run line by line. The tooling is documented in [`docs/testing/`](../testing/Task.0.md).

Last verified on a **Xiaomi 2210129SG** (`ziyi`), **Android 15 / API 35**, arm64-v8a, against a
tracker on the development machine at `10.0.0.101:25600`.

### 0. What you are building

One APK containing two programs:

```text
  dev.nodera.app  (one Android process)
  ├── libnodera_app_lib.so      shared Rust core reached through two JNI calls
  ├── MainActivity + ui/*       native Jetpack Compose Material 3 interface
  └── assets/nodera-worker.jar  the REAL Java peer worker, as dex
        └── loaded by NoderaWorker.kt → HeadlessPeerMain.main()
              └── binds 127.0.0.1:25610  ← the Rust side connects here
```

The phone is a full node: same identity, same transport, same control protocol as the desktop. That
matters for testing — **anything the app displays can be verified independently** by talking to the
worker's control socket over `adb`.

---

### 0.1 Three gates before a device, in order

The phone is a Compose application now, not a webview, so the desktop's `bun run build` says nothing
about it. Three gates, in order — each is cheap and each catches something the next one would only
surface on a device:

```sh
scripts/check-android-bytecode.sh          # ART cannot run Java 21 type-pattern switches
scripts/android-apk.sh --debug --skip-worker   # the fast loop: Compose + Kotlin + Rust, no re-dex
scripts/android-apk.sh                     # the whole thing, including the dexed worker
scripts/android-e2e.sh                     # the only one that proves the node still works
```

`android-e2e.sh` is the real gate. It verifies **from the tracker's side** that the phone announced —
which is exactly the property a front-end rewrite can break with no screen looking wrong.

Two things the build script asserts rather than assumes, because both have failed silently before:
that every JNI-reachable class has an R8 `-keep` rule (a stripped method is a `NoSuchMethodError`
from Rust at the moment a user taps something), and that `MainActivity` is `singleTask` (without it a
`nodera://tracker-store` link arriving while the app is open starts a second activity and the offer
is dropped).

---

### 1. One-time setup

#### 1.1 The Android toolchain

```bash
scripts/android-toolchain.sh            # install / verify everything
scripts/android-toolchain.sh --check    # report only, install nothing
scripts/android-toolchain.sh --env      # print the exports, if you want them in your shell
```

Installs under `$ANDROID_HOME` (default `~/Android/Sdk`); nothing lands in the repository.

| Component | Pinned version | Why pinned |
|---|---|---|
| Command-line tools | 11076708 | reproducibility |
| Platform | android-34 (toolchain) | compile target installed by the script. ⚠ The **generated** Gradle project now `compileSdk`/`targetSdk = 36` (`gen/android/app/build.gradle.kts:17,23`); AGP will pull platform 36 + its licences on the first build. Tracked as [M-9](LIMITATIONS.fixed.md) — the toolchain and the generated project disagree |
| Build-tools | 34.0.0 **and 35.0.0** | 34 signs; **35 is required to dex** — see §1.2 |
| NDK | 26.1.10909125 | Rust cross-compilation |
| Rust targets | 4 Android triples | `aarch64`, `armv7`, `i686`, `x86_64` |

#### 1.2 The two version pins that will cost you a day if you change them

| Pin | Where | Symptom when wrong |
|---|---|---|
| **JDK 21** for the Gradle build | `scripts/android-apk.sh` picks its own `JAVA_HOME` | `BUG! exception in phase 'semantic analysis' … Unsupported class file major version 69` — the Android Gradle Plugin cannot read a newer JDK's class files, and this repo builds on 25 |
| **build-tools 35** for `d8` | `DEX_BUILD_TOOLS` in the same script | `Unsupported class file major version 65`. Build-tools 34 ships R8 8.2, which refuses Java 21 class files outright. **This error was misread for months as "ART cannot run Java 21 pattern switches"** — it is a tooling version, not a platform limit |

Both are enforced by the script, which prints the JDK it chose. Building by hand means setting them
yourself.

---

### 2. Building

#### 2.1 The Android APK

```bash
scripts/android-apk.sh                    # signed release APK → build/nodera-release.apk
scripts/android-apk.sh --debug            # debug build (larger, unminified)
scripts/android-apk.sh --install          # …and adb install it afterwards
scripts/android-apk.sh --skip-worker      # UI-only rebuild; reuses the staged worker dex
scripts/android-apk.sh --target armv7     # a different ABI (default aarch64)
scripts/android-apk.sh --help
```

What it does, in order — each step exists because something broke without it:

1. **Builds the worker** (`./gradlew :peer:installDist`) and **dexes** its whole dependency closure
   with `d8` from build-tools 35.
2. **Merges the jar resources back in.** `d8` emits classes only; without this the worker starts and
   then dies on a missing `VERSION`, and finds no SLF4J provider.
3. **Strips foreign natives** (`*.so`, `*.dll`, `*.jnilib` for desktop ABIs) — 60 MB the phone can
   never load, and the worker reaches `online` without them.
4. **Copies the Kotlin** from `app/android/kotlin/` into the generated project, because
   `gen/` is disposable and Tauri regenerates it.
5. **Patches the generated project**, idempotently: Compose Material 3/activity/lifecycle/browser/
   document dependencies, permissions, Kotlin 2 Compose plugin, manifest deep-link/singleTask rules,
   and R8 **keep rules** for JNI classes.
6. **Builds, aligns, signs and verifies** the APK into `build/`.

The signing key is a **development** key at `~/.nodera/android-release.jks`, generated on first run.
It exists so the APK installs; it is not a Play Store upload key. Override with
`NODERA_ANDROID_KEYSTORE`, `NODERA_ANDROID_KEY_ALIAS`, `NODERA_ANDROID_KEY_PASS`.

#### 2.2 The desktop app, for comparison

```bash
cd app && cargo tauri build     # installer
cd app && cargo tauri dev       # window + tray + worker supervisor
./gradlew :peer:installDist               # the worker on its own
target/release/nodera-tracker          # a tracker to announce to
```

#### 2.3 Rebuild loops that do not waste time

| You Changed | Run |
|---|---|
| Only desktop React UI | `cd app/ui && bun run build` (Android runtime does not render it) |
| Rust in `nodera-app` | `scripts/android-apk.sh --skip-worker` |
| Any Java module | `scripts/android-apk.sh` (re-dexes; ~1 min, memory-hungry) |
| Kotlin in `android/kotlin/` | `scripts/android-apk.sh --skip-worker` (it re-copies every run) |

The 2026-08-01 host build also asserts native review invariants before Gradle: immutable reviewed
tracker-store URL, `BackHandler`, exact numeric settings, and non-clickable status semantics. These
are source/build guards, not substitutes for physical touch acceptance.

Dexing needs real memory. A build killed with **exit 137** is out of memory: `./gradlew --stop`,
then rebuild.

---

### 3. Connecting the phone

#### 3.1 USB

Enable **Developer options → USB debugging**, plug in, accept the RSA prompt.

```bash
adb devices -l          # must show `device` — not `unauthorized`, not empty
```

#### 3.2 Wi-Fi debugging — recommended, and why

USB re-enumerates under load on some phones; an `adb install` then dies half-way with
`adb: no devices/emulators found`, which looks like a build problem and is not. Wireless survives it,
and it is also the network path the tracker test actually exercises.

**Any device already reachable over USB:**

```bash
adb tcpip 5555                                              # once, over USB
adb shell ip -4 addr show wlan0 | grep -o 'inet [0-9.]*'    # the phone's address
adb connect 10.0.0.104:5555
export ANDROID_SERIAL=10.0.0.104:5555                       # every later adb call targets the phone
adb devices -l                                              # confirm
```

**Android 11+ with no cable at all** — *Developer options → Wireless debugging → Pair device with
pairing code*:

```bash
adb pair 10.0.0.104:37105        # the PAIRING port and the code shown on the phone
adb connect 10.0.0.104:5555      # the CONNECT port, which is a different number
```

Things that cost time when forgotten:

* The pairing port and the connect port are **different**; the phone shows both.
* `adb tcpip` does not survive a reboot — re-run it over USB.
* Set `ANDROID_SERIAL`, or pass `-s`. With a USB entry *and* a wireless entry present, an unqualified
  `adb` command fails with "more than one device".
* The phone must be on the same network as the tracker. `10.0.0.101` (the development machine) is the
  default tracker for mobile builds; loopback is deliberately **not** in that list, because
  `127.0.0.1` on a handset is the handset.

#### 3.3 Reading logs

```bash
adb logcat -s NoderaMC:V                 # the app AND the worker, one tag
adb logcat -d -s NoderaMC:V | tail -20   # last few lines, no follow
adb logcat -c                            # clear before a run
adb logcat -d -b crash                   # the Java crash buffer
```

Everything Nodera writes uses the `NoderaMC` tag: Rust `log::` calls go through `android_logger`, the
Kotlin shim logs there directly, and the Java worker's SLF4J output arrives as `System.err` under the
app's pid. A Rust panic is caught by a hook and logged as `PANIC: …` before the process dies —
without it, an abort leaves nothing behind at all.

---

### 4. Testing

#### 4.1 The end-to-end test

```bash
target/release/nodera-tracker &          # something to announce to
scripts/android-e2e.sh                        # install, launch, prove
scripts/android-e2e.sh --no-install           # test what is already installed
scripts/android-e2e.sh --tracker 10.0.0.101:25600
scripts/android-e2e.sh --apk build/nodera-debug.apk
```

It answers the only question worth asking — *is that thing a node, or a screen that says it is?* —
**from both ends**:

| # | Check | Evidence |
|---|---|---|
| 1 | The app is installed | `pm list packages` |
| 2 | The process survives launch | `pidof dev.nodera.app` after 4 s |
| 3 | The worker has a peer identity | `NODERA-STATE.node_id`, read on-device over loopback control |
| 4 | The worker exchanged with the selected tracker | matching reachable tracker in `NODERA-STATE.trackers` |
| 5 | **The tracker newly returns this phone to somebody else** | successful pre-launch `nodera-query` baseline does not contain the worker id; post-launch query, run **from the laptop**, returns that exact UUID token |
| 6 (optional, M-NET-2) | Worker selected the one-port range saved in Settings after a full relaunch | `NODERA-STATE.self_route`, read on-device by `--expect-p2p-port` |

Row 5 is the one the app cannot fake. The tracker must start without a retained entry for this
phone; the script refuses an unreadable baseline or a cached identity rather than letting yesterday's
announce prove today's APK. Its deadline spans two 60-second commons retries rather than racing the
first retry, and that one deadline covers both worker-state and independent-query phases. Last run
on 2026-08-01 (fresh tracker, `--no-install`): **5 passed, 0 failed**, the development tracker
returning worker `a3da8287-…` at `10.0.0.104:40675`.

Row 6 was added for issue #86 and has not run on a physical phone yet. Until it passes, M-NET-2 is
RETIRING rather than RETIRED.

#### 4.2 The mesh test — does the phone receive data from the Linux peers?

```bash
scripts/nodera-test.sh run android-mesh                       # the whole thing
scripts/nodera-test.sh run android-mesh             # peers only, no Minecraft clients
scripts/nodera-test.sh run android-mesh   # reuse what is already built and installed
scripts/nodera-test.sh run android-mesh
```

`android-e2e.sh` (§4.1) asks whether the phone can be *found*. This one asks whether it can be
*talked to* — the harder and more useful question.

| Phase | What happens |
|---|---|
| **P0** | Builds and installs the APK over Wi-Fi debugging. Refuses a USB-only device, because a peer that cannot be dialled by IP cannot be in the mesh |
| **P1** | The Linux stack **bound to the LAN**: 1 tracker, 1 rendezvous, 3 headless workers, plus 2 Tauri companion apps in attach mode — one per player |
| **P2** | Two Minecraft clients join a hosted world — the reason the peers have anything to say |
| **P3** | The phone's worker is confirmed **through adb**, by asking its own control socket. Fails if it advertises loopback, which means Wi-Fi is off |
| **P4** | The phone announces to the LAN tracker, and `nodera-query` **run on this machine** looks for it there |
| **P5** | **The assertion.** The phone joins the mesh (`NODERA-MESH`) and its own counters must show a peer and `total_received_bytes` above where it started |

The last phase is the point. It is asserted from the phone's own `NODERA-STATE`, not from the app's
UI, and both directions are checked separately — a NAT can let traffic flow one way while
"connected" hides it.

Requires the LAN binds, which is why the launcher grew `NODERA_SERVICE_BIND_ADDR` and
`NODERA_SERVICE_ADVERTISE_ADDR`. They default to loopback, so every other suite is unchanged; this
one sets `0.0.0.0` and this host's address, derived from the route to the phone (so a machine with
docker bridges still advertises the interface the phone can actually reach).

Evidence lands in the run's log directory: `android-logcat.log`, `android-state.json`, plus every
worker log the standard launcher collects.

#### 4.3 Querying the tracker yourself

```bash
cargo build --release -p nodera-tracker --bin nodera-query
target/release/nodera-query 10.0.0.101:25600             # the mobile commons world
target/release/nodera-query 10.0.0.101:25600 <worldIdHex>
```

Prints the world, its health, and every peer the tracker knows with its dial route. It uses
`nodera-codec` — the same frozen encoding the peers use — so it cannot drift from what it is
checking.

#### 4.4 Talking to the worker on the phone

```bash
adb shell 'printf "NODERA-PROBE 2\n" | timeout 5 toybox nc 127.0.0.1 25610'
# NODERA-OK 2 0.1.0

adb shell 'printf "NODERA-STATE 2\n" | timeout 5 toybox nc 127.0.0.1 25610'
# {"node_id":"…","self_route":"10.0.0.104:25620","roles":["BOOTSTRAP","REGION_VALIDATOR","FULL_ARCHIVE"],…}

adb shell '(printf "NODERA-WATCH 2 1000\n"; sleep 3) | timeout 6 toybox nc 127.0.0.1 25610'
# the push stream the app itself consumes
```

`self_route` is a LAN address, not loopback — other peers can dial this phone. The full verb list is
in [`worker/Task.2.md`](../peer/Task.2.md).

#### 4.5 Memory, when something feels wrong

```bash
adb shell dumpsys meminfo dev.nodera.app | head -12
```

**Native Heap** is the number that matters — the Rust side and the WebView both allocate there.
Healthy is ~14 MB shortly after launch. Hundreds of megabytes and climbing is a runaway allocation,
not normal growth: that is exactly how the `/dev/urandom` bug presented before it was found (2.5 GB
in five seconds, then the OS killed the app).

#### 4.6 Unit and integration suites

```bash
cd app && cargo test              # the app's own tests, incl. storage and battery
cd rust && cargo test --workspace             # codec, tracker, rendezvous, telemetry
./gradlew :core:test                          # includes ThreadsTest — the virtual-thread probe
./gradlew :peer:test
```

Last shared-core run on 2026-08-01: **270 passed**, including Android property/desktop environment
parity, control isolation, changed-property-file replacement, and both context/setup startup orders. Java's
`AndroidPortPropertyTest` separately launches a real worker with a property-only P2P port and proves
that exact port appears in `NODERA-STATE.self_route`. `scripts/android-apk.sh --debug` also built and
verified and installed a signed 201 MiB debug APK, compiling the frontend, Android-only Rust JNI path,
Kotlin bridge, and real dex worker. Physical startup and the five-check E2E passed; the optional
selected-port exit did not run.

`ThreadsTest` is the one to watch: it asserts that a started thread **runs its body**, which is the
property ART broke and which a construction-only test would have missed entirely.

The dex guard (`scripts/check-android-bytecode.sh`) now has a Java-level mirror in `./gradlew check`:
`:transport`'s `AndroidTypeSwitchGuardTest` fails if any class in that module compiles to a
`SwitchBootstraps` type-switch. It covers only the transport module — the whole-closure guarantee is
still the dex script — but it is what caught the M-5 regression's rewrite site at issue time.

#### 4.7 Manual checks with no script

| Check | How | Expected |
|---|---|---|
| First run asks its questions | `adb shell pm clear dev.nodera.app`, relaunch | Storage, then telemetry, then battery (only when restricted) |
| It asks **once** | complete it, force-stop, relaunch | Straight to the node screen |
| System back goes up, not out | Settings › Appearance, press back | Returns to the list; the app stays running |
| The bar hides in a sub-screen | same | Gone on the sub-screen, back on the list |
| The picker is the system's | Settings › Storage › Choose a folder… | The device's file manager, with **USE THIS FOLDER** |
| A picked folder is verified | pick one, read the card | A path and a tick, or Android's refusal in words |
| A choice survives leaving the screen | pick, save, go back, return | Listed first, marked **In use** |
| Battery restriction is surfaced | launch with optimisation on | Dialog naming the vendor, linking dontkillmyapp.com |
| No tracker reachable | clear the tracker list | Centred message with a button to Settings › Network |
| Dynamic colour is real | Settings › Appearance, change the source | Nav indicator, buttons and surfaces all re-tint |

Driving these with `adb shell input tap` is possible but fragile: coordinates drift between builds,
and a stray tap lands in whatever app happens to be in front. Prefer tapping the phone.

#### 4.8 The M-2 exit test — does the node survive an hour with the screen off?

The one check no script drives, and the one that decides whether a phone is a peer other nodes can
plan around. Before the foreground service, an app holding committee seats was killed roughly 25
minutes into a session and every peer it served lost it in silence.

```bash
adb shell svc power stayon false          # or the screen never sleeps and nothing is tested
adb shell am force-stop dev.nodera.app
adb shell am start -n dev.nodera.app/.MainActivity
sleep 25 && adb shell dumpsys activity services dev.nodera.app | grep isForeground
adb shell input keyevent KEYCODE_HOME     # backgrounded
adb shell input keyevent KEYCODE_SLEEP    # screen off
# ... one hour, undisturbed ...
adb shell pidof dev.nodera.app
adb shell "(printf 'NODERA-PROBE 2\n'; sleep 3) | timeout 15 toybox nc 127.0.0.1 25610"
```

Pass: a pid, and `NODERA-OK`. `isForeground=true` with `types=0x00000001` (dataSync) before the
wait says the service actually took, which is the half that is worth checking early.

**Poll it as little as you can bear.** Each `adb` round trip can wake the device, and a device that
keeps waking is not the device the test is about — checking every minute quietly replaces the
experiment with an easier one. Two checks, at the start and at the hour, are enough.

**Use the port above, 25610.** Probing the wrong port answers `nc: connect: Connection refused`,
which reads exactly like a dead node and is the one failure here that is worth being sure about.
`cat /proc/net/tcp` and look for state `0A` if you need to confirm what is actually listening.

Run 2026-08-04 (Android 15, arm64), and the evidence that retired M-2: `pid=2605` at minute 0 and
still `2605` at minute 60, `isForeground=true foregroundId=1 types=0x00000001`,
`mWakefulness=Dozing` throughout, `NODERA-OK 2 0.1.0` at the hour — and `logcat` showing the worker
doing real work the whole time, an archive seeder lookup every second.

---

### 5. Script reference

| Script | Purpose | Key flags |
|---|---|---|
| `scripts/android-toolchain.sh` | Install/verify the SDK, NDK and Rust targets | `--check`, `--env` |
| `scripts/android-apk.sh` | Build → dex the worker → sign → `build/` | `--debug`, `--install`, `--skip-worker`, `--target` |
| `scripts/android-e2e.sh` | Install, launch, prove the node, optionally assert its selected P2P port from state | `--no-install`, `--tracker`, `--apk`, `--expect-p2p-port` |
| `scripts/nodera-test.sh run android-mesh` | The phone in the mesh with the Linux peers, receiving bytes | `--no-game`, `--no-apk`, `--no-build`, `--serial` |
| `target/release/nodera-query` | Ask a tracker who it knows, from another machine | `<host:port> [worldIdHex]` |
| `target/release/nodera-tracker` | The tracker itself; binds `0.0.0.0:25600` TCP+UDP | env-configured |

Environment variables the scripts read:

| Variable | Default | Effect |
|---|---|---|
| `ANDROID_HOME` | `~/Android/Sdk` | SDK location |
| `ANDROID_SERIAL` | — | Which device every `adb` call targets |
| `NODERA_ANDROID_JAVA_HOME` | auto-detected JDK 21 | The JDK the Gradle build uses |
| `NODERA_ANDROID_TARGET` | `aarch64` | ABI |
| `NODERA_ANDROID_KEYSTORE` | `~/.nodera/android-release.jks` | Signing key |
| `NODERA_TRACKER` | `10.0.0.101:25600` | Tracker the e2e test checks |
| `NODERA_ANDROID_EXPECT_P2P_PORT` | — | Exact port `self_route` must report; same as `--expect-p2p-port` |
| `NODERA_SERVICE_BIND_ADDR` | `127.0.0.1` | Where the tracker and rendezvous bind; `0.0.0.0` for an off-box peer |
| `NODERA_SERVICE_ADVERTISE_ADDR` | `127.0.0.1` | The address peers are told to reach them on |

---

### 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unsupported class file major version 69` | Gradle running on JDK 25 | The script picks JDK 21; set `NODERA_ANDROID_JAVA_HOME` if it cannot find one |
| `Unsupported class file major version 65` | `d8` from build-tools 34 | Install build-tools 35 (`scripts/android-toolchain.sh`) |
| `Failed to install … platforms;android-36` | Toolchain installs platform 34; the generated project compileSdk is 36 | Let AGP install 36, or `sdkmanager "platforms;android-36"`. Tracked as [M-9](LIMITATIONS.fixed.md) |
| Build killed, exit 137 | Out of memory while dexing | `./gradlew --stop`, then rebuild |
| `adb: no devices/emulators found` mid-install | USB re-enumerated under load | Use Wi-Fi debugging (§3.2) |
| `more than one device/emulator` | USB **and** wireless both connected | `export ANDROID_SERIAL=…` |
| App opens, then closes after ~5 s | A Rust panic across an `extern "C"` boundary aborts | `adb logcat -s NoderaMC:V \| grep PANIC` — the hook logs the message and location |
| `NoSuchMethodError: … pick()V` | R8 stripped a JNI entry point | Keep rules; the build script injects them |
| `SecurityException: Writable dex file … is not allowed` | API 29+ refuses a writable dex | The staged jar is marked read-only after copying |
| `This file can not be opened as a file descriptor` | `AssetManager.openFd` on a compressed asset | Staleness is keyed on `lastUpdateTime`, not on asset size |
| Node screen shows `—` and "Offline" | The link never connected | `adb logcat -s NoderaMC:V \| grep 'link:'` — it names the endpoint and the reason |
| Setup reappears every launch | Settings could not be written | Fixed; if it returns, check the `storage:` log line names a writable directory |
| The phone joins but receives 0 bytes | The Linux peers advertise loopback | The mesh suite exports `NODERA_P2P_ADVERTISE_ADDR`; check `worker-*.log` names a LAN route |
| `no wireless device` from the mesh suite | Only a USB device is connected | `adb tcpip 5555 && adb connect <phone-ip>:5555` |
| A picked folder reports "not writable" | Android 11+ withholds raw `File` access to shared storage | Expected — use app storage or an SD-card folder. See [`LIMITATIONS.md`](LIMITATIONS.md) M-1 |

---

### 7. A full session, start to finish

```bash
# once
scripts/android-toolchain.sh

# a tracker for the phone to find
target/release/nodera-tracker > run/logs/tracker.log 2>&1 &

# the phone, over Wi-Fi
adb tcpip 5555
adb connect 10.0.0.104:5555
export ANDROID_SERIAL=10.0.0.104:5555

# build, install, prove
scripts/android-apk.sh --install
scripts/android-e2e.sh --no-install

# watch it work
adb logcat -s NoderaMC:V
target/release/nodera-query 10.0.0.101:25600
```

---

## Part C — The website

There is no automated gate for `web/` today, and this file will not pretend otherwise. The site is
three static files served by Caddy; what proves it is a human opening it.

The gates that will close [task 18](Task.18.md) and [task 20](Task.20.md) are named in those task
files, not here — a test list written before the tests exist is a list nobody updates when the tests
turn out differently. What is already true and worth keeping in one place:

```bash
scripts/deploy-site.sh --dry-run     # what would be sent, changing nothing
scripts/deploy-site.sh --status      # what that host is serving now
```

The one behaviour that must not regress while the site is rebuilt is the tracker-store hop:
`https://noderamc.org/add-store?url=…` must still refuse a non-https index before offering it, show
the address exactly as received, and invoke `nodera://tracker-store` **only from a real click** —
never on load. See [task 9](Task.9.md) § The https hop.
