//! `nodera-rendezvous` — placeholder binary.
//!
//! Task 27 scaffolds the crate so the workspace, CI job, and release-artifact job exist before the
//! service does. Task 29 fills it in (registration, discovery, relay reservations, circuit
//! bridging, hole-punch coordination). Until then the binary starts, reports its version, and
//! exits non-zero when asked to serve — never silently pretending to be a relay.

fn main() -> std::process::ExitCode {
    let arg = std::env::args().nth(1).unwrap_or_default();
    if arg == "--version" {
        println!("nodera-rendezvous {}", env!("CARGO_PKG_VERSION"));
        return std::process::ExitCode::SUCCESS;
    }
    eprintln!(
        "nodera-rendezvous {} is a Task 27 placeholder: the service is implemented by Task 29",
        env!("CARGO_PKG_VERSION")
    );
    std::process::ExitCode::FAILURE
}
