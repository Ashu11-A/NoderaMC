# Server Task 2 — Embedded Peer + Control Plane

<!-- AI-AGENT-INSTRUCTION: The endpoint runs THE peer (java/peer PeerRuntime), not a reimplementation
     and not a subset. Option B is locked for the worker and it is locked here for the same reason:
     no second region engine in any language. The control plane is the SAME verb table
     (ControlProtocol v2) — served in-process for the embedded mode and over the loopback socket for
     the external mode. Do not invent a fourth mirror of the protocol. Keep this header accurate. -->

**Status:** 🚧 IN PROGRESS (config parsed, validated and enforced at enable; the node itself remains)
**Category:** server · **Owns:** L-71 · **Last audit:** 2026-07-25
**Depends on:** [server 1](Task.1.md), [network 2](../network/Task.2.md), [network 5](../network/Task.5.md), [worker 2](../worker/Task.2.md)
**Consumed by:** [server 3](Task.3.md) … [server 9](Task.9.md)

---

## Goal

The Paper/Folia server **is a node**. On enable it loads or generates a persistent Ed25519 identity,
boots a full `PeerRuntime` in-process, dials the configured trackers and rendezvous services, and
answers the same `ControlProtocol` v2 verb table the headless worker answers — so every existing tool
(`scripts/lib/e2e-main.sh`'s `control_verb`, the Tauri app, the mod's `CompanionClient`) can
interrogate an endpoint without learning anything new.

## Status detail

Not started.

## Dependencies

- [server 1](Task.1.md) — the module, the jar, and the scheduler seam.
- [network 2](../network/Task.2.md) — `PeerRuntime` is what gets embedded.
- [network 5](../network/Task.5.md) — `PersistentIdentityStore` for the node key.
- [worker 2](../worker/Task.2.md) — `ControlProtocol` is the single source of truth for the verbs;
  this task adds a fourth *consumer*, never a fourth definition.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `EndpointPeerService` — boots/stops `PeerRuntime` on the plugin lifecycle | ⬜ |
| 2 | Persistent node identity under `plugins/NoderaEndpoint/identity.bin` | ⬜ |
| 3 | `nodera-endpoint.yml` config: trackers, rendezvous, P2P bind/advertise, custody class, peer mode | ⬜ |
| 4 | In-process `ControlHandler` implementation serving the v2 verb table | ⬜ |
| 5 | Optional loopback `ControlServer` (so external tools can talk to an endpoint) | ⬜ |
| 6 | `peer.mode = external` — attach to a standalone `nodera-headless` worker instead of embedding | ⬜ |
| 7 | `/nodera` command tree on the endpoint (status, regions, peers, world, custody) | ⬜ |

## Design

**Embed the real peer.** `PeerRuntime` is a plain Java object with its own executors; it needs no
Minecraft and no Bukkit. Embedding it costs a few threads and gives the endpoint every property the
worker has: membership, gossip, gateway candidacy, tracker announce, rendezvous registration,
distribution seeding, and out-of-game committee validation. Writing a lighter "server-flavoured peer"
would be a second implementation of the thing the single-engine rule exists to prevent.

**The peer's threads are never tick threads.** Every callback that crosses back into the world hops
through `NoderaScheduler`. This is stated once, here, and enforced by an ArchUnit rule: no Bukkit type
may be referenced from a class the peer calls back into, except through the seam.

**Two peer modes, and the second one matters.** Embedding couples the node's lifetime to the server
JVM's — a server crash takes the node down with it, which is the exact coupling the
[`worker`](../worker/Task.0.md) category exists to remove.

| Mode | Peer lives in | When |
|---|---|---|
| `embedded` (default) | the server JVM | the common case: one process, one node, simplest to operate |
| `external` | a standalone `nodera-headless` process | production: the node survives a server crash, a restart, and a plugin reload |

In `external` mode the plugin holds no `PeerRuntime` at all: it drives the worker over the loopback
control socket exactly as the mod's `CompanionClient` does, and the same gate semantics apply (fail
closed with an actionable message, never a silent no-network degrade). The property this buys —
*the world stays served while the Minecraft server is down* — is [L-71](LIMITATIONS.md)'s exit test.

**The control plane is not re-specified.** `dev.nodera.peer.control.ControlProtocol` stays the single
source of truth. The endpoint implements `ControlHandler` against live plugin state; that is
implementation number four of the *handler*, not of the *protocol*. Any protocol change still lands in
`ControlProtocol`, the mod's `CompanionProtocol`, and the app's `control.rs` in one commit — this task
adds a fifth file to that list and says so in [`../worker/Task.0.md`](../worker/Task.0.md) §3.

**Why serve the loopback socket at all when the peer is in-process?** Because every diagnostic the
project already has speaks that socket. `control_verb 25610 'NODERA-STATE 2'` works against an
endpoint on day one, the live suites need no new probe mechanism, and an operator can point the
companion app at their server. The cost is one listener on `127.0.0.1`; the trust boundary is
unchanged and remains non-negotiable.

**Custody class is configuration, and it is a claim.** `custody: FULL` in the config makes the node
advertise full custody. Whether that claim survives a spot-check is [server task 3](Task.3.md)'s
problem; this task only carries it onto the wire.

## Files

- `java/paper-plugin/src/main/java/dev/nodera/endpoint/peer/{EndpointPeerService,EndpointControlHandler,ExternalWorkerLink}.java`
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/config/EndpointConfig.java`
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/command/NoderaEndpointCommand.java`
- `java/paper-plugin/src/main/resources/nodera-endpoint.yml`

## Testing

- `EndpointControlHandlerTest` — every v2 verb answers from live state; an unknown verb answers
  `NODERA-ERR unknown verb` rather than hanging (the same contract the worker holds).
- `EndpointPeerLifecycleTest` — enable → boot → disable stops every thread; a second enable after a
  reload does not leak a listener or double-announce.
- `ExternalWorkerLinkTest` — `peer.mode = external` with no worker running fails closed with an
  actionable message; with a worker running it proxies verbs faithfully.
- Live (`e2e-endpoint.sh` P1): the endpoint answers `NODERA-PROBE 2` and `NODERA-STATE 2` on its
  control port, and appears in another peer's membership with a public key.

## Acceptance criteria

1. ⬜ An endpoint boots a `PeerRuntime`, announces to the tracker, and registers with rendezvous.
2. ⬜ Its identity persists across restarts (same `NodeId`, same public key).
3. ⬜ `control_verb <port> 'NODERA-STATE 2'` returns live JSON from an endpoint, unmodified tooling.
4. ⬜ Another peer sees the endpoint as a session member with a verified key.
5. ⬜ `peer.mode = external` drives a standalone worker and fails closed when one is absent.
6. ⬜ Disable stops every peer thread; no listener survives a `/reload`.

## Limitations

- **L-71** — the embedded peer dies with the server JVM; `external` mode is the elimination path and
  its exit test is a SIGKILLed server whose world stays served.
