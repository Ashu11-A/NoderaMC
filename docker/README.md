# `docker/`

<!-- AI-AGENT-INSTRUCTION: Two different things live here and confusing them breaks something.
     `tracker/`, `rendezvous/` and `compose.yml` are the PUBLISHED images and the file a self-hoster
     copies — they must stay runnable with no repository checkout. `telemetry/` is a DEPLOYMENT of
     the project's own analytics plane; nothing in NoderaMC may require it to be running. The three
     service Dockerfiles are deliberately near identical: change one, change all three, in the same
     commit. Update this file when a service is added. -->

Container images for the three Nodera services, the project's own website, and the compose file
someone self-hosting a tracker or a relay actually runs.

| Path | What it is |
|---|---|
| `tracker/Dockerfile` | The tracker image — discovery. Port 6969 (TCP **and** UDP). |
| `rendezvous/Dockerfile` | The rendezvous/relay image — reachability behind NAT. Port 7500 (TCP). |
| `telemetry/` | The ingest image **and** the full analytics stack (Vector → Redpanda → ClickHouse → Grafana). A deployment, not a dependency. |
| `web/` | The noderamc.org website image — the built static site plus the Caddy config that routes it. Port 8080, loopback only. Not a service, and not something a self-hoster wants: it is behind the `site` compose profile so a plain `up -d` never starts it. |
| `compose.yml` | Run a tracker and/or a relay from the published images. |
| `.env.example` | Copy to `.env`. Three variables are required and compose says so. |

Full operator documentation: [`docs/tracker/SELF-HOSTING.md`](../docs/tracker/SELF-HOSTING.md) and
[`docs/rendezvous/SELF-HOSTING.md`](../docs/rendezvous/SELF-HOSTING.md).

---

## Published images

```
ghcr.io/ashu11-a/noderamc:tracker-latest      ghcr.io/ashu11-a/noderamc:tracker-canary
ghcr.io/ashu11-a/noderamc:rendezvous-latest   ghcr.io/ashu11-a/noderamc:rendezvous-canary
ghcr.io/ashu11-a/noderamc:telemetry-latest    ghcr.io/ashu11-a/noderamc:telemetry-canary
ghcr.io/ashu11-a/noderamc:web-latest          ghcr.io/ashu11-a/noderamc:web-canary
```

One package, one tag per service and channel — `latest` is the newest release tag, `canary` is built
from every push to `main` and is what the project's own deployment runs. Both are multi-architecture
manifest lists covering `linux/amd64` and `linux/arm64`; Docker picks the right one. Every build is
also tagged with its commit (`tracker-sha-<short>`), which is what you pin when you need a deployment
that does not move under you.

Which tag `compose.yml` asks for is **two** variables, not one, because the site and the services are
held to different policies. `NODERA_IMAGE_TAG` (default `latest`) covers the tracker, the relay and
telemetry: those carry peer state and reservation identity, so an operator chooses when they move —
noderamc.org's own host pins them to a `sha-…`. `NODERA_WEB_IMAGE_TAG` (default `canary`) covers the
website alone, which carries no state and is meant to follow `main` within minutes of a merge. Pin
the site too by setting it to a `sha-…`, and the update timer stops moving it.

Built by [`.github/workflows/containers.yml`](../.github/workflows/containers.yml).

## Getting one running

```bash
cp docker/.env.example docker/.env && $EDITOR docker/.env
docker compose -f docker/compose.yml up -d
```

The three required variables are the ones no default can be right for: the **public** address peers
should use to reach your tracker, the same for your relay, and the tracker(s) your relay announces
itself to. A service that advertises its container's bind address is a service nobody outside the
container can use, which is why compose refuses to start rather than guessing.

## Configuration is entirely environment variables

Every TOML key has an exact environment twin — the service's prefix plus the key, uppercased:

```
bind_addr            →  NODERA_TRACKER_BIND_ADDR
max_peers_per_world  →  NODERA_TRACKER_MAX_PEERS_PER_WORLD
tracker_endpoints    →  NODERA_RENDEZVOUS_TRACKER_ENDPOINTS   (comma-separated)
```

Ask the binary rather than trusting a document that can drift:

```bash
docker run --rm ghcr.io/ashu11-a/noderamc:tracker-latest nodera-tracker --print-env
```

Precedence is **defaults → config file → environment → command-line flag**. A config file is still
supported and still wins over the defaults; it is simply no longer required.

**An unrecognised variable in a service's prefix refuses the start.** `NODERA_TRACKER_MAX_SERVICE`
(missing the `S`) looks exactly as authoritative in a compose file as the real name, and a service
that ignored it would run with a bound the operator believes is in force and is not. The message
names the variable.

## Things that bite

**Stop timeouts.** SIGTERM starts a drain — refuse new work, tell the peers where to go, tell the
trackers, wait for in-flight work. The default drain grace is 30 seconds and `docker stop` allows 10,
so without `stop_grace_period` every deploy kills the service mid-drain. `compose.yml` sets 45s for
the tracker and 60s for the relay; on the CLI use `docker stop -t 60`.

**Persistence is the identity.** Mount something at `/var/lib/nodera`. The service signing key lives
there, and a service that regenerates it presents itself to the network as a brand-new, unmeasured
host after every restart — precisely when the availability score it has earned matters most. There is
deliberately no `VOLUME` instruction in the Dockerfiles (it forces an anonymous volume onto every
`docker run` and does not make anything more durable); the compose file uses named volumes.

**The tracker wants UDP too.** UDP costs one round trip where TCP costs a handshake, which matters to
a peer sweeping many trackers on a cadence. Publishing only TCP works — peers fall back — and the
service says so at startup if the UDP bind fails.

**Self-update is off, and in a container it should stay off.** `update_channel` is empty by default.
The in-container update path is `docker compose pull && docker compose up -d`, which gets you the
drain via SIGTERM and an image whose contents you can verify. Setting `update_channel` inside a
container asks a read-only-ish image to rewrite its own executable, which mostly declines and
otherwise gives you a container whose contents no longer match its tag.

## Building locally

The build context is the repository root — the Rust build stamps its version from `/VERSION`:

```bash
docker build -f docker/tracker/Dockerfile -t nodera-tracker:local .
docker build -f docker/web/Dockerfile -t nodera-web:local .
```

BuildKit is required (it is the default in Docker 23+): the builder uses cache mounts for the cargo
registry and the target directory, which is the difference between a 30-second rebuild and a
five-minute one.
