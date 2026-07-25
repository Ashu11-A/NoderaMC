# Worker — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the worker category. "Permanent" is banned. Every §B row has
     an owning task and an EXIT TEST and retires only when that exit test is green. Never delete a
     row — move it to LIMITATIONS.fixed.md with its evidence. §C lists properties that are the design
     working correctly; do not convert them into §B rows. -->

**Category:** worker · **Last audit:** 2026-07-25 · Open or retiring rows: **1**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

None owned. The worker exists precisely to remove an envelope constraint that used to be real — "a
node lives only while its player has Minecraft open" — rather than to hide one.

---

## §B — Staged capabilities

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-41 | The always-on out-of-game process exists and its core scenario is proven: `CompanionCrashSurvivalIT` boots the actual worker distribution as a separate daemon, seeds a world archive through the control socket, **SIGKILLs** a co-located stand-in game process (no shutdown hooks — exactly a crashed game), and the daemon keeps answering control verbs with every seeded piece still maintained. What remains is scope rather than principle: the periodic tracker announce and rendezvous registration loops must run on the **worker's** timer independent of any game process, and validated-lane **region-piece** seeding must join the whole-save archive seeding it already does | [3](Task.3.md) | The worker keeps a world announced **and** seeded — whole-save archive and validated-lane region pieces — on its own timer, after the driving game client has disconnected | RETIRING |

---

## §C — By design (not limitations, and must not be "fixed")

<!-- AI-AGENT-INSTRUCTION: Do NOT convert these into §B rows. Each is a locked decision or the trust
     model working correctly. A proposal that adds a second region engine, or that makes the control
     channel remotely reachable, is a design regression and must be refused. -->

| Property | Why it is not a limitation |
|---|---|
| The worker is a Java process, not a lightweight Rust daemon | Option B is **locked**. A Rust-native peer is forbidden from re-executing regions by the single-engine determinism rule — a second engine implementation would have to stay bit-identical forever. A Rust seed/relay/route-only mode remains a possible later addition; it can never be a validator |
| Minecraft refuses to start without the worker | This is the gate working ([`../minecraft/Task.7.md`](../minecraft/Task.7.md)). The alternative — a game that runs while its node does not — is the failure mode the whole category exists to remove. The gate fails **closed with an actionable message**, never a stack trace or a silent no-network degrade |
| The control channel is unauthenticated | It is **loopback-only** local IPC. Binding it wider would turn it into a remote control plane for the node; the boundary is the bind address, and it is not negotiable |
| Requiring the worker does not make it trusted | Peers verify everything it serves by hash and signature. Requiring it locally is a persistence and reachability convenience, never a new trust anchor |
| The worker holds a world's signing key | It **is** the author for the worlds it hosts. That is why author-only re-key is a cryptographic statement rather than a UI convention. Single-signer genesis is a separate concern, retired in [`../engine/LIMITATIONS.fixed.md`](../engine/LIMITATIONS.fixed.md) (L-20) |

---

## Reading guide for the implementing model

- The governing rule: **no second region engine, in any language, ever.**
- Never bind the control listener to a routable interface.
- A world must never be *announced but unserved*: announce after content is seedable, not before.
- When a design choice trades against the §B row, prefer the choice that keeps the exit test
  achievable.
