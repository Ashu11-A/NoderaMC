plugins {
    id("nodera.java-library")
}

// transport-socket: a real TCP PeerTransport (Minecraft-free). Direct P2P data plane behind the
// transport-api seam — the same seam a future transport-libp2p will implement (Plan §3.10 / Phase
// 6). Depends on protocol only for ChunkedStreams / StreamChunk in sendStream (identical chunking
// semantics to testkit's LoopbackTransport), so a caller can swap transports transparently.
dependencies {
    api(project(":core"))
    api(project(":transport-api"))
    implementation(project(":protocol"))
}
