# Testing — Limitations register (open)

<!-- AI-AGENT-INSTRUCTION: Every row needs an owning task and an EXIT TEST — the command that goes
     green the day the row retires. "Permanent" is a banned classification. A row moves
     OPEN -> RETIRING -> RETIRED and then to LIMITATIONS.fixed.md carrying its evidence. Never
     delete a row. -->

**Category:** testing · **Last audit:** 2026-07-30

> **Audit 2026-07-30 — every live scenario since the conversion ran with no tracker and no
> rendezvous, and nothing said so.** `LiveStack` launched both services with
> `NODERA_TRACKER_BIND` / `NODERA_RENDEZVOUS_BIND`; the services take `*_BIND_ADDR`, and they refuse
> to start on any unrecognised `NODERA_<SERVICE>_*` variable — deliberately, so a typo cannot
> silently run a service on defaults. Both binaries printed one line and exited. The key was
> **introduced by the conversion itself** ([#120](https://github.com/Ashu11-A/NoderaMC/pull/120),
> 2026-07-29): the shell harness it replaced wrote a TOML config (`bind_addr = …`) and passed
> `--config`, so it never used that variable at all, and the env-var config lane it was written
> against had landed only two days earlier (`e2a80d1`).
>
> The reason it survived is the second half, and the more important one: **`startInfrastructure`'s
> own javadoc says it waits "until every one of them answers", and it only ever probed the workers.**
> A service that exited during startup left a stack that looked launched. Every symptom therefore
> appeared one layer up and pointed at the product — `world 'world' is on NO tracker — 0 of 1
> answered`, `Seeder lookup … 0 seeder(s), 0 routable`, mesh soaks that observed nothing — which is
> exactly the evidence several open rows in other categories were reasoning from.
>
> Fixed on both counts: the keys are corrected, and `awaitListening` now waits for each service to
> accept a connection, fails if the process has already exited, and puts the log tail in the failure
> message. First run after the fix: `nodera-tracker: listening on 127.0.0.1:25600`,
> `nodera-rendezvous: listening on 127.0.0.1:25601`, and a worker reporting
> `Seeder lookup … 1 seeder(s), 1 routable` — the first live discovery plane the Java harness has
> ever had. **Live evidence recorded before 2026-07-30 should be re-read with this in mind.**

| Row | Status | What is missing | Owning task | Exit test |
|---|---|---|---|---|
| T-5 | RETIRING | The harness launched services and never checked they were alive, so a service that refused its own configuration produced a stack that looked started (see the audit note above). `awaitListening` closes it for the tracker and the rendezvous. What is not closed: the same class starts a dedicated server and two Minecraft clients, and their readiness is asserted by scenario-specific log-watching rather than by one harness rule — so the *shape* of defect can still recur one process type over. | [testing 1](Task.1.md) | every process `LiveStack` starts is proven answering by the harness before a scenario's first stage runs, with the failure naming the process and its log tail |
| T-1 | OPEN | The converted scenarios have not all been executed live end to end since the conversion. The classes carry the same stages, evidence strings and timeouts as the shell suites they replace, and the tool builds and enumerates them, but a green nightly across the whole matrix is what turns "converted" into "proved". | [testing 1](Task.1.md) | a green `e2e-live` run over the full matrix |
| T-2 | OPEN | `NODERA-TEST DRIVE` publishes an action on the worker's event stream, but the mod does not yet consume `test.drive` — so a scenario drives a player through RCON (dedicated/Paper) rather than through its own worker. The role plumbing is what a driven player will use; the consumer is not written. | [testing 1](Task.1.md) | a scenario that moves a player with `NODERA-TEST DRIVE` alone and asserts the move in the game log |
| T-4 | OPEN | Five harness capabilities live in scenario support classes rather than in the harness, each marked `// HARNESS-GAP`: amending the *staged world's* `nodera-server.toml`, staging `options.txt` (without it `onboardAccessibility` defaults true and quick play never runs — the original CI "never joined" cause), killing the game JVM that is a child of the Gradle *daemon* rather than of the launcher, auditing errors from a mark rather than from the top of the file, and case-insensitive/last-match reads after a mark. Every scenario that needs them goes through the support class, so nothing is missing — but two places now describe one behaviour. | [testing 1](Task.1.md) | `grep -rn "HARNESS-GAP" java/testing/src` returns nothing |
| T-3 | OPEN | `scripts/lib/` still exists (`e2e-main.sh`, `e2e-server.sh`, `spark.sh`, the Python helpers). Nothing in the test lane uses it; `scripts/dev.sh --play` does. Until the developer playground is ported, two launchers describe the same topology and can drift. | [testing 1](Task.1.md) | `grep -rl e2e-main.sh scripts/` returns nothing |
