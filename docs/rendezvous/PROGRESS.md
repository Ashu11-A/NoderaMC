# Rendezvous — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the rendezvous category. On every
     outcome-changing commit touching this category: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old
     note. -->

**Category:** rendezvous · **Last audit:** 2026-07-25 · Tasks completed: **2 / 3**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | The service binary | ✅ COMPLETED | 55 Rust tests; `RendezvousRelayIT` drives the real binary |
| [2](Task.2.md) | The Java transport | ✅ COMPLETED | Direct-first repaired 2026-07-24; renewal at half TTL |
| [3](Task.3.md) | Live cross-internet proof | ⏳ BLOCKED | Needs the live/NAT environment and the migration lane |

---

## 2. Milestone notes (newest first)

### 2026-07-24 — Direct-first was structurally unreachable, and is now repaired

Found by an end-to-end audit of the discovery chain, and worth recording in full because the code was
*correct in policy and inert in practice*.

`RendezvousPeerTransport` advertised **only** a RELAY candidate. `hasDirectCandidate` was therefore
false for every discovered peer, `Path.DIRECT` never entered the available set, and every byte on that
transport crossed the relay — the exact inversion the reference architecture warns about. The
selector's careful direct-over-punched-over-relayed preference had nothing to prefer.

Three fixes: the transport now publishes a **HOST candidate** derived from the direct transport's
listen route (which is why `listenRoute()` joined the `PeerTransport` seam); it substitutes that
address when a caller addresses a peer by node id alone; and it **renews its registration at half the
TTL** instead of silently vanishing from discovery after five minutes. The joiner is now wrapped in
the same transport as the host, so relay fallback stopped being one-sided — and therefore stopped
being useless.

The lesson generalises: a preference policy is inert unless something produces the option it prefers.
A test that asserts *a direct candidate exists when a direct listener exists* is worth more here than
one that asserts the ordering.

### 2026-07-19 — The service and transport land; L-23 and L-27 RETIRED

The standalone `nodera-rendezvous` service and the Java rendezvous transport closed the cross-NAT and
relay-fallback gap, superseding a jvm-libp2p transport plan that had never been built.

The service speaks the frozen rendezvous/relay family: signed-record registration with TTL and
trust-on-first-use identity binding, paged discovery, HMAC relay reservations validated statelessly,
and a tokio circuit bridge that meters bytes, duration, and idle time and tears down with a reason
code. The Java side composes direct-first and relay-fallback behind the same seam, with an
end-to-end cipher that leaves the relay carrying bytes it can neither read nor forge.

`RendezvousRelayIT` is the proof, and it uses the **real binary**: two relay-only peers register,
discover each other, exchange a `PeerJoin` and a keep-alive byte-exact across the encrypted circuit;
the reservation's byte ceiling tears the circuit down with the right reason; and the selector reports
the direct path when one is allowed.

Recorded at the time and still true: the *mechanism* is proven headlessly and over loopback; the real
cross-internet numbers ride [`Task.3.md`](Task.3.md) with the live environment.
