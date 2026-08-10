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
└── FixtureWriter/Reader   golden canonical frames — emit from Java, compare byte-exactly
```

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

## The live harness binds its own port block, never the product's

`dev.nodera.testkit.harness.Topology` gives a run a block of ports at `portBase + offset`, and the
base defaults to **26500** — the product's 25500 block plus a thousand. It was the product's block
until 2026-08-10, which meant a developer running the companion app (it binds 25610) could not run a
single live scenario: the preflight refused every one of them, so the more complete somebody's
install the less of the suite they could execute. `chosenBase()` probes the block at startup, steps
200 up if anything is listening in it, and prints the block it settled on as the run's first line;
`NODERA_E2E_PORT_BASE` pins it. `PortHolder` turns a genuinely held port into a diagnosis — which
pid, which binary, and whether it is an installed Nodera to quit or a leftover from this checkout to
kill. `HarnessPortPlanTest` reads the product's own constants, so the two tables cannot converge
again. See [`docs/testing/Task.0.md`](../../../docs/testing/Task.0.md) §3.1 for the offset table.

**A scenario that starts its own service must ask for `Topology.scenarioPort(i)`**, not a literal.

## Rules

- Nothing here may be referenced from production code.
- No Minecraft or NeoForge types.
- A helper that would let a test pass without exercising the real seam does not belong here.

## Tests

57 tests covering the helpers and the harness itself — a broken test library produces false
confidence everywhere else.

```bash
./gradlew :testing:test
```
