# Testing — Limitations register (open)

<!-- AI-AGENT-INSTRUCTION: Every row needs an owning task and an EXIT TEST — the command that goes
     green the day the row retires. "Permanent" is a banned classification. A row moves
     OPEN -> RETIRING -> RETIRED and then to LIMITATIONS.fixed.md carrying its evidence. Never
     delete a row. -->

**Category:** testing · **Last audit:** 2026-07-29

| Row | Status | What is missing | Owning task | Exit test |
|---|---|---|---|---|
| T-1 | OPEN | The converted scenarios have not all been executed live end to end since the conversion. The classes carry the same stages, evidence strings and timeouts as the shell suites they replace, and the tool builds and enumerates them, but a green nightly across the whole matrix is what turns "converted" into "proved". | [testing 1](Task.1.md) | a green `e2e-live` run over the full matrix |
| T-2 | OPEN | `NODERA-TEST DRIVE` publishes an action on the worker's event stream, but the mod does not yet consume `test.drive` — so a scenario drives a player through RCON (dedicated/Paper) rather than through its own worker. The role plumbing is what a driven player will use; the consumer is not written. | [testing 1](Task.1.md) | a scenario that moves a player with `NODERA-TEST DRIVE` alone and asserts the move in the game log |
| T-4 | OPEN | Five harness capabilities live in scenario support classes rather than in the harness, each marked `// HARNESS-GAP`: amending the *staged world's* `nodera-server.toml`, staging `options.txt` (without it `onboardAccessibility` defaults true and quick play never runs — the original CI "never joined" cause), killing the game JVM that is a child of the Gradle *daemon* rather than of the launcher, auditing errors from a mark rather than from the top of the file, and case-insensitive/last-match reads after a mark. Every scenario that needs them goes through the support class, so nothing is missing — but two places now describe one behaviour. | [testing 1](Task.1.md) | `grep -rn "HARNESS-GAP" java/testing/src` returns nothing |
| T-3 | OPEN | `scripts/lib/` still exists (`e2e-main.sh`, `e2e-server.sh`, `spark.sh`, the Python helpers). Nothing in the test lane uses it; `scripts/dev.sh --play` does. Until the developer playground is ported, two launchers describe the same topology and can drift. | [testing 1](Task.1.md) | `grep -rl e2e-main.sh scripts/` returns nothing |
