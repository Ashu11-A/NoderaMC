//! `nodera-tracker` — placeholder binary.
//!
//! Task 27 scaffolds the crate so the workspace, CI job, and release-artifact job exist before the
//! service does. Task 28 fills it in (announce lifecycle, swarm registry, query assembly, expiry,
//! quotas). Until then the binary starts, reports its version, and exits non-zero when asked to
//! serve — never silently pretending to be a tracker.

fn main() -> std::process::ExitCode {
    let arg = std::env::args().nth(1).unwrap_or_default();
    if arg == "--version" {
        println!("nodera-tracker {}", env!("CARGO_PKG_VERSION"));
        return std::process::ExitCode::SUCCESS;
    }
    eprintln!(
        "nodera-tracker {} is a Task 27 placeholder: the service is implemented by Task 28",
        env!("CARGO_PKG_VERSION")
    );
    std::process::ExitCode::FAILURE
}
