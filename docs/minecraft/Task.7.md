# Minecraft Task 7 — Companion Presence Gate

<!-- AI-AGENT-INSTRUCTION: The gate must FAIL CLOSED with an ACTIONABLE message — never a stack trace,
     never a silent no-network degrade. Version skew must be classified ("update the app" vs "update
     the mod"), because an unclassified mismatch sends the user to the wrong place. Keep this header's
     status accurate. -->

**Status:** ✅ COMPLETED
**Category:** minecraft · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [worker 1](../peer/Task.1.md)
**Consumed by:** every player; it is what makes the always-on node an actual guarantee

---

## Goal

Make "a Nodera node is always on" true by construction: Minecraft refuses to start without the peer
worker, and says exactly what to do about it.

## Status detail

Complete. The gate probes the worker's loopback control endpoint at client setup; if it is absent,
NeoForge aborts with an actionable error carrying the install URL. Version skew is classified so the
message says whether to update the app or the mod. A verified link object holds the connection for the
rest of the session, and the requirement defaults **on**, with an opt-out in the client configuration
for development. The dedicated-server distribution enforces the same gate.

## Dependencies

- [worker 1](../peer/Task.1.md) — the endpoint being probed.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Presence probe at client setup against the loopback control endpoint | ✅ |
| 2 | Actionable abort with an install URL when the worker is absent | ✅ |
| 3 | Version-skew classification ("update the app" vs "update the mod") | ✅ |
| 4 | A verified link held for the session | ✅ |
| 5 | Requirement defaults on, with a configuration opt-out | ✅ |
| 6 | The same gate on the dedicated-server distribution | ✅ |

## Design

**A requirement that is not enforced is a hope.** Every property the project promises — a world stays
listed, its data stays served, committees keep quorum through a logout — depends on a node that
outlives the game. If the game can start without one, those properties hold only for players who
happened to install the companion, which means they do not hold.

**Fail closed, and say what to do.** The failure a user actually meets is "the worker is not running".
A stack trace there is a support burden; a silent degrade is worse, because the game appears to work
while the node does not exist. The abort names the problem and links the install.

**Classify skew or send people to the wrong place.** A protocol-version mismatch has two possible
fixes, and guessing wrong wastes the user's time. The probe answers with its version, so the message
can be specific.

**Loopback probe, real socket.** The gate opens an actual connection rather than checking for a
process or a lock file — the thing it needs is *an endpoint that answers*, and that is exactly what it
tests. Its own test uses a real loopback server socket for the same reason.

**Opt-out exists for development, and defaults off nowhere else.** Turning the requirement off is a
deliberate local choice, not a fallback the product ships in.

## Files

- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/{CompanionGate,CompanionClient,CompanionLink,CompanionProtocol}.java`

## Testing

- `CompanionGateTest` — against a **real** loopback server socket: present ⇒ the gate passes; absent
  ⇒ an actionable abort; a skewed version ⇒ the correct classification.
- CI: the companion job runs the gate **both ways** as part of the app's acceptance
  ([`app/Task.4.md`](../app/Task.4.md)).

## Acceptance criteria

1. ✅ Minecraft starts when the worker is present.
2. ✅ Minecraft aborts with an actionable, linked message when it is absent.
3. ✅ Version skew is classified correctly in both directions.
4. ✅ The requirement defaults on and is enforced on both distributions.
5. ✅ The gate is verified in both directions in CI.

## Limitations

None owned. The shared installer-and-continuity acceptance is **L-47** in
[`app/LIMITATIONS.md`](../app/LIMITATIONS.md).
