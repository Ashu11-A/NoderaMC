# Tracker — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: Rows arrive here from LIMITATIONS.md when their EXIT TEST is green, WITH
     the evidence that retired them. Never delete a row or soften an evidence cell. If a retired
     capability regresses, open a bug issue and add a NEW row to LIMITATIONS.md. -->

**Category:** tracker · **Last audit:** 2026-07-25 · Retired rows: **1**

| ID | Limitation | Retirement evidence | Owner | Retired |
|---|---|---|---|---|
| L-44 | The tracker was **embedded in a Java peer**, so the world list and announce surface died with its host peer — there was no always-on discovery infrastructure | The standalone Rust `nodera-tracker` serves the frozen discovery family plus the appended announce family. `TrackerServiceIT` spawns the **real release binary** and drives it from Java peers: two peers announce two worlds with per-world isolation; a JDK-`NodeIdentity`-signed announce is verified inside the service by `ed25519-dalek`; a tampered record is refused with `bad-signature` and never reaches the registry; a `STOPPED` announce removes a peer immediately; and — the row's actual exit — **a world whose every Java seeder has gone silent past the TTL is still listed by name, with its countdown and a DEAD verdict**, which the embedded tracker could not do. The embedded `TrackerService` and its tests were deleted; `PeerDirectory` and `ArchiveInventory` remain as peer-local caches | [1](Task.1.md) | 2026-07-19 |
