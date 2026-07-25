# App — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: Rows arrive here from LIMITATIONS.md when their EXIT TEST is green, WITH
     the evidence that retired them. Never delete a row or soften an evidence cell. If a retired
     capability regresses, open a bug issue and add a NEW row to LIMITATIONS.md — do not edit history
     here. -->

**Category:** app · **Last audit:** 2026-07-25 · Retired rows: **1**

| ID | Limitation | Retirement evidence | Owner | Retired |
|---|---|---|---|---|
| L-47 (first form) | There was no automated job that built the app, exercised the mod's presence gate in both directions, or proved a hosted world survived its game process — every one of those claims rested on a manual run | The companion CI job builds the Tauri app end to end (UI build plus a cargo release, with the worker distribution staged and bundled), runs the gate **both ways** (`CompanionGateTest`: worker present ⇒ Minecraft starts; worker absent ⇒ an actionable abort), and proves hosted-world survival via `WorldContinuityIT` and `CompanionCrashSurvivalIT` — the latter keeping the daemon seeding after a co-located game process is SIGKILLed with no shutdown hook run. En route the job surfaced and fixed four packaging gaps that only a clean-checkout build could reveal: distribution staging, a gitignored UI build, gitignored bundle icons, and a gitignored tray icon. **A narrower row of the same id remains open** for the genuinely cross-machine half — installer-based install plus a join from a second, separately networked side | [4](Task.4.md) | 2026-07-23 |
