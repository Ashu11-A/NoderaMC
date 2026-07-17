# Nodera server scripts

Build the mod and run a NeoForge dedicated server that acts as the Nodera **bootstrap peer**.

| Script | Does |
|---|---|
| `env.sh` | Shared config (versions, paths, ports, Java resolution). Sourced by the others — don't run it directly. |
| `build.sh` | Builds the runnable fat mod jar (`neoforge-mod/build/libs/neoforge-mod.jar`). `--fast` skips tests. |
| `setup-server.sh` | Installs NeoForge `21.1.77` into `run/server/`, writes JVM/args + `server.properties`, copies the mod, accepts the EULA (with `--accept-eula`). |
| `start-server.sh` | Launches the server headless (refreshes the mod from the latest build first). |

## Quick start

```bash
./scripts/build.sh
./scripts/setup-server.sh --accept-eula     # --accept-eula = you accept https://aka.ms/MinecraftEULA
./scripts/start-server.sh
```

Then in the server console: `/nodera status` and `/nodera peers` show the mesh (epoch, gateway, members).

## Requirements

- **Java 21** (MC 1.21.1 / NeoForge 21.1 target). The host here is JDK 25 — the scripts warn on a
  non-21 JVM; set `NODERA_JAVA=/path/to/java21` to pin it.
- `curl` or `wget` (installer download), and internet access for `setup-server.sh`.

## Configuration (env overrides)

All read by `env.sh`; override inline:

```bash
NODERA_XMX=4G NODERA_SERVER_DIR=/srv/nodera ./scripts/start-server.sh
```

| Var | Default | Meaning |
|---|---|---|
| `NEOFORGE_VERSION` | `21.1.77` | NeoForge version (matches `docs/Task.0.md §3`). |
| `NODERA_SERVER_DIR` | `run/server` | Server install dir (git-ignored). |
| `NODERA_XMS` / `NODERA_XMX` | `1G` / `2G` | Server heap. |
| `NODERA_MC_PORT` | `25565` | Vanilla Minecraft port. |
| `NODERA_P2P_PORT` | `25566` | Nodera P2P mesh port (see below). |
| `NODERA_JAVA` | auto | Explicit Java binary. |

## Ports & the P2P mesh (the point of Nodera)

Two ports matter:

- **`25565` (Minecraft)** — how a player's client first connects to the server.
- **`25566` (Nodera P2P)** — the direct peer mesh. On login the server hands each client its P2P
  route; the client dials in and opens **direct sockets to the other players**. This mesh is
  independent of the vanilla connection, so when the server (bootstrap peer) goes offline the
  players stay connected to each other and a **deterministic gateway election** promotes a
  successor. That is the behaviour proven headlessly by `peer-runtime`'s `SessionContinuityIT`.

Open **both** ports (TCP) on the server's firewall.

### Remote (cross-machine) players

Set the advertise host so peers publish a reachable address (edit after first launch, in
`run/server/config/nodera-server.toml` for the server and each client's `nodera-client.toml`):

```toml
[p2p]
    bindHost = "0.0.0.0"
    port = 25566
    advertiseHost = "auto"   # "auto" picks a site-local IPv4; set a literal IP/hostname for WAN
```

`auto` is fine for same-machine / simple LAN testing. For players behind different NATs, direct TCP
needs port-forwarding or a VPN — NAT hole-punching / relay is the `transport-libp2p` follow-up
(`docs/LIMITATIONS.md` L-27), which slots in behind the same transport seam.

## Two-player continuity test (manual)

1. `./scripts/start-server.sh` on the host.
2. Two players join `HOST:25565` (both must run the Nodera mod).
3. `/nodera peers` → three members (server + two players), server is the gateway.
4. Stop the server (`stop`). Watch the client logs: each player drops the bootstrap, re-elects the
   **same** successor gateway, and their direct link keeps carrying keep-alives — they remain
   connected. Bringing the server back re-seeds a bootstrap peer for new joiners.

The headless equivalent (no GUI needed) runs in CI:

```bash
./gradlew :peer-runtime:test    # SessionContinuityIT: 3 real-TCP peers, kill bootstrap, assert continuity
```
