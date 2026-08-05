# Frontend Task 15 — The settings the app can actually keep, and the worker verbs it never asks for

<!-- AI-AGENT-INSTRUCTION: §3 is a census, not a wish list. Every row names a verb that EXISTS in
     `ControlProtocol.java` and is dispatched by `ControlServer.java` today. Before moving a row to
     ✅, re-read the handler — a verb that is declared and dispatched can still be unimplemented
     (NODERA-STATUS is exactly that, and its row says so). Never add a row for a verb that does not
     exist yet; that belongs in the owning category's task file, not here. -->

**Status:** ✅ COMPLETED
**Category:** frontend · **Owns:** `app/src/android/network.rs`, the settings document
**Last audit:** 2026-07-28
**Depends on:** [frontend 13](Task.13.md), [frontend 14](Task.14.md), [frontend 7](Task.7.md)
**Consumed by:** —

---

## Goal

Two things the companion app could not do, and one census.

1. **Keep a setting.** The settings document had three ways to lose itself, and all three were live.
2. **Refuse to spend somebody's data allowance without being asked.** A seeding node moves as many
   bytes as it can, by design. On a phone that is a bill.
3. **Say what the worker offers that the app never asks for**, so the gap is a decision rather than
   an accident.

## Status detail

Completed at the 2026-07-28 audit. All four §Exit clauses are met and each is pinned by a test in
`cargo test -p nodera-app`:

* the damaged-file path is preserved as `settings.json.bad` and the first save is refused
  (`Stored { Document, Absent, Damaged }` replaces the `.ok()` chain);
* the metered-policy truth table — including the metered-hotspot and unmetered-SIM edge cases — is
  asserted, and a phone on mobile data reports `transfers_paused` to its worker;
* the worker-key census is asserted: every key the app sends is one the worker's `applyConfig`
  recognises (the two replication keys and `network.rendezvous_endpoints` are now settable);
* no private address appears in `Settings::default()` — asserted by a test, not by review.

§3 remains a **living census**: it documents the verbs the worker exposes that the app does not yet
call. Those rows are deliberate future work in the owning categories, not incomplete work in this
task — the deliverable was the *census itself*, and it exists.

---

## 1. Configuration storage — what was wrong

| # | Defect | Symptom the user saw |
|---|---|---|
| 1 | `SettingsHandle::load()` runs before Android's data directory is known, and Tauri builds the webview **before** the `setup` hook that injects it. A command landing in that window read defaults, and the first-run flow's `snapshot() → save()` wrote them over the real file. | Setup reappeared; the storage choice did not stick. |
| 2 | `read_to_string(...).ok().and_then(|r| from_str(r).ok())` — a **damaged** file was indistinguishable from a **missing** one, and the next save made the loss permanent. | One bad byte silently cost every setting. |
| 3 | `write` + `rename` with no `sync_all`. The rename is ordered; the temp file's contents are not. | A power loss produced a zero-length `settings.json`, i.e. defect 2. |
| 4 | A private LAN address (`10.0.0.101`) shipped as a **default tracker** on every platform, and as the *only* one on mobile. | "No trackers are answering" on any other network — indistinguishable from a broken app. |

**Fixes.** `Stored { Document, Absent, Damaged }` replaces the `.ok()` chain; a damaged file is
reported through the new `settings_fault` command, kept as `settings.json.bad`, and the first save
is **refused** rather than allowed to destroy it. `snapshot()` re-reads whenever the document has
never loaded, which closes the ordering race from whichever side wins. `save()` fsyncs the temp file
before the rename. `default_trackers()` ships loopback on desktop and **nothing** on mobile, with
`option_env!("NODERA_DEFAULT_TRACKERS")` baked in by `scripts/android-apk.sh` from the build host's
own address so a development APK still comes up pointed at the laptop that built it.

## 2. Connection policy — new

`network.transfer_network` ∈ `any` | `unmetered_only` | `wifi_only`, defaulting to **`wifi_only` on
a handset** and `any` on a desktop.

`unmetered_only` defers to Android's `NET_CAPABILITY_NOT_METERED`, which is the user's *own* system
setting — so an unlimited SIM marked unmetered counts, and a capped Wi-Fi hotspot does not.
`wifi_only` is the literal reading and ignores metering, because some people want the rule and not
the nuance. Both are decided in the app and reach the worker as the same `behavior.transfers_paused`
flag the battery rules use; the worker is never told what kind of link it is on, because it has no
way to know and no business deciding.

Failure is **permissive**: an unreadable connection state permits transfers and is surfaced with its
error. Pausing on ignorance presents to the user as "Nodera stopped working" with nothing on screen
to explain it.

`ACCESS_NETWORK_STATE` is injected into the manifest by `scripts/android-apk.sh`. Without it
`ConnectivityManager` answers nothing and the rule silently never fires.

## 3. The worker's verbs, and which the app asks for

29 verbs are declared in `ControlProtocol.java` and dispatched by `ControlServer.java`. The app
calls **14**.

### Called today

`NODERA-STATE` · `NODERA-WATCH` · `NODERA-EVENTS` · `NODERA-PIECES` · `NODERA-CONFIG` (set) ·
`NODERA-LAN` · `NODERA-DIRECTORY` · `NODERA-CONNECT` · `NODERA-DISCONNECT` · `NODERA-SHARELINK` ·
`NODERA-PROVE` · `NODERA-DELETE` · `NODERA-TELEMETRY GET` · `NODERA-TELEMETRY SET`

### Available and unused — the plan

| Verb | What it would give the user | Value | Cost |
|---|---|---|---|
| `NODERA-WORLDS` | The **durable** library — worlds this node has shared or supports — answerable with the game closed, the mesh empty and every tracker down. The app reads `STATE.connected_worlds`, which is a *live* view, so an idle node shows an empty Worlds tab and looks like it lost them. | ★★★ | S |
| `NODERA-CONFIG` (read-back) | What is **actually in force**, including values the worker clamped. Today the app only pushes, so a clamped value is never reflected back into the screen that set it. | ★★★ | S |
| `NODERA-IDENTITY` | This node's own id and public key, copyable — for support, invitations and grants. Currently learnt only indirectly from `STATE`. | ★★ | S |
| `NODERA-PROBE` | An explicit worker/protocol handshake, so a version mismatch can be *named* instead of appearing as "offline". | ★★ | S |
| `NODERA-GRANT` | A permissions screen: grant a role to a peer by node id. Entirely absent from the app. | ★★★ | M |
| `NODERA-REKEY` | "Change this world's password" — re-encrypt, re-sign, re-announce. | ★★ | M |
| `NODERA-HOST` / `NODERA-STOP` | Start or stop sharing a world **with the game closed**. Today only the mod can. | ★★★ | M |
| `NODERA-ARCHIVE` | "Download this world" — fetch and verify an archive to disk without Minecraft. | ★★ | M |
| `NODERA-SEED` / `NODERA-SEED-REGION` | Re-publish an archive, or pin a committed region, on demand. | ★ | M |
| `NODERA-WORLDID` | Create a world identity from the app — an app-side "new shared world". | ★ | L |
| `NODERA-MESH` | Ask this worker to join a session as a real member, and explain a DEGRADED one. | ★★ | L |
| `NODERA-JOIN` | Join by world id, as distinct from `CONNECT`, which only tunnels a live session. | ★ | M |
| `NODERA-TELEMETRY EVENT` | Let the app report through the worker's single consent gate, as the mod does. | ★ | S |
| `NODERA-STATUS` | **Dead on both sides.** Declared, dispatched, and never overridden — `ControlHandler` returns `{}`. Either implement it in the worker or delete the verb; a verb that always answers `{}` is worse than one that does not exist. | — | — |

### `NODERA-CONFIG` key drift, both directions

The worker accepted two keys nobody sent: **`storage.replication_budget_bytes`** and
**`storage.replication_sweep_seconds`** — how much disk this node spends on other people's worlds,
and how often it re-checks. On a phone that is not a detail. Both are now in the settings document,
in `WorkerConfig`, in `ENFORCEMENT`, and on both settings screens.
`network.rendezvous_endpoints` is likewise now settable; it had no path but the spawn environment,
which a phone — whose worker is started by the Activity — cannot write.

In the other direction, `storage.peer_worlds_dir` was recorded as `AtRestart` while the worker has
applied it **live** since L-58. The badge rendered correctly by accident, because `applied` is
checked before the table; the table is now right for the next person who reads it.

`config.rs` gained a test asserting every key it sends is one the worker's `applyConfig` recognises.
Nothing pinned that before, which is how the two replication keys went missing unnoticed.

---

## Files

| Path | Role |
|---|---|
| `app/src/settings.rs` | `Stored`/`SettingsHandle`, damaged-file handling, `default_trackers()` |
| `app/src/config.rs` | `WorkerConfig`, `ENFORCEMENT`, the worker-key census test |
| `app/src/android/network.rs` | `transfer_network` policy + `NET_CAPABILITY_NOT_METERED` |
| `scripts/android-apk.sh` | bakes in `NODERA_DEFAULT_TRACKERS`, injects `ACCESS_NETWORK_STATE` |

## Testing

`cargo test -p nodera-app` covers: the damaged-file path, the post-load recovery, the policy truth
table (including the metered-hotspot and unmetered-SIM cases), and the worker-key census. The
no-private-address default is asserted there too.

## Acceptance criteria

- [x] A settings file that is damaged mid-session is **preserved**, reported, and not overwritten.
- [x] A phone on mobile data with the default policy reports `transfers_paused` to its worker, and
      the Node screen says **why**.
- [x] `cargo test -p nodera-app` covers: the damaged-file path, the post-load recovery, the policy
      truth table (including the metered-hotspot and unmetered-SIM cases), and the worker-key census.
- [x] No private address appears in `Settings::default()` — asserted by a test, not by review.

## Not done here, and why

**The Rust discovery peer is still not spawned on Android.** It would be a *second* node: the real
Java worker already runs in-process, announces with real routes, and is what
`scripts/e2e-android-mesh.sh` measures. Spawning the Rust peer as well would put a second identity
on the tracker announcing an address nothing listens on. Its correctness bugs are fixed (it resolves
every address rather than the first, queries every tracker that accepted, and no longer dials
`udp://` over TCP) because `peer_self_test` is a genuine diagnostic — but it stays a diagnostic.
