# Worker — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: Rows arrive here from LIMITATIONS.md when their EXIT TEST is green, WITH
     the evidence that retired them. Never delete a row or soften an evidence cell. If a retired
     capability regresses, open a bug issue and add a NEW row to LIMITATIONS.md. -->

**Category:** worker · **Last audit:** 2026-07-25 · Retired rows: **1**

| ID | Limitation | Retirement evidence | Owner | Retired |
|---|---|---|---|---|
| L-48 | The always-on node could not **validate** regions: a companion-only peer was seeder, relay, and router capable only, and the entire validation stack was runtime-unreferenced outside the mod — the `simulationmsg` wire family had no live consumer at all | `WorkerValidationService` runs committee re-execution **out of game** and participates in quorum over the same `PeerTransport` the membership session rides. `WorkerQuorumValidationIT` proves the exit: three **companion-only** worker nodes (no Minecraft process) form a committee over registered peer runtimes — the primary proposes, validators re-execute with **the** engine and vote over the wire, the 2-of-3 quorum commits, every worker converges on the byte-identical root **matching the reference engine**, and each persists the co-signed certificate in its own store, so certified state flows peer to peer. Primary loss promotes a validator under epoch+1 and the surviving 2-member committee keeps committing. The formerly orphaned fallback lane executes an unassigned-region action through the server lane and the soak ratio holds. `HeadlessPeerMain` constructs the lane and `STATE` reports live validation counters | [4](Task.4.md) | 2026-07-21 |
