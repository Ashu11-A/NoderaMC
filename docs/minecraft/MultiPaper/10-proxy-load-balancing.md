# 10 — Proxy & Load Balancing

Players need a single address to connect to. MultiPaper supports **four
different ways** to put a load balancer in front of the cluster:

1. **Built-in proxy** (recommended for new deployments) — a tiny pure-NIO TCP
   proxy bundled into the master process.
2. **BungeeCord plugin** — drop the master jar into BungeeCord's `plugins/`
   directory and use BungeeCord's native connection handling.
3. **Velocity plugin** — same idea, for Velocity proxies.
4. **Any external TCP load balancer** (HAProxy, nginx stream, a cloud LB) —
   MultiPaper doesn't care, as long as players get spread across servers.

The first three are bundled into the single `multipaper-master.jar`. The
choice is just a deployment-time decision.

---

## Built-in proxy

The built-in proxy is the simplest option. Start it by giving the master a
second port:

```bash
java -jar multipaper-master.jar 35353 25565
#                                  │     │
#                                  │     └─ proxy port (players connect here)
#                                  └─────── master protocol port
```

Implementation: `MultiPaper-Master/.../proxy/ProxyServer.java:16`.

### Architecture

- One accept thread with a `Selector` listening on `proxyPort`.
- `proxyserver.workerthreads` worker threads (default
  `Runtime.getRuntime().availableProcessors()`), each with its own selector.
- On `OP_ACCEPT` (`:95-158`):
  1. Pick a target server via `getSuitableServer()`.
  2. Open a channel to it.
  3. Pair the two channels with two `ProxiedConnection`s that share each
     other's read buffer as the other's write buffer.
  4. Flip both connections to read-interested.
- Subsequent reads just shuttle bytes between the paired buffers
  (`ProxiedConnection` `:10`, half-duplex interest-op flip).

### Load-balancing algorithm (`getSuitableServer()`, `:160-186`)

The proxy does **not** round-robin. It does weighted random based on each
server's recent tick time:

```
weight(server) = (highestTickTime - thisServer.tickTime + 1)
chosen = random server, biased by weight
```

So a server that's been ticking fast (low `tickTime`) is more likely to be
picked, and the most loaded server (highest `tickTime`) has weight ~1 and is
almost never picked. The `tickTime` values come from each server's
`WriteTickTimeMessage` reports.

Only servers with `advertise-to-built-in-proxy: true` (default true) are
candidates.

### Handshake rewriting

Minecraft's first packet (the handshake) includes the hostname the client
connected to. The proxy **rewrites** this packet so the destination server
sees the player's real IP and hostname — the same trick BungeeCord uses
("BungeeCord IP forwarding").

Implementation: `proxy/ProxiedConnection.java:86-121`,
`proxy/HelloPacket.java:8`. `HelloPacket.sanitizeAddress` validates the
hostname (strips anything after `\0`, which is what vanilla BungeeCord IP
forwarding uses as a separator) and the rewrite injects the real player IP.

### Enabling IP forwarding

On each MultiPaper server, set `bungeecord: true` in `spigot.yml` so the
server expects the rewritten handshake. (See README under "The built-in
proxy".)

### Performance

The proxy is intentionally minimal — no rate limiting, no firewalling, no
protocol inspection, no compression, no encryption. It's a byte pipe. This is
a deliberate choice: "as fast and light-weight as possible." For richer
features, use BungeeCord or Velocity.

---

## BungeeCord plugin

If you already run BungeeCord, drop `multipaper-master.jar` into BungeeCord's
`plugins/` directory and the same jar loads as a BungeeCord plugin
(`bungee/MultiPaperBungee.java:23`).

### Configuration

`plugins/MultiPaperProxy/config.yml`:

```yaml
port: 35353         # port for the master protocol server
balanceNodes: true  # reroute players to the least-busy server
```

### Behaviour

- On plugin enable, the master protocol server starts inside the BungeeCord
  JVM.
- On `ServerConnectEvent` (`MultiPaperBungee.java:71-101`), if
  `balanceNodes` is true and the target is a MultiPaper server, the plugin
  reroutes the player to the server with the lowest
  `CircularTimer.averageInMillis()` (i.e. the fastest-ticking server).

This is the same weighting algorithm as the built-in proxy, just expressed
through BungeeCord's events.

---

## Velocity plugin

Same idea for Velocity: drop the jar into Velocity's `plugins/` directory.

`plugins/multipaper-velocity/config.toml`:

```toml
port = 35353
balance-nodes = true
```

Implementation: `velocity/MultiPaperVelocity.java:28`. Listens for
`ServerPreConnectEvent` (`:53-78`) and reroutes to the least-loaded server.

---

## External load balancer

You can also put any TCP load balancer (HAProxy in `tcp` mode, nginx `stream`
block, a cloud TCP LB, DNS round-robin, etc.) in front of the MultiPaper
servers. In that case:

- **Don't** enable the built-in proxy.
- Each MultiPaper server is a regular backend; the LB picks one per
  connection.
- **You** are responsible for the load-balancing algorithm. The
  `WriteTickTimeMessage`-driven health info isn't exposed to the LB, so the
  LB will likely round-robin or least-connection, which is usually fine.

If you want the LB to use MultiPaper's own health metrics, use the built-in
proxy or BungeeCord/Velocity plugin instead.

---

## Server registration

For the built-in proxy and the BungeeCord/Velocity plugins to know which
servers exist, each server must:

1. Connect to the master (`multipaperMasterAddress`).
2. Send `SetPortMessage(port)` so the master knows which port the server
   listens on for player connections.
3. Have `advertise-to-built-in-proxy: true` in its `multipaper.yml`.

The master then includes that server in the candidate pool.

---

## Health metrics

The proxy / plugins read each server's `CircularTimer`
(`MultiPaper-Master/.../CircularTimer.java:3`), a ring buffer of the last
1200 tick durations (about 60 seconds at 20 TPS). Servers report their tick
times via `WriteTickTimeMessage(tickTime, tps)` every second; the master
updates the timer and broadcasts `ServerInfoUpdateMessage`.

The `tps` field is informational; the load balancer actually uses the
rolling-average tick time (in milliseconds) because that's a more sensitive
indicator of overload than TPS, which is already clamped at 20.

---

## Choosing a load-balancing strategy

| Strategy | Use when |
|---|---|
| **Built-in proxy** | You want a single, no-frills deployment with no extra dependencies. |
| **BungeeCord** | You're already on BungeeCord, or you need its plugin ecosystem. |
| **Velocity** | You're already on Velocity (faster than BungeeCord). |
| **External LB** | You have an existing LB infrastructure, or you don't want the master doing the LB work. |

In all cases, **the master must be reachable from each MultiPaper server** on
the master protocol port (`35353` by default), and **the LB must be reachable
from players** on port `25565`.

---

## Next

Continue to [11 — Configuration Reference](./11-configuration.md).
