# App Task 6 — The Dashboard API and the Live Link

<!-- AI-AGENT-INSTRUCTION: One rule governs everything here and it is not a style preference: a value
     nobody has reported is NOT zero. `Option` in Rust, `null` on the wire, an em dash on screen.
     Do not add a `unwrap_or(0)`, a `?? 0`, or a default that turns "unknown" into a number — that
     is the exact defect this task exists to remove. Second rule: the dashboard is emitted when a
     snapshot is ACCEPTED, never on a timer; a UI interval would make a dead link look alive. Keep
     this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** app · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [app 2](Task.2.md), [worker 2](../peer/Task.2.md), [worker 6](../peer/Task.6.md)
**Consumed by:** [app 3](Task.3.md), [app 5](Task.5.md)

---

## Goal

Replace the dashboard with one built on a real API: a typed model produced in Rust, a link the Java
worker **pushes** into, and pages that cannot render a number nobody reported.

## The defect

A user's dashboard read, in full:

```
SHARED WORLDS 0   ACTIVE PLAYERS 0   PEERS 0   RELAY LATENCY 0 ms
UPLOADED 0 B (0 B/s)   DOWNLOADED 0 B (0 B/s)
```

Every one of those zeros was rendered with total confidence, and each was indistinguishable from
four different situations: the worker is down; the worker is up and has genuinely done nothing; the
app has not read it yet; the app read it once, long ago, and has shown that ever since. `0 ms` was
the clearest case — the worker sends `-1` for "unreachable or not yet probed", and the UI printed it
as an instant handshake that never happened.

Nothing was broken in the plumbing. The screen simply had no way to say "I do not know", so it said
"zero" instead. A dashboard that cannot distinguish those is not reporting, it is decorating.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `NODERA-WATCH` — the worker holds the connection open and pushes state on change | ✅ |
| 2 | `api::model` — a typed view model with `Option` wherever "unknown" is a real answer | ✅ |
| 3 | `api::store` — one revisioned picture; rates measured against a real clock | ✅ |
| 4 | `api::link` — streams by preference, polls only for a worker too old to stream | ✅ |
| 5 | `api::commands` — what React calls: initial paint, world detail, admin proof | ✅ |
| 6 | One typed event, `nodera://dashboard`, emitted on acceptance rather than on a timer | ✅ |
| 7 | The dashboard rebuilt on it, with a status band and honest empty states | ✅ |
| 8 | World detail rebuilt, including an **Ownership** pane and a live proof button | ✅ |

## Design

```text
  Java worker ──NODERA-WATCH──▶ link.rs ──▶ store.rs ──▶ nodera://dashboard ──▶ React
       (pushes on change)      (reconnects)  (revision,    (one typed event)
                                              rates, age)
```

**The worker announces; the app does not guess when to ask.** A poller cannot be both current and
cheap: a two-second cadence is up to two seconds wrong about a world that just came online, and a
faster one spends the difference re-reading identical bytes. `NODERA-WATCH` inverts it — the worker
writes when its own state changes. Measured live: a world hosted on one connection reached a watcher
on another in **9 ms**, and an idle node sent **one** line in the first second and then nothing.

**Silence is made into evidence.** The worker re-sends the current state every 10 s even when
nothing changed, and the app treats 25 s of silence as a dead link. Without that, a half-open TCP
connection reads as a quiet node for minutes and the dashboard keeps claiming "live" over numbers
nobody is still sending.

**Provenance travels with the payload.** Every snapshot carries `link`: status, transport
(`stream`/`poll`), when it was taken, how many it is in the sequence, the last error, and
`has_data`. It is on the payload rather than behind a second query because a page that must ask two
questions to render one number will eventually render it without asking the second.

**The revision is what proves liveness.** Values legitimately stay the same, so "unchanged" and
"not arriving" cannot be told apart by watching them. The revision climbs on every accepted
snapshot, so the band can show motion when the numbers are still.

**Rates are computed in Rust.** Throughput is a derivative, and taking it in the view means taking
it between renders, which are not samples. It is measured here against a real elapsed time, is
`None` until two snapshots exist, and is dropped across an outage — differencing over a gap would
attribute a whole outage to one interval.

**Going offline ages the picture; it does not blank it.** The last thing heard is the most useful
thing to show, provided the screen says how old it is. Blanking loses the only information available
about a node that has gone quiet.

**A worker that cannot stream is polled, not waited on.** It answers `NODERA-ERR unknown verb` on
the first line; the link drops to a 1 s poll, says `Polling` on screen, and re-probes for the stream
when that worker goes away — so upgrading the worker upgrades the link with no app restart.

**What the proof button does not claim.** Asking your peer to sign a fresh challenge shows the
world's private key is on this machine. It is not a cryptographic verification: the app carries no
Ed25519 implementation and deliberately does not grow one to check a claim about the process it is
already talking to. Verification is what other peers do, against the world's public key. The UI says
"your peer signed this challenge", never "verified".

## Files

- `peer/src/main/java/dev/nodera/peer/control/{ControlProtocol,ControlServer}.java` — the verb
- `app/src/api/{mod,model,store,link,commands}.rs`
- `app/ui/src/{api.ts,Dashboard.tsx,App.tsx,World.tsx}`
- `app/src/{control,daemon,power,main}.rs` — the old poll loop and `MetricsHandle`
  removed; the store is now the single source the power rules read too

## Testing

- `ControlWatchStreamTest` (6, Java) — a change is pushed unasked; an unchanged node is **not**
  re-sent at the sampling interval; the interval is clamped; an abandoned watcher does not disturb
  the endpoint; ordinary verbs still work while a watch is open; a throwing renderer ends one stream
  and not the node.
- `api::link` tests (6, Rust) — over a real socket: a pushed snapshot reaches the store; the
  offline→online edge fires **once per connection**, not per snapshot; a worker that cannot stream is
  reported rather than waited on; an unreadable line surfaces instead of freezing the screen; a
  closed stream always says why; an absent worker never claims data.
- `api::store` tests (8) — the first snapshot has no rate; a rate is measured between two; a worker
  restart reads as zero rather than a negative spike; offline keeps and dates the picture; no rate
  is computed across an outage; the revision climbs even when nothing changed.
- `api::model` tests (8) — administered and hosting are independent; an unprobed endpoint is unknown
  rather than instant; a ratio with no denominator is unknown, not `0.00`; availability of nothing is
  unknown, not 100%; a closed game has no endpoint rather than an empty one.

## Acceptance criteria

1. ✅ A change on the node reaches the screen without the app asking (measured: 9 ms).
2. ✅ Every tile renders "—" when no worker has reported, and says why in the band.
3. ✅ `-1` latency never reaches a screen as `0 ms`.
4. ✅ A dead link is visibly distinct from a quiet node, within 25 s.
5. ✅ Throughput is never derived from React renders.
6. ✅ A worker too old to stream still works, and says it is being polled.
