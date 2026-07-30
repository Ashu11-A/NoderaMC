# Worker Task 7 — The LAN Lane: Playing Together Without a Mod

<!-- AI-AGENT-INSTRUCTION: Two invariants, both security-relevant. (1) A guest names a SESSION, never
     an address. `TunnelOpen` carries no host and no port, and `publish` is the only thing that maps
     a session to one. Adding an address field would turn every peer into an open TCP proxy into its
     own loopback and LAN. (2) Detection is NOT consent — nothing is announced until the player says
     so through NODERA-LAN SHARE. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** worker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [worker 2](Task.2.md), [worker 6](Task.6.md), [network 3](../network/Task.3.md),
[tracker 2](../tracker/Task.2.md)
**Consumed by:** [app 7](../app/Task.7.md)

---

## Goal

Let two people play together when **neither has installed anything into Minecraft** — using the
button vanilla already has: *Open to LAN*.

## How it works

```text
  Player A                          the network                        Player B
  ────────                          ───────────                        ────────
  vanilla Minecraft
   └─ Open to LAN ──224.0.2.60:4445──▶ LanWatcher
                                        └─ LanSessionService  (offered)
                                             ⇧ the app asks; A says yes
                                        ┌─ TunnelService.publish(session → :54321)
                                        └─ WorldHostingService.host(…) ──▶ tracker
                                                                             │
                                                        NODERA-DIRECTORY ◀───┘
                                                                             │
                       TunnelOpen / TunnelData / TunnelClose ◀── NODERA-CONNECT
                                                                             │
  A's game :54321 ◀───────── the tunnel ─────────▶ 127.0.0.1:35461 ◀── B's vanilla Minecraft
                                                    (Direct Connect)
```

Verified live on 2026-07-26 against two real worker processes and a real tracker binary, with a
stand-in for A's game and the genuine vanilla multicast beacon:

```
1. A's app is offered the world:  'Ashu's Survival World' — state=offered
2. A presses 'Share it':          NODERA-OK
3. B browses:                     "Ashu's Survival World"  players=1  pieces=0  mine=False
4. B clicks Join:                 NODERA-OK 127.0.0.1:35461
5. B connects to that port:       B received: [A's world] HELLO FROM PLAYER B
                                  A's game actually saw: ['hello from player B']
6. A closes the world:            A's sessions: []
```

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `LanBeacon` — parse vanilla's `[MOTD]…[/MOTD][AD]port[/AD]` announcement | ✅ |
| 2 | `LanWatcher` — join `224.0.2.60:4445` on every usable interface; expire by silence | ✅ |
| 3 | `LanSessionService` — offered / shared / declined, and what closing a world does to each | ✅ |
| 4 | `TunnelOpen`/`TunnelData`/`TunnelClose` (tags 63–65) | ✅ |
| 5 | `TunnelService` — publish a session, open a local door, pump both ways | ✅ |
| 6 | `NODERA-LAN`, `NODERA-DIRECTORY`, `NODERA-CONNECT`, `NODERA-DISCONNECT` | ✅ |
| 7 | `NODERA-SHARELINK` + `WorldShareLink` — the magnet-style invitation | ✅ |
| 8 | The `lan` block in `NODERA-STATE`, so the app can raise the modal on the same push | ✅ |

## Design

**The worker listens, because the player has no mod.** Everything that leaves an unmodified game
when a world is opened to LAN is one multicast datagram, every ~1.5 s, on a group Minecraft has used
for a decade. The always-on worker is already outside the game and can simply hear it — so detection
works for vanilla, modded, any launcher, with nothing installed into Minecraft.

**Only the connection travels.** No save is copied, no chunk is replicated, no world is transferred.
A's game remains the sole authority over A's world; the tunnel moves TCP bytes. That is precisely
why an unmodified client can use it: from Minecraft's point of view, B joined a server on localhost.

**A guest names a session, never an address.** The obvious design — "connect me to `host:port`" —
makes every peer an open TCP proxy into its own loopback and LAN for anyone who can reach it. So
`TunnelOpen` has no address field at all: it carries a session id, and `publish` is the only
thing that maps one to a port. The property is structural rather than a check somebody must remember,
and `TunnelServiceIT` asserts that an unpublished session is refused without the host's game ever
being dialled.

**Detection is not consent.** A detected world sits in `offered` and reaches nothing. Only
`NODERA-LAN SHARE` publishes the tunnel and announces the world. There are three states, not two,
because *"no"* and *"not now"* are different answers: `declined` records an answer so the app stops
asking, and both keep the world listed so it can still be shared later — which is the only way "ask
me later" can mean anything.

**The port is the session's identity.** Minecraft picks a fresh port every time a world is opened and
says nothing at all when it closes. So "still open" is a beacon seen recently, "closed" is eight
seconds of silence (several missed beacons, not one), and a reopened world is a *new* session —
reusing the id would advertise a session resolving to a socket nobody is listening on.

**A LAN session is a world, on the ordinary announce lane.** It is published through
`WorldHostingService` like anything else, with a live `mc` endpoint and no content. A browser
therefore needs no second discovery path, and `pieces == 0` is what distinguishes "a live game" from
"a shared world" in the directory.

**Backpressure closes a stream rather than the node.** Inbound chunks are queued per stream with a
bound; a socket that will not drain loses its stream. The alternative is applying backpressure to the
runtime's state thread, where the queue behind it is the whole node's message handling — one
player's connection failing is a bad minute, a stalled node is everyone's.

**Invitations are magnet links.** People already know that a link is the whole invitation, that it
contains no content, and that receiving one does not oblige the sender to stay online while they
think about it. All three are true of `nodera:?xt=urn:nodera:…`. It carries the world id, the name,
the trackers and rendezvous services to look through, and the world's public key — and no content and
no password, which is what makes it safe to forward.

## Files

- `java/transport/src/main/java/dev/nodera/protocol/tunnel/{TunnelOpen,TunnelData,TunnelClose}.java`
- `java/peer/src/main/java/dev/nodera/peer/tunnel/{TunnelService,LanWatcher,LanBeacon}.java`
- `java/worker/src/main/java/dev/nodera/headless/LanSessionService.java`
- `java/worker/src/main/java/dev/nodera/headless/WorkerControlHandler.java` (`LiveLanes` + the verbs)
- `java/storage/src/main/java/dev/nodera/storage/WorldShareLink.java`
- `java/peer/src/main/java/dev/nodera/peer/control/{ControlProtocol,ControlHandler,ControlServer}.java`

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `NODERA_LAN_WATCH` | `true` | Set to `0` to run without LAN detection (a locked-down container) |

A machine whose network stack will not let the worker join the multicast group still runs a perfectly
good peer. The control verbs report `supported:false` rather than an empty list, because "nothing is
open" and "this can never be detected here" are different answers and only one of them is worth
telling the user about.

## Running it by hand

```bash
scripts/dev.sh --lan --with-app
```

Two **unmodified** Minecraft clients, two workers, two companion windows, a tracker and a rendezvous.
Player 1 opens a world to LAN and answers the modal; player 2 finds it under *Join a world* and
direct-connects to the loopback address it hands back. Player 2's worker runs with LAN detection off
on purpose — on one machine both workers hear the same beacon, and a joiner that can see the world
locally proves nothing about the network.

## Testing

- `TunnelServiceIT` (7) — over real sockets and a real transport pair: bytes cross both ways and
  reach a stand-in game; an **unpublished session is refused and the host's game is never dialled**;
  withdrawing a session drops the connections it was carrying; the guest's door is loopback-only;
  joining twice reuses one port; leaving releases everything.
- `LanBeaconTest` (6) — the payloads vanilla actually sends, including a MOTD with colour codes and
  brackets, and every malformed shape that must not be mistaken for a world.
- `LanSessionServiceTest` (11) — detection announces nothing; sharing publishes *and* announces;
  declining keeps the world listed; stopping differs from declining; closing the world withdraws it
  whatever was decided; a repeated beacon does not reset the decision; ports are identities.
- `WorldShareLinkTest` (9) — both encodings round-trip, a name with `&` and `?` survives, the magnet
  spelling parses, a mangled key costs the badge and not the invitation, and nothing sensitive is in
  the link.

## Acceptance criteria

1. ✅ A world opened to LAN in an unmodified Minecraft is detected within ~2 s.
2. ✅ Nothing about it reaches the network until the player says so.
3. ✅ A second player finds it in the directory and joins it with an unmodified client.
4. ✅ Bytes written by the guest reach the host's actual game, and the reply comes back.
5. ✅ No world data is transferred at any point.
6. ✅ A peer cannot be asked to connect to an address the host did not publish.
7. ✅ Closing the world withdraws the session everywhere.
