# Rendezvous — Retired Limitations

<!-- AI-AGENT-INSTRUCTION: Rows arrive here from LIMITATIONS.md when their EXIT TEST is green, WITH
     the evidence that retired them. Never delete a row or soften an evidence cell. If a retired
     capability regresses, open a bug issue and add a NEW row to LIMITATIONS.md. -->

**Category:** rendezvous · **Last audit:** 2026-07-25 · Retired rows: **2**

| ID | Limitation | Retirement evidence | Owner | Retired |
|---|---|---|---|---|
| L-23 | Cross-NAT direct P2P was unproven. The original plan was a jvm-libp2p transport that was **never built**; it was superseded by the standalone Rust rendezvous relay | The service speaks the frozen rendezvous/relay family: signed-record registration (Ed25519, TTL, trust-on-first-use identity binding, per-IP quota), paged discovery, HMAC relay reservations, and a tokio circuit bridge that meters bytes, duration, and idle time and tears down with a reason code. `RendezvousRelayIT` spawns the **real binary** and drives two relay-only Java peers through it: they register, discover each other, and a `PeerJoin` plus `SessionKeepAlive` cross the end-to-end-encrypted circuit byte-exact; exhausting the reservation's byte ceiling tears the circuit down; and the selector reports the direct path when one is available | [1](Task.1.md) | 2026-07-19 |
| L-27 | The direct socket transport needed reachable listen endpoints (LAN, port forwarding, or a VPN); there was no hole punching and no relay fallback | `RendezvousPeerTransport` composes direct-first and relay-fallback behind the **same** `PeerTransport` seam, with `SocketPeerTransport` remaining the LAN path. An X25519-ECDH + Ed25519-authenticated + AES-GCM `EndToEndCipher` means the relay forwards opaque bytes it can neither read nor forge, and a `TransportSelector` prefers direct over punched over relayed per (peer, message class), keeping bulk stream chunks off relays. A later audit found and fixed the structural half of this claim: the transport had advertised **only** a RELAY candidate, so direct-first was unreachable and every byte crossed the relay — it now publishes a HOST candidate from the direct transport's listen route and renews registration at half the TTL | [2](Task.2.md) | 2026-07-19 |
