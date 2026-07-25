# Minecraft — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: Rows arrive here from LIMITATIONS.md when their EXIT TEST is green, WITH
     the evidence that retired them. Never delete a row or soften an evidence cell — this is the audit
     trail that lets a reader check a retirement instead of trusting it. If a retired capability
     regresses, open a bug issue and add a NEW row to LIMITATIONS.md; do not edit history here. -->

**Category:** minecraft · **Last audit:** 2026-07-25 · Retired rows: **1**

| ID | Limitation | Retirement evidence | Owner | Retired |
|---|---|---|---|---|
| L-31 | The in-game diagnostics HUD shipped its session and network panels, but the region-ownership and entity panels rendered **placeholders** — the providers behind them were stubs, so the most useful panels showed nothing regardless of what the node was doing | Both data halves exited with **live** evidence. The entities panel is fed by a live entity-control provider — observed live at 239 entities across 12 delegated regions with versions advancing every flush. The region-ownership panel is fed by a live ownership provider — observed live at `14 owned / 896 owned chunks`, with the unassigned placeholder retired. While any validation lane is active the panel shows the node's real leases (owned / validating / replica, with chunks, epoch, and expiry); the empty placeholder renders **only** when no lane is active, which is correct behaviour rather than a gap. The stub provider was replaced by the live singleton registration | [3](Task.3.md) | 2026-07-24 |
