# Running a Nodera rendezvous / relay

<!-- AI-AGENT-INSTRUCTION: Written for someone who is NOT us and has no checkout. The sections that
     are identical for both services live in ../tracker/SELF-HOSTING.md and are linked, not copied —
     two copies of the image and upgrade instructions is two things to forget to update. The ENV
     table comes from `nodera-rendezvous --print-env`; regenerate it rather than hand-editing. -->

**Category:** rendezvous · **Last audit:** 2026-07-28

A relay is the reachability half of the network. Most players are behind a NAT that will not accept
an inbound connection; a relay lets two such peers meet, and bridges an **end-to-end-encrypted**
circuit between them when no direct path exists.

It holds **no authority** and cannot read what it carries. Records are self-signed and verified by
the discovering peer against the same canonical bytes; circuit payloads are encrypted end to end. A
lying relay can refuse to forward or hide records. It cannot forge one, and it cannot read a byte of
what passes through it.

Running one costs bandwidth, which is the honest reason there are fewer of these than trackers.

---

## 1. The short version

```bash
mkdir nodera && cd nodera
curl -O https://raw.githubusercontent.com/Ashu11-A/NoderaMC/main/docker/compose.yml
curl -o .env https://raw.githubusercontent.com/Ashu11-A/NoderaMC/main/docker/.env.example
$EDITOR .env
docker compose up -d rendezvous
```

Open **7500/tcp**, and check it from elsewhere:

```bash
docker run --rm ghcr.io/ashu11-a/noderamc:rendezvous-latest \
    nodera-rendezvous --healthcheck your-host.example.org:7500
```

## 2. The images, upgrading, and the update lane

```
ghcr.io/ashu11-a/noderamc:rendezvous-latest      the newest release tag
ghcr.io/ashu11-a/noderamc:rendezvous-canary      built from every push to main
ghcr.io/ashu11-a/noderamc:rendezvous-sha-abc1234 one specific commit — pin this if you want a
                                                 deployment that does not move under you
```

`latest` and `canary` are multi-architecture manifest lists covering `linux/amd64` and
`linux/arm64`, so Docker picks the right one on a Raspberry Pi or an Ampere VM as well. The image is
`alpine` with one statically linked binary in it, about 10 MB: no wrapper script, no supervisor, no
package manager left in the runtime layer.

Upgrading is `docker compose pull && docker compose up -d`. That is the intended path in a
container: `SIGTERM` drains, the new image starts, and what is running is something you can verify
against a tag.

**Self-update is off, and empty means off.** Setting `NODERA_RENDEZVOUS_UPDATE_CHANNEL=latest` makes
the service notice a newer published binary, verify it against the release's SHA-256 manifest,
drain, swap and re-exec. Downloading a relay is agreement to run a relay, not agreement to let it
replace its own executable — and inside a container it is the wrong tool anyway, because if it
succeeds you have a container whose contents no longer match its tag.

Two things about that lane are worth knowing before you enable it outside a container. The manifest
is checked against a pinned Ed25519 key **before** any digest is read, and a missing signature is a
refusal rather than a fallback — but **no key is pinned yet**, so until one is, the digest proves
integrity and not provenance (L-81). And `NODERA_RENDEZVOUS_UPDATE_CHANNEL` **requires**
`NODERA_RENDEZVOUS_TRACKER_ENDPOINTS`, refusing to start without it: a relay that updates itself has
to tell peers where to go while it drains, and it learns about replacement relays from its trackers.
Updating without that is a relay that vanishes mid-transfer.

## 3. Configuration

`NODERA_RENDEZVOUS_` plus the TOML key, uppercased. Full list:

```bash
docker run --rm ghcr.io/ashu11-a/noderamc:rendezvous-latest nodera-rendezvous --print-env
```

### The two you must set

| Variable | Why |
|---|---|
| `NODERA_RENDEZVOUS_ADVERTISED_ROUTES` | The **public** `host:port` peers should use. Inside a container the bind address is `0.0.0.0:7500`, which is useless to anyone outside it. |
| `NODERA_RENDEZVOUS_TRACKER_ENDPOINTS` | The tracker(s) this relay announces itself to. **A relay that announces nowhere is reachable only by peers that already have its address**, which is the discovery lane not working. Running a tracker from the same compose file? Use `tcp://tracker:6969` — see §4. |

### Worth setting

| Variable | Default | What it does |
|---|---|---|
| `NODERA_RENDEZVOUS_RESERVATION_HMAC_KEY_HEX` | empty | Keeps relay reservation proofs valid across a restart. Empty mints an ephemeral key at boot — correct, but every outstanding reservation is invalidated whenever the process restarts. `openssl rand -hex 32`. |
| `NODERA_RENDEZVOUS_MAX_CONCURRENT_CIRCUITS` | `0` (unstated) | Advertised headroom, not an enforced cap — the enforcement is the reservation limits. Publishing it lets peers prefer a relay with room over a saturated one. `0` is honest for a host you have not measured. |
| `NODERA_RENDEZVOUS_DRAIN_GRACE_SECONDS` | `30` | How long a drain waits for live circuits. Raising it past compose's `stop_grace_period` achieves nothing — Docker kills the container first. |
| `NODERA_RENDEZVOUS_RESERVATION_MAX_BYTES` | `67108864` | Byte ceiling for one circuit. This is your bandwidth bill. |
| `NODERA_RENDEZVOUS_RESERVATION_MAX_DURATION_SECONDS` | `600` | Wall-clock ceiling for one circuit. |
| `NODERA_RENDEZVOUS_CIRCUIT_IDLE_TIMEOUT_SECONDS` | `60` | No bytes either way for this long tears a circuit down. |
| `NODERA_RENDEZVOUS_PER_IP_REQUEST_QUOTA` | `120` | Register/reserve per source IP per refresh interval. |
| `NODERA_RENDEZVOUS_MAX_RECORDS_PER_NAMESPACE` | `5000` | |
| `NODERA_RENDEZVOUS_IDENTITY_FILE` | `/var/lib/nodera/rendezvous-identity.bin` | **Keep this.** |

An unrecognised `NODERA_RENDEZVOUS_*` variable refuses the start, for the reason given in the
tracker guide.

Three names in this prefix belong to the Java **peer**, not to this service —
`NODERA_RENDEZVOUS_ENDPOINTS`, `_FANOUT`, `_SWEEP_SECONDS` — and are ignored here rather than
refused, so a shell holding both can start both.

## 4. Things that will bite you

**Announce to a tracker, or nobody finds you.** This is the single most common way a working relay
ends up unused.

**If the tracker is in the same compose file, use its service name.** A container reaching its own
host's public address has to hairpin back through the published port, and on a default bridge
network that is refused — you get `Connection refused` in the announce line and nothing else. Use
`tcp://tracker:6969`.

**Stop timeouts matter more here than for a tracker.** A drain waits for bridged circuits carrying
real transfers, and cutting one costs a peer its download rather than a query it will retry. The
compose file allows 60s.

**A drain that times out still cuts.** The grace period is bounded because an unbounded wait lets one
stuck circuit hang a restart forever. Making that cost a re-dial instead of the transfer needs
resumable transfers, which is an open limitation (L-83) rather than something a longer wait fixes.

**The identity file is the service.** A regenerated one presents this relay to every peer as brand
new with no measured availability.

## 5. Getting listed

Open a pull request against the `services` branch — not `main` — adding an entry to
[`index.json`](https://github.com/Ashu11-A/NoderaMC/blob/services/index.json):

```json
{
  "kind": "rendezvous",
  "name": "example-eu",
  "endpoints": ["tcp://relay.example.org:7500"],
  "node_id": "9f2c1e40b7a34d5581cc0e77a1b93d02",
  "operator": "you <you@example.org>",
  "region": "eu-central"
}
```

`kind`, `name` and `endpoints` are required; the rest is optional. `node_id` is the 32 hex
characters from your startup line, and publishing it turns a hijacked DNS name from something a peer
accepts into something a peer notices. If you run a tracker on the same host, that is a **second**
entry with `"kind": "tracker"` — separate service, separate port, separate identity.

Run it for a while before you open the PR. A relay that appears and disappears is worse for peers
than one that was never listed, because they will have measured it and preferred it.

## See also

- [`../tracker/SELF-HOSTING.md`](../tracker/SELF-HOSTING.md) — running a tracker
- [`../../docker/README.md`](../../docker/README.md) — the images and how they are built
- [`REFERENCE.md`](REFERENCE.md) — the protocol
- [`Task.6.md`](Task.6.md) — the deployment lane
