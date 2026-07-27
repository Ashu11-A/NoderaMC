# Tracker — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the tracker category. "Permanent" is banned. Every §B row has
     an owning task and an EXIT TEST. A newly discovered limitation enters §B as OPEN with a path, an
     owner, and an exit test BEFORE the discovering PR merges. Never delete a row — move it to
     LIMITATIONS.fixed.md with its evidence. Note the §C entry: some properties that LOOK like
     limitations are the trust model working as designed, and must not be "fixed". -->

**Category:** tracker · **Last audit:** 2026-07-27 · Open or retiring rows: **2**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

None owned by this category. The tracker's constraints are all in §C below: they are consequences of
the trust model, not facts of the platform.

---

## §B — Staged capabilities

| ID | Gap | Why it is not permanent | Elimination path | Owner | Exit test | Status |
|---|---|---|---|---|---|---|
| L-81 | The self-update lane verifies release **integrity, not provenance**: the digest it checks comes from the same release as the binary, so whoever can publish to the release can publish a matching digest | Signing is a release-process change, not an architectural one — the verification seam (`update::stage`) already refuses a mismatch, so a signature check slots in beside it | Sign `SHA256SUMS` in `release-latest.yml`, ship the public key with the binary, and verify the signature before the digest | [5](Task.5.md) | a test that a validly-digested but wrongly-signed manifest is refused by `update::check` | OPEN |
| L-82 | The updater fetches over a `curl` subprocess, so a host without `curl` on `PATH` cannot self-update | The `Fetcher` trait is the seam; a native HTTPS implementation is a dependency decision, deliberately deferred rather than pulling a TLS stack into a service whose whole point is to be small | Add a `Fetcher` backed by a Rust HTTPS client behind a cargo feature, keeping `CurlFetcher` as the default | [5](Task.5.md) | `update` tests pass with the native fetcher selected, and `CurlFetcher`'s absence message stays asserted | OPEN |

Both are confined to the update lane: with `update_channel` empty — the default — neither can affect a
running service at all.

Two adjacent gaps are tracked in the categories that own them, not here:

- The mod's periodic announce loop was constructed but never scheduled on a timer. It moved into the
  always-on worker ([`../worker/Task.3.md`](../worker/Task.3.md)), where the timer belongs — an
  announce that stops when the game closes is exactly the gap that task exists to remove — and the
  worker's L-41 row retired on 2026-07-26 with the heartbeat proven to re-read what this node holds
  on every announce.
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
