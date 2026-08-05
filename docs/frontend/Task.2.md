# Frontend Task 2 — Live Metrics Dashboard

<!-- AI-AGENT-INSTRUCTION: The dashboard RENDERS; it does not compute. Parsing must tolerate unknown
     fields (serde defaults) because worker STATE fields are additive — a newer worker must degrade to
     fewer panels, never to an error. If a panel needs a rule, put the rule in the worker where it can
     be asserted headlessly. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** frontend · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [worker 2](../peer/Task.2.md)
**Consumed by:** players

---

## Goal

Show the player what their node is doing for the network: chunks and data maintained, bytes sent and
received, the peers currently exchanging data, and the worlds this node hosts or is connected to —
from **real** worker data, refreshed once a second.

## Status detail

Complete. A one-second pump probes the worker for liveness and fetches `STATE`, parses it into the
`Metrics` struct, and emits it to the UI. The dashboard was later rebuilt as a torrent-client-shaped
**Info / State / Peers / Trackers / Pieces** tab set inside VPN-client connection chrome, which is the
mental model players already have for "a background process moving data on my behalf".

Two data paths that used to show placeholder zeros now show real numbers: per-peer throughput
(previously hardcoded), and the piece map (whose source had **never been called** anywhere until a
`PIECES` verb, a parser, and a feed were added).

The crate has **188 tests** (green 2026-07-28; see [TESTING.md](TESTING.md)) covering bitmap
decoding, parsing tolerance, the log ring, system sampling, the configuration lane, and the
enforcement-badge invariants.

## Dependencies

- [worker 2](../peer/Task.2.md) — the control verbs and the `STATE` payload.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `control.rs` — probe + `fetch_state`, a strict mirror of the worker protocol | ✅ |
| 2 | `metrics.rs` — the `Metrics` struct the UI renders | ✅ |
| 3 | One-second pump emitting to the UI, with daemon liveness | ✅ |
| 4 | Info / State / Peers / Trackers / Pieces tabs | ✅ |
| 5 | Piece-map bitmap decoding matching Java's byte order | ✅ |
| 6 | Configuration surface with honest applied / rejected / restart-required badges | ✅ |
| 7 | Log ring + system sampling | ✅ |

## Design

**Render, do not compute.** Every number on screen is produced by the worker, where it is asserted
headlessly on the Java gate. That keeps the app's test surface to parsing — and keeps a UI change from
being able to make the dashboard *wrong* rather than merely ugly.

**Parse tolerantly, by contract.** Worker `STATE` fields are additive, so the parser uses defaults for
unknown fields. A worker newer than its dashboard shows fewer panels; it never shows an error. The
corollary is a hard rule on the worker side: never repurpose an existing field name.

**Byte order is a real bug class.** The piece bitmap is produced by Java's `BitSet` and consumed by
Rust, and the two disagree about bit ordering unless someone decides. The decoder is tested against
that exact contract, including bounded, short, and undecodable bitmaps — a mis-decoded bitmap would
render a plausible but wrong picture, which is worse than rendering nothing.

**Badges must not lie.** A setting the node cannot honour gets a distinct muted "not supported" badge
carrying the worker's own reason — deliberately **not** the amber "not enforced yet", which implies
the feature is coming. Two connection settings are permanently in this state and are kept in the UI on
purpose: removing them would silently drop values users already saved and would hide that the
limitation is known ([`LIMITATIONS.md`](LIMITATIONS.md) L-56).

**Degrade, never crash-loop.** When the worker is down the tray shows offline and the dashboard shows
the daemon as down. A supervisor that restarts on every failed probe would hammer a worker that is
merely slow to start.

## Files

- `app/src/{control,metrics}.rs`
- `app/ui/src/App.tsx`, `ui/src/panels/`, `ui/src/ipc.ts`

## Testing

- Piece-bitmap decode matching Java's `BitSet` byte order; bounded, short, and undecodable bitmaps.
- Additive-field tolerance against a golden `STATE` JSON.
- Control-socket error surfacing and read timeout.
- `Settings → WorkerConfig` golden JSON; the worker-environment spawn pairs; a power-state truth
  table.
- Enforcement invariants: coverage of every badge state, and "live only if confirmed".
- Log ring and system sampling.

## Acceptance criteria

1. ✅ The dashboard renders real worker metrics, not zeros.
2. ✅ Parsing tolerates unknown fields and surfaces socket errors honestly.
3. ✅ The piece map decodes byte-compatibly with the Java producer.
4. ✅ Unsupported settings are badged distinctly from unenforced ones.
5. ✅ The app degrades gracefully when the worker is down.

## Limitations

- **L-56** — two connection settings that cannot be honoured as specified, kept in the UI and badged
  rather than deleted. See [`LIMITATIONS.md`](LIMITATIONS.md).
