# App Task 9 — Tracker stores

<!-- AI-AGENT-INSTRUCTION: The rule this task exists to preserve: a deep link must NEVER add a store.
     It records the URL, the app shows it, the user decides. Any change that makes a link act by
     itself defeats the entire design and must be refused. Second rule: a store's endpoints are
     APPENDED after the user's own and never reorder them. Keep this header's status accurate. -->

**Status:** ✅ DONE
**Category:** app · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [app 8](Task.8.md), [tracker 6](../tracker/Task.6.md), [network 13](../network/Task.13.md)
**Consumed by:** [mobile 5](../mobile/Task.5.md)

---

## Goal

A user can trust a list of trackers and relays published by somebody other than us — added by
pasting a URL, or by following a button on a website — and the node uses it without any of it
becoming authority.

## Status detail

Landed 2026-07-27. `rust/nodera-app/src/stores.rs` with 13 tests; the screen serves both the desktop
rail and the mobile settings page; `nodera://tracker-store?url=…` is registered on desktop and on
Android. 172 `nodera-app` tests green — and now running in CI, which they never were.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | The index format, shared with `services/official.json` | ✅ |
| 2 | Fetch over HTTPS with a timeout, a redirect limit and a size cap | ✅ |
| 3 | Strict parsing; a future schema version refused, not guessed at | ✅ |
| 4 | Stores persisted in settings, one entry per URL | ✅ |
| 5 | Endpoints merged into what the worker gets, at spawn **and** live | ✅ |
| 6 | The store the app ships with, compiled in | ✅ |
| 7 | `nodera://tracker-store?url=…` on desktop and Android | ✅ |
| 8 | A confirmation dialog showing the URL before anything is fetched | ✅ |
| 9 | List / add / refresh / copy / delete, on both layouts | ✅ |
| 10 | A synchronisation file the worker reads, refreshed on a schedule | ✅ |

## Design

**One format, no privileged store.** The index a third party publishes is the same format as
`services/official.json`. The project's list is one store among however many a user adds, and is
deletable like any other — a built-in the user cannot remove would make the project the authority
this design spends its whole architecture avoiding.

**A link records; a person decides.** `nodera://tracker-store?url=…` stores the URL in one slot and
shows it. A page on any website can send someone here, and the decision being made is "I trust this
publisher". One slot rather than a queue: a stack of dialogs is a stack of things to dismiss, and the
user asked for the most recent one.

**The index is not signed, deliberately.** A signature is worth what its key distribution is worth,
and there is no trust root that would make one mean more than "this file came from whoever published
this file" — the app would end up pinning a key that certifies nothing. What carries the weight is
the explicit decision with the URL in front of them, and the fact that every service inside any store
still proves its own identity: directory rows are Ed25519-signed by the services and verified before
anything is dialled. The blast radius of a hostile store is wasted time.

**HTTPS only, refused before any request.** A store served over plaintext is a list anyone on the
path can rewrite, which quietly hands the user's trust decision to somebody else. Android release
builds block cleartext anyway; this makes desktop agree rather than differ silently.

**A store adds, it never displaces.** Merging puts the user's own entries first and appends what the
stores contribute, deduplicated. Peers re-rank everything by their own measurements anyway, so
position here is about provenance rather than preference — but a store that could reorder a
hand-typed endpoint would be a store overruling the person who added it.

**A failed refresh keeps yesterday's services**, with the error beside them rather than replacing
them. Losing every tracker because a web server had a bad minute is a far worse outcome than a
slightly stale list.

**An entry with a route the settings screen would reject is dropped.** A store must not be a way to
get a route into the configuration that a hand-typed one could not.

**This is the first honest default a handset has had.** `default_trackers()` is empty on Android on
purpose — `127.0.0.1` is the handset, and a baked LAN address is unreachable on every network but
one, so both options produce a permanently failing row that teaches the user to ignore red. A real,
reachable, publicly operated tracker in a list they can inspect is neither.

**The synchronisation file is what makes any of this work on a phone.** On the desktop the app
spawns the worker and hands it `NODERA_TRACKER_ENDPOINTS`. On Android it cannot — the worker is
loaded into the app's own process, and `NoderaWorker.kt` states the constraint: *"Environment
variables cannot be set for our own process from Java."* So before this, an Android install had no
channel to its worker at all: it fell back to `127.0.0.1:25600`, which on a handset is the handset,
and every store a user added stopped at the app.

The resolved list is therefore written to a file, which is the same split Mihon uses — the
*subscriptions* are settings the user owns, the *synced content* is refreshed on a schedule and
consumed by something else. A system property carries the path on Android (Java can set those for
itself); `NODERA_SERVICES_FILE` carries it on the desktop, so there is one mechanism rather than two
that diverge.

**The file is not JSON, and that is the point.** The worker's reader runs before anything else it
does. A malformed line must cost one endpoint, never the worker's ability to start — so every parse
failure is a skipped line and an unreadable file is the defaults plus a warning. Written
write-then-rename, because on Android the reader and the writer share a process.

**Six hours between syncs.** A service list changes when somebody opens a pull request against it,
which is not an hourly event; polling harder loads somebody's web server for nothing, and the manual
Refresh button covers a user who knows something changed.

**The merge feeds both push paths.** The spawn environment *and* the live `NODERA-CONFIG` push, so
adding a store takes effect on a running worker. If only one had it, adding a store would appear to
do nothing until a restart and the user would have no way to tell which they were looking at.

## Files

- `rust/nodera-app/src/stores.rs` — including `sync_file_body` / `write_sync_file`
- `rust/nodera-app/src/lib.rs` — `sync_stores_forever`
- `rust/nodera-app/android/kotlin/NoderaWorker.kt` — the Android path
- `java/worker/.../HeadlessPeerMain.java` — `SyncedServices`, the reader
- `rust/nodera-app/src/settings.rs` — `network.tracker_stores` + its enforcement row
- `rust/nodera-app/src/daemon.rs` · `src/config.rs` — the merge into both push paths
- `rust/nodera-app/src/lib.rs` — five commands, the deep-link handler, `PendingStore`
- `rust/nodera-app/ui/src/TrackerStores.tsx` — one screen, both layouts
- `rust/nodera-app/tauri.conf.json` · `capabilities/default.json`
- `scripts/android-apk.sh` — the manifest intent-filter

## Testing

```bash
cd rust/nodera-app && cargo test
cd rust/nodera-app/ui && bun run build
```

Decisive tests:

- `stores::tests::the_bundled_official_store_parses` — the list compiled into the binary is one this
  build can actually read. An unreadable built-in store is an empty screen with no explanation.
- `stores::tests::a_plaintext_store_is_refused_before_any_request_is_made`.
- `stores::tests::an_index_from_a_future_format_is_refused_rather_than_guessed_at`.
- `stores::tests::a_store_cannot_displace_an_endpoint_the_user_typed`.
- `stores::tests::an_entry_with_a_route_the_settings_screen_would_refuse_is_dropped`.
- `stores::tests::an_invitation_link_is_not_read_as_a_store` — `nodera:` already means "join this
  world"; reading one as a store would add a store from a link about something else.
- `stores::tests::a_malformed_escape_survives_into_the_dialog_rather_than_being_invented` — the user
  is shown this string; repairing it silently would show them one URL and use another.
- `config::tests::a_stores_trackers_are_pushed_to_a_running_worker`.
- `daemon::tests::a_store_adds_endpoints_without_displacing_the_users_own`.
- `stores::tests::the_sync_file_is_readable_by_a_parser_with_no_dependencies` and Java's
  `SyncedServicesTest.readsWhatTheAppWrote` — the same format, pinned once on each side of a
  boundary neither language can check for the other.
- `SyncedServicesTest.aMalformedLineIsSkipped` — a half-written cache file costs one endpoint.

## Acceptance criteria

1. ✅ A store can be added by pasting a URL and by following a link.
2. ✅ A link never adds anything on its own.
3. ✅ Store endpoints reach a running worker, not only the next one.
4. ✅ The built-in store is deletable.
5. ✅ A fresh install — including on a handset — has a reachable tracker.
6. ✅ Verified on a physical Android device (2026-07-27). The release APK installs, the `nodera`
   scheme is registered (`dumpsys package` reports the VIEW/BROWSABLE filter with `Scheme:
   "nodera"`), and a cold `nodera://tracker-store?url=…` intent starts `.MainActivity` — the app was
   not running beforehand, which is the case a listener registered after window creation would miss.
   What a release build does **not** allow is reading the app's own storage (`run-as` is denied), so
   the synchronisation file's contents and the confirm dialog were not inspected on-device; both are
   covered by unit tests on each side of the boundary.

## Limitations

Owns none. Criterion 6 is unverified rather than broken: the manifest patch is asserted by running it
over a realistic manifest and checking idempotency and XML validity, but no one has yet tapped a
`nodera://` link on a handset.
