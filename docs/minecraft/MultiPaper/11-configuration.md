# 11 — Configuration Reference

This is the complete reference for MultiPaper's runtime configuration. There
are three layers:

1. **`multipaper.yml`** — generated in each MultiPaper server's working
   directory on first run. Schema defined by `MultiPaperConfiguration`
   (`patches/server/0003:122-216`) using Paper's `ConfigurationPart` +
   Sponge Configurate.
2. **JVM properties** — every `multipaper.yml` key can be overridden with a
   `-D` flag, plus a handful of keys that are **only** JVM properties.
3. **Master CLI** — `multipaper-master.jar [address:]port [proxyPort]`, plus
   a few `-D` knobs on the master side.

---

## `multipaper.yml` schema

```yaml
master-connection:
  my-name: server1
  master-address: localhost:35353
  advertise-to-built-in-proxy: true

peer-connection:
  compression-threshold: 1024
  consolidation-delay: 2

optimizations:
  disable-safety-redstone-chunk-lock: false
  dont-save-just-for-lighting-updates: true
  max-footstep-packets-sent-per-player: -1
  reduce-player-position-updates-in-unloaded-chunks: false
  ticks-per-inactive-entity-tracking: 1
  use-event-based-io: true

sync-settings:
  sync-entity-ids: true
  sync-json-files: true
  sync-permissions: true
  sync-scoreboards: false
  use-local-player-count-for-server-is-full-kick: false
  persistent-player-entity-ids: false
  persistent-vehicle-entity-ids-seconds: 15

  files:
    files-to-not-sync: []
    files-to-only-upload-on-server-stop: []
    files-to-sync-in-real-time: []
    files-to-sync-on-startup: []
    log-file-syncs: false
```

---

### `master-connection`

| Key | Default | Description |
|---|---|---|
| `my-name` | `server<random>` | This server's name in the cluster. Should match the BungeeCord/Velocity server name if you're using a proxy. Must be **unique** in the cluster. |
| `master-address` | `localhost:35353` | Master's `host:port`. **Must be identical on every server** in the cluster — you cannot use `127.0.0.1` on one server and `192.168.0.5` on another; both must use the same address. |
| `advertise-to-built-in-proxy` | `true` | Whether players may join this server via the master's built-in proxy. |

---

### `peer-connection`

| Key | Default | Description |
|---|---|---|
| `compression-threshold` | `1024` | Bytes before zlib compression kicks in for P2P packets. `0` disables compression. Disabled automatically on loopback. Recommended value when compression is on: 1024. |
| `consolidation-delay` | `2` | Milliseconds that `PacketConsolidationHandler` batches P2P writes for, so multiple packets get compressed together. `0` disables batching. Recommended value when compression is on: 2 ms. |

---

### `optimizations`

| Key | Default | Description |
|---|---|---|
| `disable-safety-redstone-chunk-lock` | `false` | Disables the atomic ownership takeover for redstone (see [08 — Redstone Safety](./08-redstone-safety.md)). **Will break cross-server redstone.** |
| `dont-save-just-for-lighting-updates` | `true` | Avoids writing chunks to disk when only lighting has changed, which vanilla does a lot on chunk load. |
| `max-footstep-packets-sent-per-player` | `-1` | Cap on the number of nearby players who receive a player's footstep packets. Useful for very crowded events. `-1` = unlimited. |
| `reduce-player-position-updates-in-unloaded-chunks` | `false` | When `true`, external players' positions are not broadcast to servers that don't have the player's chunk loaded. Saves bandwidth; trades off `Player.getLocation()` accuracy for foreign players. |
| `ticks-per-inactive-entity-tracking` | `1` | How often to track inactive entities (entities outside simulation distance). `1` = vanilla. Higher = less CPU, slightly delayed despawn/respawn. |
| `use-event-based-io` | `true` | Use the async event-loop chunk IO engine (`MultiPaperIO`) instead of Paper's single-thread `RegionFileIOThread`. **Strongly recommended** — without it, one slow peer can stall all chunk reads. |

---

### `sync-settings`

| Key | Default | Description |
|---|---|---|
| `sync-entity-ids` | `true` | Allocate entity IDs in 4096-block chunks from the master so IDs are globally unique across servers. Needed for plugins that fake entity-ID packets. |
| `sync-json-files` | `true` | Sync `ops.json`, `whitelist.json`, `banned-players.json`, `banned-ips.json`. |
| `sync-permissions` | `false` | Sync Bukkit permission attachments between servers. **Resource-intensive** at high player counts; turn off if you don't need it or your permissions plugin already syncs. |
| `sync-scoreboards` | `false` | Sync scoreboard and team data. |
| `use-local-player-count-for-server-is-full-kick` | `false` | `false` = kick based on **cluster-wide** player count vs `max-players`. `true` = kick based on **this server's** player count. |
| `persistent-player-entity-ids` | `false` | Players have the same entity ID on every server. Useful for cross-server teleport plugins. |
| `persistent-vehicle-entity-ids-seconds` | `15` | When a player disconnects, retain their vehicle's entity ID for this many seconds so they can be re-seated on reconnect. `0` disables. |

#### `sync-settings.files`

| Key | Default | Description |
|---|---|---|
| `files-to-not-sync` | `[]` | Exclusion list (paths or directories). Takes precedence over include lists. |
| `files-to-only-upload-on-server-stop` | `[]` | Big files uploaded once on shutdown instead of on every write (e.g. SQLite/H2 databases). |
| `files-to-sync-in-real-time` | `[]` | Files uploaded on every change and pushed to peers. **Not safe** for SQLite/H2 files. |
| `files-to-sync-on-startup` | `[]` | Files synced at startup. Directories are recursive. Last writer wins. **Deletion is not synced.** |
| `log-file-syncs` | `false` | Print a log line for every file synced. |

---

## `-D` system property overrides

Every key above can be overridden with a `-D` flag using a dotted path:

```bash
java \
  -DbungeecordName=server1 \
  -Dmultipaper.master-connection.my-name=server1 \
  -Dmultipaper.master-connection.master-address=127.0.0.1:35353 \
  -Dproperties.view-distance=16 \
  -Dpaper.global.proxies.proxy-protocol=true \
  -Dspigot.world-settings.default.entity-tracking-range.players=128 \
  -Dmultipaper.sync-settings.files.files-to-sync-on-startup="myconfigfile.yml;plugins/MyPlugin.jar" \
  -jar multipaper.jar
```

Notice:

- List values use `;` as separator inside the `-D` value.
- The standard `server.properties`, `spigot.yml`, and `paper.yml` keys are also
  accessible this way (`-Dproperties.view-distance=16`,
  `-Dspigot.world-settings.*`, `-Dpaper.global.*`).

This is what makes it easy to templated-clone many identical MultiPaper
servers with one command line each.

### Legacy keys

Older versions of MultiPaper used flat keys (`bungeecordName`,
`multipaperMasterAddress`, `interServerCompressionThreshold`, …). These are
migrated to the new nested format on first run by
`MultiPaperConfigurationLoader.transformLegacyConfig`
(`patches/server/0003:315-336`).

---

## JVM-property-only keys

These do **not** have a `multipaper.yml` equivalent and must be set via `-D`:

| Property | Default | Description |
|---|---|---|
| `multipaper.netty.threads` | `min(cores, 3)` (server side) | Size of the P2P Netty event loop. |
| `multipaper.netty.useOwnEventLoop` | `true` | Re-register P2P channels on MultiPaper's event loop instead of Minecraft's. |
| `multipaper.master.pingtimeout` | `60000` (ms) | Master-protocol keepalive timeout. |
| `allow.multiple.connections` | `false` | Silence the duplicate-server-name warning. |
| `disableFileWatching` | `false` | Disable `MultiPaperFileSyncer`'s filesystem watcher entirely. |
| `Paper.disableFlushConsolidate` | `false` | Disable Netty flush consolidation on the P2P loop. |
| `proxyserver.buffersize` | `32768` | Built-in proxy's per-connection buffer size. |
| `proxyserver.workerthreads` | `= cores` | Built-in proxy's worker thread count. |
| `max.regionfile.cache.size` | `256` | LRU size for the master's `RegionFileCache`. |
| `entityid.block.size` | `4096` | Number of entity IDs handed out per `RequestEntityIdBlock` call. |
| `logging.enabled` | `true` | Master-side logging toggle. |

---

## Master CLI

```
java [JVM properties] -jar multipaper-master.jar [address:]port [proxyPort]
```

- `port` defaults to `35353`.
- `address` defaults to `0.0.0.0`.
- `proxyPort` (optional) enables the built-in proxy on that port.

Once running, the master accepts these stdin commands (handled by
`CommandLineInput.java`):

| Command | Action |
|---|---|
| `shutdown` | Graceful shutdown. |
| `exit` | Force exit. |
| `threaddump` | Print a full thread dump. |

---

## BungeeCord plugin config

`plugins/MultiPaperProxy/config.yml`:

```yaml
port: 35353           # master protocol port
balanceNodes: true    # reroute players to least-busy server
```

## Velocity plugin config

`plugins/multipaper-velocity/config.toml`:

```toml
port = 35353
balance-nodes = true
```

---

## Related configs (not MultiPaper-specific)

Because MultiPaper is a Purpur fork, all the usual Purpur/Paper/Spigot configs
apply and are similarly overridable via `-D`:

- `server.properties` (`-Dproperties.*`)
- `spigot.yml` (`-Dspigot.*`)
- `paper.yml` / `paper-global.yml` / `paper-world.yml` (`-Dpaper.*`)
- `purpur.yml` (`-Dpurpur.*`)

Some are forced by MultiPaper patches (e.g. `preventMovingIntoUnloadedChunks`
on the Folia side is not relevant here, but MultiPaper does force a few
cross-server safety defaults — check the diffs for `patches/server/`).

---

## Next

Continue to [12 — Key Classes & File Reference](./12-key-classes.md).
