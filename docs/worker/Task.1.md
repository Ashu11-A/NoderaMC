# Worker Task 1 — Boot + Presence Endpoint

<!-- AI-AGENT-INSTRUCTION: The control listener is LOOPBACK-ONLY. Never bind it to a routable
     interface "for convenience" — it is an unauthenticated local IPC channel and binding it wider
     turns it into a remote control plane for the node. A bad connection must never take the server
     down. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** worker · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [network 2](../network/Task.2.md), [network 5](../network/Task.5.md)
**Consumed by:** [worker 2](Task.2.md), [minecraft 7](../minecraft/Task.7.md), [app 1](../app/Task.1.md)

---

## Goal

A Minecraft-free process that boots the same `PeerRuntime` the mod would build, holds the node's
persistent identity, joins the mesh, and answers the presence probe the mod's gate requires.

## Status detail

Complete and verified live outside the gate: the worker boots, becomes gateway, and answers
`NODERA-PROBE 1` → `NODERA-OK 1 <version>`. It ships as a runnable Gradle `application` distribution
that `scripts/dev.sh` builds, runs, and health-checks with a control probe.

## Dependencies

- [network 2](../network/Task.2.md) — the runtime being run.
- [network 5](../network/Task.5.md) — persistent identity, so the node's continuity is the worker's
  job rather than the game's.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `HeadlessPeerMain` — boots a `PeerRuntime` over a real socket transport | ✅ |
| 2 | Persistent identity loaded and reused across restarts | ✅ |
| 3 | `ControlProtocol` — verbs and version, the single source of truth | ✅ |
| 4 | `ControlServer` — loopback listener with handler dispatch | ✅ |
| 5 | `NODERA-PROBE` → `NODERA-OK` presence answer | ✅ |
| 6 | Configuration from file and environment | ✅ |
| 7 | Runnable distribution + `scripts/dev.sh` integration | ✅ |

## Design

**The node's identity belongs to the worker, not the game.** Once identity lives in a long-lived
process, a player restarting Minecraft is no longer a peer leaving and rejoining the mesh — which
means committee membership and reliability scores stop being reset by an ordinary game restart.

**Loopback-only, and that is a security boundary.** The control channel is unauthenticated local IPC.
Binding it to a routable interface would hand anyone who can reach the port the ability to host, stop,
and re-key worlds. The listener binds `127.0.0.1` and nothing negotiates that away.

**A line-based protocol, deliberately.** The control channel is not the peer wire: it is a local
supervisory channel spoken by three implementations in three languages (Java, Java-inside-NeoForge,
Rust). A simple line/JSON protocol keeps the two mirrors cheap to maintain and easy to debug with
`nc`. The canonical peer encoding stays where trust matters — on the network.

**A bad connection must never take the server down.** The dispatcher isolates per-connection failures;
a malformed request gets an error line, not a dead endpoint. The mod's startup gate depends on this
endpoint answering, so its availability is a product requirement, not an internal detail.

**Version in the handshake from the start.** The probe answers with the protocol version so the mod
can classify skew as "update the app" or "update the mod" rather than failing with a parse error.

## Files

- `java/peer/src/main/java/dev/nodera/headless/HeadlessPeerMain.java`
- `java/peer/src/main/java/dev/nodera/peer/control/{ControlProtocol,ControlServer}.java`
- `scripts/dev.sh`

## Testing

- `ControlServerTest` (on the Java gate): probe → `NODERA-OK`; verb dispatch; a bad connection never
  takes the server down.
- Live verification per increment: the worker boots, becomes gateway, and survives mod restarts;
  `printf 'NODERA-PROBE 1\n' | nc 127.0.0.1 25610` answers.

## Acceptance criteria

1. ✅ The worker boots a full `PeerRuntime` with no Minecraft present.
2. ✅ It keeps its `NodeId` across restarts.
3. ✅ It answers the presence probe with its protocol version.
4. ✅ A malformed or abruptly closed connection cannot kill the endpoint.
5. ✅ It binds the control listener to loopback only.

## Limitations

None owned. **L-47** (installer and cross-machine acceptance) is owned by
[`app/LIMITATIONS.md`](../app/LIMITATIONS.md).
