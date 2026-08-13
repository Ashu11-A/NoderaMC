# Testing — Limitations register (open)

<!-- AI-AGENT-INSTRUCTION: Every row needs an owning task and an EXIT TEST — the command that goes
     green the day the row retires. "Permanent" is a banned classification. A row moves
     OPEN -> RETIRING -> RETIRED and then to LIMITATIONS.fixed.md carrying its evidence. Never
     delete a row. -->

**Category:** testing · **Last audit:** 2026-08-10

> **Audit 2026-08-10 — no developer with Nodera installed could run a single scenario, and the
> evidence of that was filed as a stale test stack.** `Topology` built every run on the product's own
> port block: tracker 25600, rendezvous 25601, worker control 25610+i, P2P 25620+i. The companion app
> binds 25610, so the preflight refused the run — and said only `port 25610 is still held …
> (scripts/dev.sh? a stale client JVM?)`, which sends a reader hunting a leftover that does not
> exist. **The live report of 2026-08-07 in `build/reports/nodera/TEST-REPORT.md` — 0 passed, 17
> failed, every failure `port 25600 is still held after 60s` — is this bug, not a leaked stack.**
> Nothing may be concluded from that report about any scenario's behaviour: not one of them started.
>
> Fixed by giving the harness its own block (26500 by default, probed and announced at startup) and
> by making the preflight name the holding process and classify it. Both halves are proved by a pair
> of runs made with the installed app still holding 25610 — see the 2026-08-10 note in
> [`PROGRESS.md`](PROGRESS.md) ([#266](https://github.com/Ashu11-A/NoderaMC/issues/266)).

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

## §B — Staged capabilities (the burn-down list)

<!-- This heading was missing until 2026-08-05, and its absence was not cosmetic. Nine of the ten
     registers under docs/ split into `## §A` (envelope constraints, which never burn down) and
     `## §B` (staged capabilities, which do). This one had neither, so any reader — human or
     tool — that located staged rows by their `## §B` heading read this register as EMPTY and
     silently dropped all six rows below. `web/scripts/build-status.mjs` did exactly that until it
     grew a fallback to the first table under the H1, and a register that reports zero open rows
     while holding six is the most expensive shape of wrong a limitations register has.

     The rows are unchanged: same ids, same statuses, same owning tasks, same exit tests. Only the
     heading they live under is new. There is no `## §A` because this register declares no envelope
     constraints — an absent section is honest; an empty one would not be. -->

| Row | Status | What is missing | Owning task | Exit test |
|---|---|---|---|---|
| T-1 | OPEN | The converted scenarios have not all been executed live end to end since the conversion. **2026-08-10: the full matrix has now been dispatched — twice — and it is not green.** Run [31396175753](https://github.com/Ashu11-A/NoderaMC/actions/runs/31396175753) was 3 passed / 16 failed over the nineteen unattended scenarios; the per-leg table with a cause and a class for every one is [TESTING.md](TESTING.md) §1.1. `endpoint`, `folia` and `plugins` passed for the first time on any machine. Sixteen failures resolved to three causes, thirteen of them one: the staged dedicated server carried no `[companion]` block, fell back to the product's own 25610, and crashed in `ServerStartedEvent`. Three preconditions of this row also landed that day — the plan job had had no JDK for twelve consecutive nightlies and dispatched nothing ([#142](https://github.com/Ashu11-A/NoderaMC/issues/142)); its matrix was a hand-kept thirteen of nineteen; and the harness bound the product's own ports, so on any machine with Nodera installed every scenario was refused before its first stage ([#266](https://github.com/Ashu11-A/NoderaMC/issues/266), and the 0-passed/17-failed report of 2026-08-07, which recorded that bug and not any scenario's behaviour). Remaining: a dispatch in which all nineteen are green. T-6 is the known obstacle for the client-bearing legs on a small box, and [#272](https://github.com/Ashu11-A/NoderaMC/issues/272) is a CDN flake that can fail any leg at random. | [testing 1](Task.1.md) | a green `e2e-live` run over the full matrix |
| T-2 | OPEN | `NODERA-TEST DRIVE` publishes an action on the worker's event stream, but the mod does not yet consume `test.drive` — so a scenario drives a player through RCON (dedicated/Paper) rather than through its own worker. The role plumbing is what a driven player will use; the consumer is not written. | [testing 1](Task.1.md) | a scenario that moves a player with `NODERA-TEST DRIVE` alone and asserts the move in the game log |
| T-4 | RETIRING | The five capabilities the row named are in the harness now: `LiveStack.stageClientOptions` + `LiveStack.setHostConfig`, `ManagedProcess.findByToken` (carrying the `/proc` full-command-line read and the Gradle-daemon exclusion), and `LogWatcher.auditErrorsAfter` / `linesAfter` / `lastMatchAfter` / `matchesAfter` / `containsAfterIgnoringCase`. `ServerLogs` is deleted; `HostWorldSupport`'s path-based readers are one-line delegates. **Remaining: the exit test as written was a tree-wide grep, and five markers it also caught describe a capability this row never did** — starting a process `LiveStack` has no slot for. Those moved to T-7 rather than being absorbed, so the UNION of T-4's and T-7's exit tests is still the original grep returning nothing; neither was loosened. | [testing 1](Task.1.md) | `grep -rn "HARNESS-GAP" library/java/testing/src` returns nothing outside the five sites T-7 owns (`ProfileScenario`, `ServerEndpointSupport` ×2, `ServerVanillaBot`, `TelemetryScenario`) |
| T-3 | OPEN | `scripts/lib/` still exists (`e2e-main.sh`, `e2e-server.sh`, `spark.sh`, the Python helpers). Nothing in the test lane uses it; `scripts/dev.sh --play` does. Until the developer playground is ported, two launchers describe the same topology and can drift. | [testing 1](Task.1.md) | `grep -rl e2e-main.sh scripts/` returns nothing |
| T-6 | RETIRING | The diagnosis half is done and the harness no longer blames the lane for the machine: `LogWatcher.awaitWithLagGuard` reads vanilla's own `Can't keep up! … N ticks behind` from the host's log and, above 200 ticks, fails with "the host server fell N ticks behind … the machine, not the lane, is why '<needle>' never appeared". It is applied at `continuity` S2c/S3, `ownership` O1/O2, `churn` C2, `crash` X2/X3, `mesh-soak` S2, and to `awaitMemberNodes`, which all four host-client scenarios share. `continuity`'s RAM floor rose 6 → 8 GiB, so a box that cannot sustain the topology says so up front instead of failing at S2c forty minutes in. **Remaining: no live demonstration.** The 14 GB development box reports ~4-6 GiB available and now SKIPS `continuity` rather than reaching S2c, so the branch the exit clause names has been proved by `LogWatcherLagGuardTest` (including the negative cases) and not yet by a run. | [testing 1](Task.1.md) | `continuity` completes S5 on the reference machine, **or** the harness fails S2c with "the host server fell N ticks behind" when the game is the cause rather than the lane |
| T-7 | OPEN | Five `// HARNESS-GAP` markers describe one capability the harness still has no answer for: starting a process `LiveStack` was not designed a slot for. A foreign Bukkit/Folia server JVM and an endpoint-owned worker (`ServerEndpointSupport.java:389,434`), the telemetry collector and that scenario's own worker (`TelemetryScenario.java:297`), spark's mod jars staged into a run directory (`ProfileScenario.java:88`), and a real vanilla client the in-process bot cannot be (`ServerVanillaBot.java:174`). Each owns its own teardown, so a scenario that fails halfway can leave a JVM behind that `LiveStack.close` knows nothing about. Split out of T-4 on 2026-08-10: T-4 named five capabilities and its exit test was a tree-wide grep that caught these five as well. | [testing 1](Task.1.md) | `grep -rn "HARNESS-GAP" library/java/testing/src` returns nothing |
