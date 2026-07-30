plugins {
    id("nodera.java-library")
}

// endpoint: what a Minecraft-hosting process needs in order to BE a Nodera node, with no Minecraft
// in it.
//
// # Why this is a module and not a package in `:peer`
//
// `dev.nodera.endpoint.control.CompanionClient` is a CLIENT of `dev.nodera.peer.control.ControlServer`.
// Putting a wire's client in the same module as its server erases the only compile-time signal that
// the wire changed: today a `ControlProtocol` edit that breaks the client fails the build, because
// they are separately compiled artefacts with a declared dependency between them. Fold them together
// and that signal is gone — and this particular wire has THREE mirrors (here, `:peer`'s
// `ControlProtocol`, and `rust/nodera-app`'s `control.rs`), which is exactly the situation that needs
// a boundary rather than a convention.
//
// The second reason is `:paper-plugin`. It needs the endpoint logic and does not need the peer
// runtime on its compile path; a Paper server that hosts a world is not obliged to also be a full
// archival peer.
//
// # What is in it
//
//  - control/    the loopback companion wire: CompanionClient, the protocol constants, the gate that
//                refuses to start without a worker, and the probe that asks whether one is there.
//  - state/      parsers for what the worker reports back (NODERA-STATE, piece maps).
//  - share/      sharing a world: options, the host/join password gate, announce proofs, identities.
//  - world/      per-world durable bits a host keeps: the world store, the player→node registry,
//                the region seed spool.
//  - lane/       the Minecraft-free half of the live lanes — ownership, presence, cancel gates,
//                capture rules, operator sync. The Minecraft adapters that feed them stay in the mod.
//  - lang/       translation KEYS, so a reply is a key plus arguments and never assembled English.
//  - telemetry/  the endpoint's consent-gated event emitter.
//
// # The rule
//
// **No Minecraft or NeoForge types here, and no Paper types either.** That is the whole point: this
// module is what makes a mod or a plugin a thin shell over a tested library rather than the place
// the logic lives.
dependencies {
    api(project(":core"))
    // The endpoint speaks the peer's control wire and renders its diagnostics view models, and it
    // shares the distribution layer's password/key material with the worker on the other end.
    api(project(":peer"))

    implementation("org.slf4j:slf4j-api:2.0.9")

    testImplementation(project(":testing"))
}
