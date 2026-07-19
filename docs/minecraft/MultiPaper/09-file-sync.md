# 09 — File & Data Synchronization

MultiPaper doesn't share a filesystem between servers. Instead, every server
that joins a cluster:

1. **Pulls** the canonical versions of the files it needs from the master at
   startup.
2. **Pushes** its local changes back to the master when files change.
3. **Receives** real-time file pushes from the master when other servers
   modify them.

This is implemented by `MultiPaperFileSyncer`
(`patches/server/0088-Add-generic-file-syncing-between-servers.patch:125`),
plus the master-side `handlers/UploadFileHandler.java`,
`handlers/DownloadFileHandler.java`, `handlers/RequestFilesToSyncHandler.java`.

In addition, MultiPaper exposes a simple **key-value data store** for plugins
to share structured data without running their own database.

---

## File syncer design

`MultiPaperFileSyncer` (`0088:128+`) is the central class. Lifecycle:

### `init()` (`:156-167`)

On startup, the server sends `RequestFilesToSyncMessage` to the master. The
master replies with `FilesToSyncMessage` — a list of `FileToSync(path,
lastModified)` entries from `synced-server-files/`. The server compares each
entry against its local copy:

- If the master's `lastModified` is **newer**, the server downloads the file
  via `DownloadFileMessage` (`handlers/DownloadFileHandler.java:15`).
- If the server's local copy is **newer**, the server uploads it via
  `UploadFileMessage` (`handlers/UploadFileHandler.java:16`).
- If they're equal, no transfer.

After the initial sync, the file watcher thread starts.

### File watcher thread (`run()`, `:223-251`)

Uses Marc Methvin's `DirectoryWatcher` (a cross-platform watch service that
falls back to polling on filesystems without native notifications) to watch
`.` for `ENTRY_CREATE` / `ENTRY_MODIFY` events. When a file changes:

1. Match the path against `filesToSyncInRealTime`, `filesToSyncOnStartup`,
   and `filesToNotSync` (see below for what these mean).
2. If it should be synced, call `queueFileUpload(path)`.
3. `queueFileUpload` deflates the file, opens a `DataStreamMessageReply` over
   the master connection, and sends `UploadFileMessage` with the content.

To avoid infinite loops (server A writes → uploads → master pushes to server
B → B writes → uploads back to A → …), `pathsBeingModified` records paths
that are being written **because** of an incoming sync; the watcher ignores
those events for a short window.

`UploadFileMessage.immediatelySyncToOtherServers` tells the master to push the
file to every other server immediately via `FileContentMessage`, rather than
waiting for them to ask.

### `writeOnServerStop` (`0088:65+`)

Some files (typically large databases like a SQLite or H2 file) are too hot
to upload in real time. List them under `files-to-only-upload-on-server-stop`
and they will only be uploaded at server shutdown.

---

## Configuration (in `multipaper.yml`)

All keys are under `sync-settings.files` (see
[11 — Configuration](./11-configuration.md)):

| Key | Purpose |
|---|---|
| `files-to-sync-on-startup` | List of paths/directories to sync **once** at startup. Directories are recursive. Last writer wins. **Deletion is not synced.** |
| `files-to-sync-in-real-time` | Same, but changes are uploaded as they happen and pushed to peers. Useful for plugin configs that change at runtime. **Cannot** be used with SQLite/H2 (those files are not safely syncable this way). |
| `files-to-not-sync` | Exclusion list, takes precedence over the include lists. |
| `files-to-only-upload-on-server-stop` | Big files that are uploaded once on shutdown instead of on every write. |
| `log-file-syncs` | Print a log line for every file synced. |

Example:

```yaml
sync-settings:
  files:
    files-to-sync-on-startup:
      - "plugins/MyPlugin/config.yml"
      - "plugins/Essentials/userdata"
    files-to-sync-in-real-time:
      - "plugins/MyPlugin/dynamic.yml"
    files-to-only-upload-on-server-stop:
      - "plugins/MyPlugin/data.db"
    files-to-not-sync:
      - "logs"
      - "plugins/MyPlugin/cache"
    log-file-syncs: false
```

---

## How the master stores synced files

Synced files live under `synced-server-files/` on the master, mirroring the
relative path of the file as the server saw it. The master does **not**
interpret the contents — it just stores them as blobs and serves them back.

The master also tracks `lastModified` for each file so that conflicts can be
resolved with "last writer wins". If two servers write to the same file at
the same time, the master simply accepts whichever arrives last; the other
server's version is lost.

This means **MultiPaper's file sync is not a database**. It is a
best-effort shared filesystem for plugins that don't have their own
synchronisation strategy. For real shared state, use a database (see
[13 — Plugin Development](./13-plugin-development.md)).

---

## Disabling the watcher

Some deployments don't want the watcher running (e.g. on a server that
shouldn't be writing files back). Two escape hatches:

- `-DdisableFileWatching=true` JVM property — turns the watcher off entirely.
- `-Dmultipaper.sync-settings.files.files-to-sync-on-startup="a;b;c"` —
  semicolon-separated list as a JVM property, useful for templating many
  identical servers.

---

## Data storage KV (`CallDataStorage`)

For plugins that want to share small amounts of structured data without
running their own database, MultiPaper provides a tiny **key-value store**
hosted on the master. The store is backed by `datastorage.yml` on disk and
saved atomically every 15 s and on shutdown.

Operations (see `MultiPaperDataStorage` API,
`patches/api/0006`):

| Op | Behaviour |
|---|---|
| `get(key)` | Returns the value as a `String`, or null. |
| `set(key, value)` | Sets a value. Overwrites existing. |
| `add(key, delta)` | Atomic integer add (returns the new value). |
| `list(prefix)` | Returns `Map<String, String>` of all keys with the given prefix. |

Master side: `handlers/CallDataStorageHandler.java:21`.

Replies use the generic message types:
`NullableStringMessageReply` (for `get`),
`IntegerPairMessageReply` (for `add` — the new value),
`KeyValueStringMapMessageReply` (for `list`),
`BooleanMessageReply` (for `set`).

In addition, when **any** server writes a key, the master broadcasts a
`DataUpdateMessage` to all other servers, so subscribers to that key can
refresh their cache. The Bukkit API exposes this via
`MultiPaperNotificationManager` (`patches/api/0001:106`), a pub/sub layer.

### Limitations

- **Size.** The store is loaded into memory on the master and saved as a YAML
  file. It is suitable for kilobytes-to-low-megabytes of data, not for
  player data or anything large.
- **Atomicity.** `add` is atomic; `set` is atomic per-key. There is no
  multi-key transaction.
- **Throughput.** Every write triggers a 15-second save timer and a broadcast
  to all servers. Don't use it as a high-frequency data path.

For real plugin data, use MySQL/PostgreSQL/Mongo — see
[13 — Plugin Development](./13-plugin-development.md).

---

## JSON file sync

`sync-json-files: true` (default) syncs the special JSON files that
CraftBukkit maintains in every world's root:

- `ops.json` — server operators.
- `whitelist.json` — whitelisted players.
- `banned-players.json` — banned players.
- `banned-ips.json` — banned IPs.

These are synced via `ReadJsonMessage` / `WriteJsonMessage`
(`handlers/ReadJsonHandler.java`, `handlers/WriteJsonHandler.java`) so that an
op or ban applied on one server takes effect across the cluster immediately.

---

## Other synced state (not files)

For completeness, the following are **not** file-synced but flow through the
master and are worth knowing about (see
[07 — Entity & Player Sync](./07-entity-player-sync.md)):

- Player `.dat` files (`ReadPlayer` / `WritePlayer`) — always go through the
  master so the player's state is consistent across servers.
- `level.dat` (`ReadLevel` / `WriteLevel`) — same.
- World `session.lock` / UUID (`ReadUid` / `WriteUid`).
- Player statistics (`ReadStats` / `WriteStats`).
- Player advancements (`ReadAdvancements` / `WriteAdvancements`).

These are not under the file-syncer's control — they're hard-coded by the
patches because they have specific semantics (e.g. last-writer-wins would be
wrong for player data).

---

## Next

Continue to [10 — Proxy & Load Balancing](./10-proxy-load-balancing.md).
