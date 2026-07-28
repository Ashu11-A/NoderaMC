# Server Task 2 — Embedded Peer + Control Plane

<!-- AI-AGENT-INSTRUCTION: The endpoint runs THE peer (java/peer PeerRuntime), not a reimplementation
     and not a subset. Option B is locked for the worker and it is locked here for the same reason:
     no second region engine in any language. The control plane is the SAME verb table
     (ControlProtocol v2) — served in-process for the embedded mode and over the loopback socket for
     the external mode. Do not invent a fourth mirror of the protocol. Keep this header accurate. -->

**Status:** 🚧 IN PROGRESS (config parsed/validated/enforced at enable; external worker link shipped and proven live; the in-process `PeerRuntime` remains)
**Category:** server · **Owns:** — (L-71 RETIRED 2026-07-26) · **Last audit:** 2026-07-28
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

**Two of the seven deliverables landed; the node itself has not.**

- **`nodera-endpoint.yml` is parsed, validated, and enforced at enable** — `EndpointConfig` reads the
  file **without Bukkit's YAML reader** (the contract is unit-testable with no server), takes
  documented defaults for omissions, and refuses contradictions with every reason at once. Running it
  against a real Paper server corrected the first rule: *custody: FULL with no world id* is fine until
  the world is also **announced**, which is the shape the harness stages.
- **`peer.mode: external` works and is the harness default.** `EndpointPeerLink` links the plugin to
  an always-on worker over the worker's **own** control protocol (one line out, one line back —
  written first with the app's 4-byte framing, which the worker ignored, and a live run said so in one
  line), then hands it the world with the same `NODERA-HOST` verb the mod sends. The endpoint is
  another client of the same node, not a special case.
- **L-71 is RETIRED** (2026-07-26). `e2e-endpoint.sh` **E4 is the exit test, run against real Paper
  1.21.1**: the endpoint links, the worker reports the world in `connected_worlds`, the server JVM is
  **SIGKILLed** (`kill -9`, because a graceful stop proves the opposite of the claim), and the worker
  still answers and still reports the world hosted. Evidence in
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

**What remains** is the in-process node: a persistent identity under
`plugins/NoderaEndpoint/identity.bin`, a booted `PeerRuntime`, and an in-process `ControlHandler`
serving the v2 verb table. **Follow-on scope from L-71's retirement** (not a new limitation): `embedded`
is still a config value that does nothing — given that an external worker is the crash-independent
answer, it should host a node or be deleted, and deleting it is the honest end state.

## Dependencies

- [server 1](Task.1.md) — the module, the jar, and the platform seam.
- [network 2](../network/Task.2.md) — `PeerRuntime` is what gets embedded.
- [network 5](../network/Task.5.md) — `PersistentIdentityStore` for the node key.
- [worker 2](../worker/Task.2.md) — `ControlProtocol` is the single source of truth for the verbs;
  this task adds a fourth *consumer*, never a fourth definition.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `EndpointPeerService` — boots/stops `PeerRuntime` on the plugin lifecycle | ⬜ |
| 2 | Persistent node identity under `plugins/NoderaEndpoint/identity.bin` | ⬜ |
| 3 | `nodera-endpoint.yml` config: trackers, rendezvous, P2P bind/advertise, custody class, peer mode | ✅ (`EndpointConfig`, 9 unit tests) |
| 4 | In-process `ControlHandler` implementation serving the v2 verb table | ⬜ |
| 5 | Optional loopback `ControlServer` (so external tools can talk to an endpoint) | ⬜ (external mode reaches the worker's own socket instead) |
| 6 | `peer.mode = external` — attach to a standalone `nodera-headless` worker instead of embedding | ✅ (`EndpointPeerLink` + `ControlClient`; E4 green) |
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
*the world stays served while the Minecraft server is down* — was **L-71's exit test, now green**.

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

- `java/paper-plugin/src/main/java/dev/nodera/endpoint/NoderaEndpointPlugin.java:86` — `linkPeer()`:
  the external-mode wiring (probes, links, hosts through the worker)
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/EndpointConfig.java:25` — the parsed +
  validated configuration (`PeerMode`, `Custody`, `problems()`)
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/ControlClient.java:32` — one exchange with the
  worker's control socket (`NODERA-PROBE`, `NODERA-HOST`)
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/EndpointPeerLink.java:23` — background retry
  link (linking is never a startup gate; only state changes are logged)
- `java/paper-plugin/src/main/resources/nodera-endpoint.yml` — the file the harness stages
- Tests: `EndpointConfigTest` (9) · `EndpointPeerLinkTest` (6, against a real stand-in worker socket)
- Live: `scripts/e2e-endpoint.sh` E1–E4 (E4 SIGKILLs the server JVM; the worker keeps hosting)
- *Not yet created:* `endpoint/peer/{EndpointPeerService,EndpointControlHandler,ExternalWorkerLink}.java`,
  `endpoint/command/NoderaEndpointCommand.java`

## Testing

- `EndpointControlHandlerTest` — every v2 verb answers from live state; an unknown verb answers
  `NODERA-ERR unknown verb` rather than hanging (the same contract the worker holds). *(planned —
  handler not yet built)*
- `EndpointPeerLifecycleTest` — enable → boot → disable stops every thread; a second enable after a
  reload does not leak a listener or double-announce. *(planned)*
- `EndpointPeerLinkTest` — the external link probes a real socket, logs only changes, and a wrong
  answer is not a link. *(green)*
- Live (`e2e-endpoint.sh` E4): the endpoint links to a worker, hands it the world, and the world
  stays hosted through a SIGKILL of the server JVM. *(green — L-71's exit)*

## Acceptance criteria

1. ⬜ An endpoint boots a `PeerRuntime`, announces to the tracker, and registers with rendezvous.
2. ⬜ Its identity persists across restarts (same `NodeId`, same public key).
3. 🚧 `control_verb <port> 'NODERA-STATE 2'` returns live JSON — in `external` mode the worker
   answers; the in-process handler for `embedded` mode is not yet built.
4. ⬜ Another peer sees the endpoint as a session member with a verified key.
5. ✅ `peer.mode = external` drives a standalone worker and fails closed when one is absent (E4 green).
6. 🚧 Disable stops the link thread cleanly; the in-process peer thread does not exist yet.

## Limitations

- **L-71** — RETIRED 2026-07-26: the external worker link survives a SIGKILLed server JVM and keeps
  the world hosted. Evidence in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). The residual — `embedded`
  does nothing — is follow-on scope, tracked in [`PROGRESS.md`](PROGRESS.md), not a new row.
