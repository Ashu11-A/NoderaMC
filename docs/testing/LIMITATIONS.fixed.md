# Testing — Limitations register (retired)

<!-- AI-AGENT-INSTRUCTION: A row lands here only with the evidence that retired it — the command
     that went green, and where its output is. Never delete a row; this file is the record. -->

**Category:** testing · **Last audit:** 2026-07-29

| Row | Retired | What was missing | Evidence |
|---|---|---|---|
| T-0 | 2026-07-29 | A live suite failure was a stage number on somebody's terminal. There was no structure a report could read, no machine-readable result, and no way to see the acceptance, benchmark and structure lanes together — so a change that passed every suite while doubling a benchmark and adding dead classes looked clean. | `dev.nodera.testkit.suite.StageResult` / `ScenarioResult` and `dev.nodera.testkit.report.RunReport`; `scripts/nodera-test.sh run` writes `build/reports/nodera/TEST-REPORT.md` + `test-report.json` |
