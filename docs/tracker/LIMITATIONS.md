# Tracker — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the tracker category. "Permanent" is banned. Every §B row has
     an owning task and an EXIT TEST. A newly discovered limitation enters §B as OPEN with a path, an
     owner, and an exit test BEFORE the discovering PR merges. Never delete a row — move it to
     LIMITATIONS.fixed.md with its evidence. Note the §C entry: some properties that LOOK like
     limitations are the trust model working as designed, and must not be "fixed". -->

**Category:** tracker · **Last audit:** 2026-07-25 · Open or retiring rows: **0**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

None owned by this category. The tracker's constraints are all in §C below: they are consequences of
the trust model, not facts of the platform.

---

## §B — Staged capabilities

**Empty.** No open or retiring rows.

Two adjacent gaps are tracked in the categories that own them, not here:

- The mod's periodic announce loop is constructed but not scheduled on a timer — tracked with the
  live GUI pass in [`../minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md) (L-45), and moved into
  the always-on worker by [`../worker/Task.3.md`](../worker/Task.3.md).
- Operator hardening (`STATS`, listing policy, deployment docs) is ordinary remaining scope in
  [`Task.3.md`](Task.3.md), not a limitation: nothing about the network is degraded by its absence.

---

## §C — By design (not limitations, and must not be "fixed")

<!-- AI-AGENT-INSTRUCTION: Do NOT convert these into §B rows and do not "improve" them away. Each is
     the trust model working correctly. A proposal to make the tracker more trusted is a design
     regression, not a fix. -->

| Property | Why it is not a limitation |
|---|---|
| A tracker can hide peers or invent unreachable ones | It cannot forge state or identities: announces are Ed25519-signed and world state verifies by hash. A lying tracker degrades *discovery* and nothing else — exactly the boundary the trust model draws |
| A tracker outage makes worlds hard to find | Discovery degrades; the mesh, committee validation, and stored state are unaffected. This is tested as a degradation path, not treated as an outage of the system |
| Manifests and directory metadata are plaintext | Structure metadata (world ids, piece counts and sizes, KDF parameters, cadence) is visible; block contents never are. Encryption is a content-plane property ([`../network/Task.8.md`](../network/Task.8.md)) |
| Signed records stop impersonation, not identity farming | Sybil resistance is a membership-economics problem owned by [`../engine/Task.12.md`](../engine/Task.12.md), not something a discovery service can solve |
| No full-scrape endpoint | Deliberate omission: enumerating every world and peer is the primitive an abuser wants |

---

## Reading guide for the implementing model

- The single rule that governs this category: **no tracker endpoint may become load-bearing for
  correctness.** If a proposed feature would make a wrong tracker answer produce a wrong world, the
  feature is wrong.
- Test the degradation path whenever you touch this service: tracker down ⇒ discovery degrades, mesh
  and state unaffected.
- Reference: [`REFERENCE.md`](REFERENCE.md) §23 (persistence) and §26 (abuse defences).
