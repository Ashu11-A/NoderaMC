# 13 — Plugin Development Guide

Writing a plugin that works on a MultiPaper cluster is **not** the same as
writing one for a single server. This file collects the rules, the new API
surface, and the recommended patterns.

The companion doc
[`DEVELOPING_A_MULTISERVER_PLUGIN.md`](../../MultiPaper/DEVELOPING_A_MULTISERVER_PLUGIN.md)
in the repo root is the upstream version of this material; this file
integrates it with the rest of the architecture docs.

---

## The fundamental rule

> **No data must be stored on the server itself.** All persistent state must
> live in an external store (a database, the master's KV, or files synced
> through the file syncer) so that any server in the cluster can read or write
> it consistently.

If you keep a `HashMap<Player, Integer> balance` on the server, you have two
problems:

1. The map only exists on **one** server. When the player teleports to
   another server, their balance is wrong.
2. Even on the original server, another server may have updated the canonical
   balance and you'll never know.

---

## Where events fire

A MultiPaper server only fires events for **things it owns**:

- `PlayerJoinEvent` and `PlayerQuitEvent` fire on **one** server only — the
  server the player actually connects to / disconnects from.
- Other player events (block break, move, interact, etc.) fire on **whichever
  server owns the chunk** where the event happened — which may not be the
  player's home server.
- Broadcast events (`Bukkit.broadcastMessage`, plugin-broadcast events) **do**
  fire on all servers.

This means your plugin must handle the case where it sees a player on a
server they don't live on (e.g. an `ExternalPlayer`). Use the predicates
below to tell.

---

## The MultiPaper API

Add the dependency (Maven Central / Clojars):

**Gradle:**

```groovy
repositories {
    maven { url "https://repo.clojars.org/" }
}
dependencies {
    compileOnly "com.github.puregero:multipaper-api:1.20.1-R0.1-SNAPSHOT"
}
```

**Maven:**

```xml
<repositories>
    <repository>
        <id>clojars</id>
        <url>https://repo.clojars.org/</url>
    </repository>
</repositories>
<dependencies>
    <dependency>
        <groupId>com.github.puregero</groupId>
        <artifactId>multipaper-api</artifactId>
        <version>1.20.1-R0.1-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

> If you want your plugin to also run on **regular Spigot/Paper**, depend on
> [MultiLib](https://github.com/MultiPaper/MultiLib) instead. MultiLib is a
> shim that provides the same notification API on both vanilla Paper and
> MultiPaper.

### Predicates — am I the owner?

```java
player.isLocalPlayer();          // True if this player is connected to *this* server.
chunk.isLocalChunk();            // True if *this* server ticks the chunk.
location.isChunkLocal();         // True if the chunk at this location is locally owned.
entity.isInLocalChunk();         // True if the entity is in a locally-owned chunk.
block.isInLocalChunk();          // True if the block is in a locally-owned chunk.
```

Use these everywhere you would normally mutate state. A common pattern:

```java
@EventHandler
public void onBlockBreak(BlockBreakEvent event) {
    if (!event.getBlock().isInLocalChunk()) {
        // Another server owns this chunk; the break will fire there.
        // Don't try to update persistent state from here.
        return;
    }
    // Safe to update persistent state.
}
```

### Online players

```java
Bukkit.getOnlinePlayers();        // Players connected to *this* server only.
Bukkit.getLocalOnlinePlayers();   // Same as above (explicit).
Bukkit.getAllOnlinePlayers();     // All players in the cluster, including external.
Bukkit.getLocalServerName();      // The name of *this* server.
```

Use `getAllOnlinePlayers()` for cluster-wide rendering (e.g. listing players
in a command), but be aware that `ExternalPlayer`s are read-only ghosts —
mutating their state through the API will not work the way you expect.

### Notifications (pub/sub)

`MultiPaperNotificationManager` (`patches/api/0001:106`) is a lightweight
pub/sub layer that propagates messages between servers:

```java
Bukkit.getNotificationManager().on("myplugin:balance-changed", (channel, message) -> {
    UUID uuid = UUID.fromString(message);
    refreshBalanceCache(uuid);
});

// On whichever server updates the balance:
Bukkit.getNotificationManager().notify("myplugin:balance-changed", player.getUniqueId().toString());

// Notify only the server that owns this player:
Bukkit.getNotificationManager().notifyOwningServer(player, "myplugin:balance-changed", payload);
```

This is the canonical replacement for `Bukkit.broadcastMessage` and for
"MySQL polling" — it gives you near-real-time cross-server updates without a
database.

### Key-value data storage

`MultiPaperDataStorage` (`patches/api/0006:65`) gives you a tiny KV store on
the master, backed by `datastorage.yml`:

```java
MultiPaperDataStorage kv = Bukkit.getMultiPaperDataStorage();

kv.getAsync("balance:" + player.getUniqueId()).thenAccept(balanceStr -> {
    int balance = balanceStr == null ? 0 : Integer.parseInt(balanceStr);
    player.sendMessage("Your balance is " + balance);
});

kv.set("balance:" + player.getUniqueId(), String.valueOf(newValue));
kv.add("global:online-count", 1);   // atomic integer add
kv.list("balance:").thenAccept(map -> { ... });
```

All methods are async (return `CompletableFuture`). Don't call `.join()` from
the main thread — block on the future from an async task instead.

This is **not** a substitute for a real database. Use it for small bits of
shared state (lookup tables, feature flags, simple counters). For player
data, use MySQL/PostgreSQL.

---

## Strategies for shared data

The original `DEVELOPING_A_MULTISERVER_PLUGIN.md` lays out four strategies;
summary:

### Option 1 — Single source of truth (always query the DB)

```java
void execBalanceCommand(Player player) {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
        int balance = Database.getBalance(player);
        player.sendMessage("Your balance is " + balance);
    });
}
```

Always correct, always slow. Best for read-mostly data.

### Option 2 — Polling with MySQL (not preferred)

Cache in memory, refresh every second. Creates DB load even when nothing
changed, and exposes a race window where a player can spend the same money
twice if they switch servers fast.

### Option 3 — Notifications with PostgreSQL (`LISTEN`/`NOTIFY`)

Cache in memory, but use Postgres's notification model to invalidate the
cache instantly when another server writes. Requires the
`com.impossibl.pgjdbc-ng` driver; add it to your `plugin.yml`:

```yaml
libraries:
  - com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.9
```

```java
connection.addNotificationListener(new PGNotificationListener() {
    public void notification(int processId, String channelName, String payload) {
        if (channelName.equals("balances")) {
            int updated = Database.getBalance(payload);
            playerBalances.put(payload, updated);
        }
    }
});
try (Statement s = connection.createStatement()) { s.execute("LISTEN balances"); }
```

When updating:

```java
public void updateBalance(Player player, int value) throws SQLException {
    playerBalances.put(player.getUniqueId().toString(), value);
    Database.setBalance(player, value);
    sendNotification("balances", player.getUniqueId().toString());
}
```

### Option 3b — Notifications with MultiLib / MultiPaperNotificationManager

Same pattern, but using MultiPaper's built-in notification manager instead of
Postgres. Keeps your existing MySQL database; the notification layer just
tells other servers when to refresh their cache.

### Option 4 — Subscriptions with Firestore (not covered)

Cloud-only; can't be self-hosted. Not discussed here.

---

## Other gotchas

### Caches can prevent you from seeing updates

If your plugin caches data without an invalidation strategy, you will serve
stale data. Either always read from the database, or implement a notification
listener.

### `PlayerJoinEvent` / `PlayerQuitEvent` fire on one server

If you do one-time setup on join (e.g. load player data), do it on the
**local** server where the event fires, but make sure other servers can pull
the same data on demand.

### `Bukkit.broadcastMessage` works cluster-wide

MultiPaper overrides this to broadcast across all servers. So do
`Bukkit.broadcast`.

### Don't assume single-threaded main thread

The vanilla "main thread" still exists on each individual MultiPaper server,
but **events fire on whichever server owns the relevant chunk**, which can be
a different server for different events in the same tick. Plan for
reentrancy.

### Scheduled tasks

`Bukkit.getScheduler().runTask(...)` runs on this server's main thread. If
your task affects a chunk that might be owned by another server, gate it on
`isInLocalChunk()` or use notifications to ask the owner to do the work.

### Persistence: file vs. data storage

- If your plugin uses a config file, put it in `files-to-sync-in-real-time`
  (see [09 — File Sync](./09-file-sync.md)).
- If it uses a database file (SQLite/H2), put it in
  `files-to-only-upload-on-server-stop` — **never** in real-time sync, which
  corrupts live DB files.
- For player data, use MySQL/Postgres.

### Teleporting between servers

If you want to teleport a player across servers (using BungeeCord's
`connect()` or a `ServerConnectEvent`), enable `persistent-player-entity-ids:
true` so the entity ID stays the same across servers. This is what
"seamless" cross-server teleport plugins need.

---

## Debugging

Useful commands (run as op):

| Command | What it shows |
|---|---|
| `/servers` | Every server in the cluster with TPS, tick time, player count. |
| `/slist` | Every online player and which server they're on. |
| `/mpdebug` | In-world overlay: aqua = your server ticks this chunk, red = another server ticks it. |
| `/mpmap` | ASCII map of nearby chunks and their ownership. |

`/mpdebug` is by far the most useful — it shows you exactly which server owns
the chunks around you, which is the question you'll be asking any time
something doesn't work right.

---

## A worked example: a multi-server economy

```java
public final class MultiServerEconomy extends JavaPlugin {

    private final Map<UUID, Integer> cache = new ConcurrentHashMap<>();
    private DataSource db;     // HikariCP-wrapped MySQL

    @Override
    public void onEnable() {
        // Listen for balance changes from other servers.
        Bukkit.getNotificationManager().on("econ:update", (channel, payload) -> {
            UUID uuid = UUID.fromString(payload);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try (Connection c = db.getConnection()) {
                    cache.put(uuid, Database.getBalance(c, uuid));
                }
            });
        });
    }

    public CompletableFuture<Integer> getBalance(Player player) {
        return CompletableFuture.supplyAsync(() -> {
            UUID uuid = player.getUniqueId();
            if (cache.containsKey(uuid)) return cache.get(uuid);
            try (Connection c = db.getConnection()) {
                int balance = Database.getBalance(c, uuid);
                cache.put(uuid, balance);
                return balance;
            }
        });
    }

    public CompletableFuture<Void> setBalance(Player player, int value) {
        return CompletableFuture.runAsync(() -> {
            UUID uuid = player.getUniqueId();
            try (Connection c = db.getConnection()) {
                Database.setBalance(c, uuid, value);
            }
            cache.put(uuid, value);
            // Tell every other server to refresh their cache.
            Bukkit.getNotificationManager().notify("econ:update", uuid.toString());
        });
    }
}
```

Notes:

- The notification guarantees that *eventually* every server has the right
  balance; the database guarantees correctness.
- Mutations are async so they don't block the main thread.
- `cache.put` happens after the DB write, so a failure rolls forward safely.

This pattern (DB for correctness, notifications for cache freshness) is the
recommended way to do shared mutable state in MultiPaper.

---

## Next steps

- Read the [MultiLib README](https://github.com/MultiPaper/MultiLib) for a
  drop-in API that also works on vanilla Paper.
- Browse [`patches/api/*.patch`](../../MultiPaper/patches/api/) for the full
  set of API additions.
- Use [`/mpdebug`](./12-key-classes.md) while developing to see chunk
  ownership in real time.

---

This concludes the MultiPaper documentation. Start over at the
[README](./README.md) or jump to a specific topic.
