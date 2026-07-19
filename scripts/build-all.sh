#!/usr/bin/env bash
# Build + test both halves of the monorepo (Task 27 / MONOREPO.md).
#
# One gate, two toolchains: the Java modules under java/ and the Rust service crates under rust/.
# CI runs the same two commands as separate jobs; this script is the local equivalent, so
# "green locally" means the same thing as "green in CI".
#
# Usage: scripts/build-all.sh [--fast]
#   --fast  skip the Rust release build (debug test binaries only)
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

fast=0
[[ "${1:-}" == "--fast" ]] && fast=1

echo "==> Java: ./gradlew check"
./gradlew check

echo "==> Rust: cargo fmt --check"
(cd rust && cargo fmt --check)

echo "==> Rust: cargo clippy --all-targets -D warnings"
(cd rust && cargo clippy --all-targets -- -D warnings)

echo "==> Rust: cargo test (includes cross-language fixture conformance)"
(cd rust && cargo test)

if [[ "$fast" -eq 0 ]]; then
  echo "==> Rust: cargo build --release (service binaries)"
  (cd rust && cargo build --release --bin nodera-tracker --bin nodera-rendezvous)
  echo "    binaries: rust/target/release/{nodera-tracker,nodera-rendezvous}"
fi

echo "==> all green"
