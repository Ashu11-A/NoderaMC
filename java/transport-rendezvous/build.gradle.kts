plugins {
    id("nodera.java-library")
}

// transport-rendezvous: the third PeerTransport (Task 29), composing direct-first, punch-upgrade,
// and end-to-end-encrypted relay fallback over the standalone Rust `nodera-rendezvous` service. It
// replaces the never-built `transport-libp2p` (LEGACY.md). Behind the transport-api seam — the same
// seam SocketPeerTransport and the NeoForge relay implement — so peer-runtime / committee /
// distribution call sites cannot tell which transport carried a message.
dependencies {
    api(project(":core"))
    api(project(":transport-api"))
    implementation(project(":protocol"))
    implementation(project(":transport-socket"))
}
