# Mobile — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the mobile category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (a test, a log line, a control-socket answer), then reconcile ../ROADMAP.md §2.
     Never rewrite an old note. -->

**Category:** mobile · **Last audit:** 2026-07-28 · Tasks completed: **7 / 8**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md) · refactoring
register: [`REFACTORING.md`](REFACTORING.md).

---

## 1. Task status

| Task | Title | Status | Evidence |
|---|---|---|---|
| 1 | Android build pipeline | ✅ COMPLETED | `scripts/android-apk.sh` → signed `build/nodera-release.apk`, installed on a Xiaomi 2210129SG |
| 2 | The Java worker runs on ART | ✅ COMPLETED | `NODERA-PROBE 2` → `NODERA-OK 2 0.1.0` from the device; `self_route 10.0.0.104:25620` |
| 3 | Material You, generated not imitated | ✅ COMPLETED | `m3/theme.ts` via `@material/material-color-utilities`; changing the source re-tints the whole tree |
| 4 | Dual desktop/mobile layouts | ✅ COMPLETED | `useIsCompact` (window) + `useIsMobileBuild` (binary), deliberately two questions |
| 5 | First-run setup + storage + battery | ✅ COMPLETED | SAF picker returns `/storage/emulated/0/Documents`, write-probed; battery dialog names the vendor |
| 6 | Native navigation | ✅ COMPLETED | System back walks the WebView history; navigation bar hides in sub-screens |
| 7 | The phone in the mesh, tested | ✅ COMPLETED | `scripts/e2e-android-mesh.sh` — asserts the phone's own `total_received_bytes` moves after joining the Linux mesh |
| [8](Task.5.md) | The services list the worker actually reads | 🚧 IN PROGRESS | Service-file path aligned; tracker stores follows dynamic Material 3 roles; P2P app-property handoff headless-green and M-NET-2 RETIRING; live re-read, Android restart, foreground service, and touch acceptance remain |

Task-file mapping: the eight rows above are the category's sub-deliverables. The deliverable task
files are [`Task.1`](Task.1.md) ✅ · [`Task.2`](Task.2.md) ✅ · [`Task.3`](Task.3.md) ✅ ·
[`Task.4`](Task.4.md) ✅ (2026-07-28) · [`Task.5`](Task.5.md) 🚧 (owns M-NET-1 … M-NET-4).

## 2. Milestone notes (newest first)

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
roles` verifies Vite's emitted selectors, not source strings. Task 5 deliverable 7 remains open until
both reused desktop screens receive physical touch acceptance.

### 2026-07-28 — Documentation sweep: Task 4 closed, Task 5 re-audited, M-9 filed

A full status reconciliation against the current tree.

* **Task 4 (settings + worker-verb census) → ✅ COMPLETED.** All four §Exit clauses are met and each
  is pinned by `cargo test -p nodera-app`: damaged-file preservation (`Stored { Document, Absent,
  Damaged }`), the metered-policy truth table (incl. metered-hotspot and unmetered-SIM), the
  worker-key census (`config.rs` asserts every key the app sends is one `applyConfig` recognises),
  and the no-private-address default. The §3 verb census is a living register of future work in the
  owning categories, not incomplete work here — the deliverable was the census itself.
* **Task 5 re-audited — still 🚧.** Deliverable 1 (services-list path) stays green
  (`NoderaWorker.kt:92-95`). M-NET-1 … M-NET-4 each re-verified against code: `SyncedServices.load`
  boot-only (`HeadlessPeerMain.java:103`); `envInt` getenv-only (`:630`); `restart_worker`'s
  consumer `daemon::supervise` is `#[cfg(desktop)]`; `minSdk = 24`
  (`gen/android/app/build.gradle.kts:22`) vs `--min-api 26` (`scripts/android-apk.sh:156`).
* **New limitation M-9** (build pipeline): the generated Gradle project now `compileSdk`/`targetSdk
  = 36` (`gen/android/app/build.gradle.kts:17,23`) but `scripts/android-toolchain.sh:33` installs
  only platform `android-34`. A host provisioned solely by the toolchain is one SDK-pull away from a
  failed build. Owned by [Task.1](Task.1.md).
* **Refactoring register added** ([`REFACTORING.md`](REFACTORING.md)): jscpd finds **0 clones** in
  `app/android/` (the Kotlin module is clean). jscpd does not scan shell, so the three
  `scripts/android-*.sh` were reviewed manually — they share logging helpers, version pins and the
  `NODERA_ROOT` preamble worth sourcing from one library; the top sequencing item (centralising the
  version pins) is also the elimination path for M-9.

No limitations retired this pass — every open M-* row was re-confirmed still live in code.

### 2026-07-27 — The services file the phone was told to read

The design names the synchronised services list as "the ONLY way the worker learns about a tracker
on Android". It had never once been read on a device: the Rust side writes
`/data/user/0/<pkg>/nodera/nodera-services.list` and `NoderaWorker.kt` pointed the worker at
`/data/user/0/<pkg>/files/nodera-services.list` — `filesDir` is one level below `app_data_dir()`,
and `config_dir()` appends `nodera/`. `api/storage.rs` already documents that exact trap for
`storage-pick.json`; the services list had no equivalent workaround. The network came up anyway
only because `network.default_trackers` also arrives over the live `NODERA-CONFIG` push.

The Kotlin side now derives the path the same way the Rust side does. Opened as
[`Task.5.md`](Task.5.md); the read-once behaviour, the `envInt` property gap, the no-op restart
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
