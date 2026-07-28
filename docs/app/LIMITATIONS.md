# App — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the app category. "Permanent" is banned. Every §B row has an
     owning task and an EXIT TEST. Never delete a row — move it to LIMITATIONS.fixed.md with its
     evidence. L-56 is the register's own precedent for the rule "badge honestly rather than delete":
     removing an unsupportable setting would silently drop values users already saved AND hide that
     the limitation is known. -->

**Category:** app · **Last audit:** 2026-07-27 · Open or retiring rows: **7**

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
| L-47 | There is no automated **installer-based, cross-machine** acceptance. The halves that exist are green in CI: a companion job builds the app end to end, runs the gate both ways (worker present ⇒ Minecraft starts; absent ⇒ an actionable abort), and proves hosted-world survival with a real daemon and a SIGKILLed stand-in game process. What is missing is the sentence a player would actually say — install the app, host a world, close Minecraft, and have it still be listed **and joinable from a second, separately networked side** | [4](Task.4.md) | A CI job installs the app, verifies the gate both ways, hosts a world, closes Minecraft, and joins that world from a second machine or separately networked instance | OPEN |
| L-56 | **Two connection settings can never be honoured as specified.** Settings → Network exposes a per-world connection cap and an unlimited-connections-only filter. Neither is implementable against the architecture as it stands, and both are **kept in the UI on purpose**, permanently badged with the worker's own reason rather than deleted — removing them would silently drop settings users already saved and would hide that the limitation is known. *Per-world caps:* the socket transport has no world dimension at all — a socket is not owned by a world — so making it real needs either a world dimension on the transport or a world→manifest accounting index, i.e. an architectural change rather than a settings change. *Unlimited-connections-only:* no peer advertises a connection cap anywhere on the wire, so there is nothing to filter peers on; it would need a new field in the frozen membership family, mirrored in the Rust codec. The worker returns both as `rejected` with these reasons and the app renders a distinct muted "not supported" badge — deliberately **not** the amber "not enforced yet", which would imply the feature is coming | [3](Task.3.md) | Either the transport gains per-world attribution and a peer-advertised cap field, or both controls are removed from the UI **with their saved values migrated** | OPEN |
| A-UX-1 | When the link to the worker is down, only the top bar changes: every screen keeps rendering its numbers unmarked, because they all gate on `link.has_data`, which stays true. Keeping the last picture is deliberate; failing to say it is the last picture is not | [10](Task.10.md) | With the worker stopped, every screen marks its figures as last-known rather than current | OPEN |
| A-UX-2 | `appearance.notifications` reports itself as in force and has no read site — no consumer, no `tauri-plugin-notification`, no capability entry. `Enforcement::Local` resolves to `Live` unconditionally | [10](Task.10.md) | The setting either raises a notification or is declared unenforced | OPEN |
| A-UX-3 | Saving a restart-scoped setting tells the user to restart the worker themselves, while the app already owns the restart signal | [10](Task.10.md) | The banner restarts the worker, or explains why it cannot in this mode | OPEN |
| A-UX-4 | The tracker-stores screen uses Tailwind colour tokens that are not generated (`text-fg`, `bg-accent`, `text-warning`, …) and has no page padding, so it renders unstyled and flush to the window edge | [10](Task.10.md) | The screen uses defined tokens and matches the padding of every other screen | OPEN |
| A-UX-5 | Six registered commands have no desktop caller: `settings_fault`, `pause_reason`, `dashboard_world`, `open_share_file`, `get_unenforced_settings`, and the `nodera://pause` event | [10](Task.10.md) | Each has a caller or is deleted | OPEN |

---

## §C — Known trade-offs (recorded, owned, not hidden)

<!-- AI-AGENT-INSTRUCTION: These are accepted costs with named owners, not §B rows. Do not present the
     green workspace Rust gate as covering this crate while the first row stands. -->

| Trade-off | Consequence | Owner |
|---|---|---|
| The crate is **workspace-excluded** from the headless `cargo test` gate (Tauri's native webkit dependencies would burden every unrelated Rust change) | CI does not compile the app until the dedicated build job covers every target. **Never read the green workspace Rust gate as covering this crate.** Its tests run via `cd rust/nodera-app && cargo test` | [3](Task.3.md) |
| The app supervises a **Java** worker | Deliberate: Option B is locked by the single-engine rule ([`../worker/Task.0.md`](../worker/Task.0.md)). A Rust-native lightweight seeder mode remains a possible later addition, never a validator | [`../worker/`](../worker/Task.0.md) |
| The app is not a peer | It holds no signing keys and serves nothing to the network. Any behaviour that touches the network belongs in the worker or the Rust services — if you are adding peer logic here, stop | [1](Task.1.md) |

---

## Reading guide for the implementing model

- **Badge honestly rather than delete.** L-56 is the precedent: a control the node cannot honour keeps
  its saved value and gains a reason, because deleting it would both discard user data and conceal a
  known gap.
- **Do not put rules in the UI.** If a panel needs a decision, the decision belongs in the worker where
  it can be asserted headlessly on the Java gate.
- **Per-platform behaviour gets per-platform acceptance.** One OS passing is not evidence about
  another.
