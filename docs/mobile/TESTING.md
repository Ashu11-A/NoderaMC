# Mobile — Building, Debugging and Testing

<!-- AI-AGENT-INSTRUCTION: This is the operational guide for the Android build. Two rules for edits:
     (1) every command here must have been run, in this form, on a real device — no illustrative
     invocations; (2) any claim about the worker must be evidenced by a control-socket answer FROM
     THE DEVICE, never by what the app's own UI shows. Keep §0 (the device last used) accurate. -->

**Category:** mobile · **Last run:** 2026-07-28

> **The live suites are Java scenarios now.** Every `scripts/e2e-<id>.sh` became `dev.nodera.testkit.scenario.<Id>Scenario` and runs through one command:
> `scripts/nodera-test.sh run <id>` (`list` shows them all). The stages, evidence strings and timeouts were carried over, so a report maps onto an old run line by line. The tooling is documented in [`docs/testing/`](../testing/Task.0.md).

Last verified on a **Xiaomi 2210129SG** (`ziyi`), **Android 15 / API 35**, arm64-v8a, against a
tracker on the development machine at `10.0.0.101:25600`.

---

## 0. What you are building

One APK containing two programs:

```text
  dev.nodera.app  (one Android process)
  ├── libnodera_app_lib.so      the Tauri/Rust app + WebView UI
  └── assets/nodera-worker.jar  the REAL Java peer worker, as dex
        └── loaded by NoderaWorker.kt → HeadlessPeerMain.main()
              └── binds 127.0.0.1:25610  ← the Rust side connects here
```

The phone is a full node: same identity, same transport, same control protocol as the desktop. That
matters for testing — **anything the app displays can be verified independently** by talking to the
worker's control socket over `adb`.

---

## 1. One-time setup

### 1.1 The Android toolchain

```bash
scripts/android-toolchain.sh            # install / verify everything
scripts/android-toolchain.sh --check    # report only, install nothing
scripts/android-toolchain.sh --env      # print the exports, if you want them in your shell
```

Installs under `$ANDROID_HOME` (default `~/Android/Sdk`); nothing lands in the repository.

| Component | Pinned version | Why pinned |
|---|---|---|
| Command-line tools | 11076708 | reproducibility |
| Platform | android-34 (toolchain) | compile target installed by the script. ⚠ The **generated** Gradle project now `compileSdk`/`targetSdk = 36` (`gen/android/app/build.gradle.kts:17,23`); AGP will pull platform 36 + its licences on the first build. Tracked as [M-9](LIMITATIONS.md) — the toolchain and the generated project disagree |
| Build-tools | 34.0.0 **and 35.0.0** | 34 signs; **35 is required to dex** — see §1.2 |
| NDK | 26.1.10909125 | Rust cross-compilation |
| Rust targets | 4 Android triples | `aarch64`, `armv7`, `i686`, `x86_64` |

### 1.2 The two version pins that will cost you a day if you change them

| Pin | Where | Symptom when wrong |
|---|---|---|
| **JDK 21** for the Gradle build | `scripts/android-apk.sh` picks its own `JAVA_HOME` | `BUG! exception in phase 'semantic analysis' … Unsupported class file major version 69` — the Android Gradle Plugin cannot read a newer JDK's class files, and this repo builds on 25 |
| **build-tools 35** for `d8` | `DEX_BUILD_TOOLS` in the same script | `Unsupported class file major version 65`. Build-tools 34 ships R8 8.2, which refuses Java 21 class files outright. **This error was misread for months as "ART cannot run Java 21 pattern switches"** — it is a tooling version, not a platform limit |

Both are enforced by the script, which prints the JDK it chose. Building by hand means setting them
yourself.

---

## 2. Building

### 2.1 The Android APK

```bash
scripts/android-apk.sh                    # signed release APK → build/nodera-release.apk
scripts/android-apk.sh --debug            # debug build (larger, unminified)
scripts/android-apk.sh --install          # …and adb install it afterwards
scripts/android-apk.sh --skip-worker      # UI-only rebuild; reuses the staged worker dex
scripts/android-apk.sh --target armv7     # a different ABI (default aarch64)
scripts/android-apk.sh --help
```

What it does, in order — each step exists because something broke without it:

1. **Builds the worker** (`./gradlew :worker:installDist`) and **dexes** its whole dependency closure
   with `d8` from build-tools 35.
2. **Merges the jar resources back in.** `d8` emits classes only; without this the worker starts and
   then dies on a missing `VERSION`, and finds no SLF4J provider.
3. **Strips foreign natives** (`*.so`, `*.dll`, `*.jnilib` for desktop ABIs) — 60 MB the phone can
   never load, and the worker reaches `online` without them.
4. **Copies the Kotlin** from `rust/nodera-app/android/kotlin/` into the generated project, because
   `gen/` is disposable and Tauri regenerates it.
5. **Patches the generated project**, idempotently: the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   permission, the `androidx.documentfile` dependency, and R8 **keep rules** for the three classes
   Rust calls over JNI — without them R8 renames `NoderaStorage.pick` and the folder picker fails
   with `NoSuchMethodError`.
6. **Builds, aligns, signs and verifies** the APK into `build/`.

The signing key is a **development** key at `~/.nodera/android-release.jks`, generated on first run.
It exists so the APK installs; it is not a Play Store upload key. Override with
`NODERA_ANDROID_KEYSTORE`, `NODERA_ANDROID_KEY_ALIAS`, `NODERA_ANDROID_KEY_PASS`.

### 2.2 The desktop app, for comparison

```bash
cd rust/nodera-app && cargo tauri build     # installer
cd rust/nodera-app && cargo tauri dev       # window + tray + worker supervisor
./gradlew :worker:installDist               # the worker on its own
rust/target/release/nodera-tracker          # a tracker to announce to
```

### 2.3 Rebuild loops that do not waste time

| You Changed | Run |
|---|---|
| Only the React UI | `scripts/android-apk.sh --skip-worker` |
| Rust in `nodera-app` | `scripts/android-apk.sh --skip-worker` |
| Any Java module | `scripts/android-apk.sh` (re-dexes; ~1 min, memory-hungry) |
| Kotlin in `android/kotlin/` | `scripts/android-apk.sh --skip-worker` (it re-copies every run) |

Dexing needs real memory. A build killed with **exit 137** is out of memory: `./gradlew --stop`,
then rebuild.

---

## 3. Connecting the phone

### 3.1 USB

Enable **Developer options → USB debugging**, plug in, accept the RSA prompt.

```bash
adb devices -l          # must show `device` — not `unauthorized`, not empty
```

### 3.2 Wi-Fi debugging — recommended, and why

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

### 3.3 Reading logs

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

## 4. Testing

### 4.1 The end-to-end test

```bash
rust/target/release/nodera-tracker &          # something to announce to
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
| 3 | The device has a peer identity | its own log line, out of logcat |
| 4 | A tracker accepted its announce | the app's log line, with the tracker and the latency |
| 5 | **The tracker returns this phone to somebody else** | `nodera-query`, run **from the laptop** |
| 6 (optional, M-NET-2) | Worker selected the one-port range saved in Settings after a full relaunch | `NODERA-STATE.self_route`, read on-device by `--expect-p2p-port` |

Row 5 is the one the app cannot fake. Last run: **5 passed, 0 failed**, the tracker returning
`b24dc714-…` at `10.0.0.104:48570`.

Row 6 was added for issue #86 and has not run on a physical phone yet. Until it passes, M-NET-2 is
RETIRING rather than RETIRED.

### 4.2 The mesh test — does the phone receive data from the Linux peers?

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

### 4.3 Querying the tracker yourself

```bash
cargo build --release -p nodera-tracker --bin nodera-query
rust/target/release/nodera-query 10.0.0.101:25600             # the mobile commons world
rust/target/release/nodera-query 10.0.0.101:25600 <worldIdHex>
```

Prints the world, its health, and every peer the tracker knows with its dial route. It uses
`nodera-codec` — the same frozen encoding the peers use — so it cannot drift from what it is
checking.

### 4.4 Talking to the worker on the phone

```bash
adb shell 'printf "NODERA-PROBE 2\n" | timeout 5 toybox nc 127.0.0.1 25610'
# NODERA-OK 2 0.1.0

adb shell 'printf "NODERA-STATE 2\n" | timeout 5 toybox nc 127.0.0.1 25610'
# {"node_id":"…","self_route":"10.0.0.104:25620","roles":["BOOTSTRAP","REGION_VALIDATOR","FULL_ARCHIVE"],…}

adb shell '(printf "NODERA-WATCH 2 1000\n"; sleep 3) | timeout 6 toybox nc 127.0.0.1 25610'
# the push stream the app itself consumes
```

`self_route` is a LAN address, not loopback — other peers can dial this phone. The full verb list is
in [`worker/Task.2.md`](../worker/Task.2.md).

### 4.5 Memory, when something feels wrong

```bash
adb shell dumpsys meminfo dev.nodera.app | head -12
```

**Native Heap** is the number that matters — the Rust side and the WebView both allocate there.
Healthy is ~14 MB shortly after launch. Hundreds of megabytes and climbing is a runaway allocation,
not normal growth: that is exactly how the `/dev/urandom` bug presented before it was found (2.5 GB
in five seconds, then the OS killed the app).

### 4.6 Unit and integration suites

```bash
cd rust/nodera-app && cargo test              # the app's own tests, incl. storage and battery
cd rust && cargo test --workspace             # codec, tracker, rendezvous, telemetry
./gradlew :core:test                          # includes ThreadsTest — the virtual-thread probe
./gradlew :worker:test
```

Last app run on 2026-07-28: **188 passed**, including Android property/desktop environment parity,
control isolation, changed-property-file replacement, and both context/setup startup orders. Java's
`AndroidPortPropertyTest` separately launches a real worker with a property-only P2P port and proves
that exact port appears in `NODERA-STATE.self_route`. `scripts/android-apk.sh --debug` also built and
verified a signed 185 MiB APK, compiling the frontend, Android-only Rust JNI path and Kotlin bridge.
No device was connected, so the physical selected-port exit did not run.

`ThreadsTest` is the one to watch: it asserts that a started thread **runs its body**, which is the
property ART broke and which a construction-only test would have missed entirely.

The dex guard (`scripts/check-android-bytecode.sh`) now has a Java-level mirror in `./gradlew check`:
`:transport`'s `AndroidTypeSwitchGuardTest` fails if any class in that module compiles to a
`SwitchBootstraps` type-switch. It covers only the transport module — the whole-closure guarantee is
still the dex script — but it is what caught the M-5 regression's rewrite site at issue time.

### 4.7 Manual checks with no script

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

---

## 5. Script reference

| Script | Purpose | Key flags |
|---|---|---|
| `scripts/android-toolchain.sh` | Install/verify the SDK, NDK and Rust targets | `--check`, `--env` |
| `scripts/android-apk.sh` | Build → dex the worker → sign → `build/` | `--debug`, `--install`, `--skip-worker`, `--target` |
| `scripts/android-e2e.sh` | Install, launch, prove the node, optionally assert its selected P2P port from state | `--no-install`, `--tracker`, `--apk`, `--expect-p2p-port` |
| `scripts/nodera-test.sh run android-mesh` | The phone in the mesh with the Linux peers, receiving bytes | `--no-game`, `--no-apk`, `--no-build`, `--serial` |
| `rust/target/release/nodera-query` | Ask a tracker who it knows, from another machine | `<host:port> [worldIdHex]` |
| `rust/target/release/nodera-tracker` | The tracker itself; binds `0.0.0.0:25600` TCP+UDP | env-configured |

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

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unsupported class file major version 69` | Gradle running on JDK 25 | The script picks JDK 21; set `NODERA_ANDROID_JAVA_HOME` if it cannot find one |
| `Unsupported class file major version 65` | `d8` from build-tools 34 | Install build-tools 35 (`scripts/android-toolchain.sh`) |
| `Failed to install … platforms;android-36` | Toolchain installs platform 34; the generated project compileSdk is 36 | Let AGP install 36, or `sdkmanager "platforms;android-36"`. Tracked as [M-9](LIMITATIONS.md) |
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

## 7. A full session, start to finish

```bash
# once
scripts/android-toolchain.sh

# a tracker for the phone to find
rust/target/release/nodera-tracker > run/logs/tracker.log 2>&1 &

# the phone, over Wi-Fi
adb tcpip 5555
adb connect 10.0.0.104:5555
export ANDROID_SERIAL=10.0.0.104:5555

# build, install, prove
scripts/android-apk.sh --install
scripts/android-e2e.sh --no-install

# watch it work
adb logcat -s NoderaMC:V
rust/target/release/nodera-query 10.0.0.101:25600
```
