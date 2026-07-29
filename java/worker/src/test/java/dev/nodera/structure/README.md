# `dev.nodera.structure` — the structural code report

<!-- AI-AGENT-INSTRUCTION: Two rules are load-bearing. (1) The budgets in
     fixtures/structure/budget.json ratchet DOWNWARDS ONLY — never raise one to make a build green;
     delete the dead code, or teach DeadCodeAnalysis#analysable the rule that proves the code is
     reachable. (2) Never make a finding "go away" by loosening a verdict. If the running worker
     contradicts the analysis (report § 4.3), the ANALYSIS is wrong and gets a new rule — that is how
     the supertype-resolution and <clinit>-root rules were found. -->

Reads every module's compiled bytecode for the reference graph and for work that costs more than it
looks like it does, then runs the **real `nodera-headless` worker under a JDWP debugger** to see
which of that code actually executes.

| File | Job |
|---|---|
| `CodeGraph` | ASM scan of `java/*/build/classes/java/{main,test,jmh}`: reference graph (including `invokedynamic` lambda targets), class hierarchy, in-loop cost findings, estimated bytecode size |
| `DeadCodeAnalysis` | three verdicts — *never referenced*, *only tests and benchmarks call it*, *no entry point reaches it* — plus what is deliberately unanalysable |
| `HeadlessDebugProbe` | launches the worker with `-agentlib:jdwp`, attaches over JDI, records class-prepare + method-entry events while driving twelve control verbs |
| `StructureReport` | renders `STRUCTURE.md` and `structure.json` |
| `StructuralReportTest` | the suite, and the budget ratchet |

```bash
./gradlew :worker:structureReport                          # analysis + probe (~1 min)
./gradlew :worker:structureReport -Pstructure.debug=false  # static half only (~5 s)
```

Outputs land in the repository's `build/reports/nodera/`, beside `BENCHMARKS.md`.

## Why it lives in a test source set of `:worker`

It needs the compiled output of **every** module (to know who calls what) and the ability to launch
the real worker process (to know what runs). `:worker` is the only module that can have both, and
nothing depends on `:worker`, so pointing it at the whole tree creates no cycle. It is tagged
`structure` and excluded from `:worker:test`, so one module's unit-test feedback never waits on nine
modules compiling; `structureReport` declares those compilations as task dependencies instead.

## What is never called dead

Anything whose caller could be outside our bytecode: methods satisfying an external interface (JDK,
NeoForge, JUnit), compiler-generated members, mixins (wired by JSON, applied by a transformer),
constant holders (javac inlines `static final` values into every caller, so the holder has no
incoming reference), private no-argument constructors, and every member of a class whose supertypes
are not on the classpath. Those are counted as *unanalysable* — the report never claims more coverage
than it has.

## Why a debugger and not a coverage agent

A coverage agent needs the build to instrument classes; a sampling profiler says where time went but
not which services were ever touched. JDI watches the **unmodified** process the companion app
supervises. Method-entry tracing deoptimises the filtered classes, so the probe reports **counts and
coverage only, never durations** — speed is `:peer:jmh`'s job, on an uninstrumented JVM.

Full design and the first run's findings: [`docs/network/Task.15.md`](../../../../../../../../docs/network/Task.15.md).
