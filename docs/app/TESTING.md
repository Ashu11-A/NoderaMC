# App — Testing

<!-- AI-AGENT-INSTRUCTION: This crate is WORKSPACE-EXCLUDED: `cd rust && cargo test` does NOT cover
     it. Never present the green workspace Rust gate as covering the app. Run it explicitly. The
     dashboard's data path is asserted on the JAVA gate (the worker's STATE verb) — keep it that way:
     no logic in the UI worth testing beyond parsing. Keep counts and Last run current. -->

**Category:** app · **Last run:** 2026-07-25 · **61 tests · 0 failing**

```bash
cd rust/nodera-app && cargo test        # REQUIRED — the workspace gate does not cover this crate
cd rust && cargo test                   # does NOT include nodera-app
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
