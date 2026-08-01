# App — Testing

<!-- AI-AGENT-INSTRUCTION: This crate is WORKSPACE-EXCLUDED: `cd rust && cargo test` does NOT cover
     it. Never present the green workspace Rust gate as covering the app. Run it explicitly. The
     dashboard's data path is asserted on the JAVA gate (the worker's STATE verb) — keep it that way:
     no logic in the UI worth testing beyond parsing. Keep counts and Last run current. -->

**Category:** app · **Last run:** 2026-07-28 · **Last audit:** 2026-07-28 ·
**193 tests** (187 Rust + 6 frontend), all re-run

```bash
cd app && cargo test        # REQUIRED — the workspace gate does not cover this crate
cd rust && cargo test                   # does NOT include nodera-app
cd app/ui && bun run test   # type-check + bundle + emitted-CSS contracts
cd app/ui && bun run build  # same production gate used by CI
```

---

## 1. The testing strategy

The app is deliberately the thinnest layer in the project, and its test strategy follows from that:

| Concern | Where it is tested | Why |
|---|---|---|
| The **numbers** on the dashboard | The Java gate, via the worker's `STATE` verb | They are produced by the worker; asserting them here would test the wrong component |
| **Parsing** those numbers | Here | Byte order, tolerance, and error surfacing are genuinely the app's responsibility |
| **Supervisor lifecycle** | Here | Spawn, attach, stop — including the rule that quitting never kills a worker the app did not spawn |
| **Packaging and install** | CI ([`Task.3.md`](Task.3.md)) | Only a clean-checkout build catches gitignored build inputs |
| **The product claim** | CI ([`Task.4.md`](Task.4.md)) | Install → gate both ways → host → close Minecraft → still joinable |
| **Generated UI roles and shell padding** | Post-build Node regression over Vite's emitted CSS | Tailwind silently omits unknown utilities; mobile must resolve through dynamic Material 3 roles; desktop and mobile intentionally frame the shared component differently |

## 2. Crate coverage

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

### Test counts (run 2026-07-28)

A count of `#[test]` / `#[tokio::test]` attributes in `app/src/`, plus the frontend's
Node tests, re-run after the UX honesty bundle (issue #98). The Rust total is unchanged at 187: two
tests for deleted code went with it (`the_unenforced_shim_lists_exactly_the_non_live_keys` and the
invitation-file round trip, whose reader half was an uncalled command), one direct assertion on the
writer replaced them, and the notifications enforcement test is new. The frontend gained five exit
tests for A-UX-1/2/3/5, including one that walks all 47 registered Tauri commands and fails on any
without a caller: **193 total.**

| Module | Tests |
|---|---:|
| `settings` | 24 |
| `api::store` | 13 |
| `api::model` | 15 |
| `stores` | 15 |
| `api::link` | 12 |
| `daemon` | 14 |
| `config` | 10 |
| `power` | 10 |
| `metrics` | 8 |
| `peer::tracker` | 8 |
| `android::network` | 7 |
| `android::worker` | 2 |
| `telemetry` | 7 |
| `api::modinstall` | 6 |
| `api::network` | 5 |
| `api::events` | 5 |
| `control` | 5 |
| `peer::identity` | 5 |
| `api::about` | 4 |
| `api::storage` | 4 |
| `android::battery` | 2 |
| `api::commands` | 2 |
| `logs` | 2 |
| `system` | 2 |
| `ui/tests/tracker-stores-style.test.mjs` | 1 |
| `ui/tests/ux-honesty.test.mjs` | 5 |
| **Total** | **193** |

## 3. Manual smoke, per increment

```bash
scripts/dev.sh --with-app      # attach mode against an externally started worker
```

Checks:

1. The dashboard shows live data, not zeros.
2. Quitting the app leaves the externally started worker **running** (attach semantics).
3. Stopping the worker makes the tray show offline and the dashboard show the daemon down — with no
   crash loop.

## 4. Conventions

- **Never claim the workspace gate covers this crate.** It does not, and saying so would hide a real
  coverage gap that [`Task.3.md`](Task.3.md) exists to close.
- **No logic in the UI worth testing beyond parsing.** If a panel needs a rule, put the rule in the
  worker where it is asserted headlessly.
- **Three protocol mirrors, one commit.** A control-protocol change lands in the worker's
  `ControlProtocol`, the mod's `CompanionProtocol`, and this crate's `control.rs` together, with the
  version bumped — the mod's gate then classifies skew as "update the app" or "update the mod".
- **Per-platform behaviour gets per-platform acceptance.** One OS passing is not evidence about
  another.

## 5. CI

The companion job builds the app end to end from a clean checkout (UI build plus cargo release, with
the worker distribution staged and bundled) and runs the gate in both directions. Its clean-checkout
property is the point: the four packaging gaps it found on its first run were all files that worked
locally and were gitignored.

## The launch lane

`nodera-core/src/launch/` is unit-tested against fixtures — synthetic `versions/*.json` files written
into a temp directory — so the parsers run on a machine with no Minecraft installed. That covers what
actually breaks in a launcher: `inheritsFrom` resolution, classpath ordering, natives never reaching
the classpath, the placeholder table, and the `has_quick_plays_support` gate that decides whether the
player lands in the world or in the Multiplayer menu.

What it cannot cover is a real game starting. That is a human protocol:

1. Two sides. Two machines, or `scripts/dev.sh --play --with-app` with `NODERA_APP_MULTI=1`.
2. Host: open a world to LAN from inside Minecraft, then Share when the app asks.
3. Guest: **Discover** → the world appears → press **Play**.
4. Watch all four, not one:
   - the `nodera://launch` phases arrive in order — `resolving`, `joining`, `preparing`, `spawning`,
     `running`;
   - `ss -ltnp` shows the loopback port bound between `joining` and `preparing`;
   - `ps -ef | grep quickPlayMultiplayer` shows the flag **and the port matches**;
   - the game window opens straight into the world, with no Multiplayer menu.
5. Quit the game. `exited` fires, the tunnel is closed, and `ss` shows the port gone. This last step
   is the one that regressed before the lane existed: the old Join screen opened a tunnel and never
   closed it.

Each failure path is scriptable through `NODERA_MINECRAFT_DIR`, and each must produce its own remedy
button rather than a generic error:

| Break this | Expect |
|---|---|
| rename the Nodera jar out of `mods/` | `install-mod` |
| point `NODERA_MINECRAFT_DIR` at an empty directory | `pick-install` |
| point `JAVA_HOME` at a Java 17 | `install-java`, naming the version the profile requires |

## The Android front end

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
