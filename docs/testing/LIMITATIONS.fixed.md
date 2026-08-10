# Testing — Limitations register (retired)

<!-- AI-AGENT-INSTRUCTION: A row lands here only with the evidence that retired it — the command
     that went green, and where its output is. Never delete a row; this file is the record. -->

**Category:** testing · **Last audit:** 2026-08-10

| Row | Retired | What was missing | Evidence |
|---|---|---|---|
| T-0 | 2026-07-29 | A live suite failure was a stage number on somebody's terminal. There was no structure a report could read, no machine-readable result, and no way to see the acceptance, benchmark and structure lanes together — so a change that passed every suite while doubling a benchmark and adding dead classes looked clean. | `dev.nodera.testkit.suite.StageResult` / `ScenarioResult` and `dev.nodera.testkit.report.RunReport`; `scripts/nodera-test.sh run` writes `build/reports/nodera/TEST-REPORT.md` + `test-report.json` |
| T-5 | 2026-08-10 | `LiveStack` started a dedicated server and two Minecraft clients and handed them straight back. Readiness was whatever stage happened to watch a log next, so a Gradle build that failed to compile gave the scenario a dead wrapper, the first wait after it burned its full timeout, and the run reported that the GAME had never said the thing it was asked for — the failure named the product and the cause was a build. The same shape as the tracker/rendezvous audit above, one process type over. | Every process the stack starts is now proven before it is returned: trackers and rendezvous by socket probe (`awaitListening`), workers by `ControlClient.awaitUp`, the dedicated server by its game port **and** an RCON round trip (a server that binds and does not answer RCON is not ready), a client by the existence of a game JVM carrying its own `<run>RunProgramArgs` token, and the companion launcher by surviving its own startup. Every failure names the process, its exit status when it has one, and quotes its log tail. Evidence: `LiveStackLivenessTest` in `library/java/testing/src/test/java/dev/nodera/testkit/harness/` — the two launcher-death cases fail on the pre-fix bodies (`aClientLauncherThatDies…`, `aServerLauncherThatDies…`) and pass after; `./gradlew :testing:test` green. |
