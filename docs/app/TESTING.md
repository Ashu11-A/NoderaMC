# App — Testing

<!-- AI-AGENT-INSTRUCTION: This crate is WORKSPACE-EXCLUDED: `cd rust && cargo test` does NOT cover
     it. Never present the green workspace Rust gate as covering the app. Run it explicitly. The
     dashboard's data path is asserted on the JAVA gate (the worker's STATE verb) — keep it that way:
     no logic in the UI worth testing beyond parsing. Keep counts and Last run current. -->

**Category:** app · **Last run:** 2026-08-01 · **Last audit:** 2026-08-01 ·
**303 tests** (270 shared core + 2 shell, one intentionally ignored + 31 frontend): 302 passed, 1 ignored

```bash
cd app && cargo test        # REQUIRED — root workspace does not cover the Tauri shell
cargo test                  # includes library/rust/nodera-core
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

### Test counts (run 2026-08-01)

A run count, not a source estimate. Shared behavior moved to root-workspace `nodera-core`; the Tauri
shell now has only platform integration tests. Frontend tests run after production Vite output exists.

| Suite | Tests |
|---|---:|
| `library/rust/nodera-core` | 270 |
| `app` Tauri shell | 2 (1 ignored: opens a real browser) |
| `app/ui/tests` | 31 |
| **Total** | **303** |

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
