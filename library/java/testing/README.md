# `java/testing`

<!-- AI-AGENT-INSTRUCTION: This module is a TEST LIBRARY consumed by other modules' test source sets.
     Nothing here may leak into production code, and nothing here may be Minecraft-aware. Fixture
     files under fixtures/wire/ are GENERATED, never hand-edited — regenerate them through the writer
     rather than adjusting bytes. Update this file when a helper is added or its contract changes. -->

**The shared test library.** Small, deliberately: the project's testing strategy puts almost all of
its weight on real objects, real sockets, and real processes, so the helpers here exist only where a
substitute genuinely buys something.

- **Depends on:** `core`, `engine`, `transport`.
- **Depended on by:** the test source sets of the other Java modules.
- **Docs:** [`docs/engine/TESTING.md`](../../docs/engine/TESTING.md) ·
  [`docs/network/TESTING.md`](../../docs/network/TESTING.md)

---

## Architecture

```
dev.nodera.testkit
├── LoopbackTransport      an in-JVM PeerTransport for multi-peer scenarios without sockets
├── FakeRegion             region/snapshot builders for engine and consensus tests
├── FixtureWriter/Reader   golden canonical frames — emit from Java, compare byte-exactly
├── harness/               the live stack: topology, ports, processes, control clients
├── suite/                 the scenario contract, the registry, the runner and its report
└── scenario/              one class per live acceptance scenario
```

**A scenario may build something before the stack does.** `Scenario.prepare` runs *before*
`topology()` is asked for, and `cleanUp` runs in a `finally` after teardown. Almost nothing needs it
— setting up inside a stage keeps the work in the report — and it exists for the one case where it
cannot be done later: `CrossMachineScenario` creates two container networks and only then knows which
address the stack must bind, and the stack binds before `run` is called. `SeparateNetwork` is that
lab: two Docker bridges, a router container between them, and a joining peer whose default route is
deleted, so "cross-machine" is a fact about the run rather than a description of a diagram.

## Why it is shaped this way

**`LoopbackTransport` implements the real seam.** It is not a mock: it is a `PeerTransport` like any
other, so a multi-peer scenario written against it exercises the same call paths as a socket run.
Scenarios that need real network behaviour use real TCP instead — several deliberately do, because
handshakes, bind failures, and teardown reasons only exist on a real socket.

**Fixtures are generated, never authored.** `FixtureWriter` emits golden frames from the Java
canonical encoder; `rust/nodera-codec` re-encodes them byte-exactly. Hand-editing a fixture would
break the only mechanism that keeps the two implementations honest, so a fixture change is always a
regeneration.

**Deliberately thin.** The project's crash, adversarial, and consensus proofs use forcibly killed
JVMs, genuinely adversarial peers on the wire, and the real service binaries. Every helper added here
is a small step away from that, so the bar for adding one is high.

## Rules

- Nothing here may be referenced from production code.
- No Minecraft or NeoForge types.
- A helper that would let a test pass without exercising the real seam does not belong here.

## Tests

49 tests covering the helpers themselves — a broken test library produces false confidence everywhere
else.

```bash
./gradlew :testing:test
```
