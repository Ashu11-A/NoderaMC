# The telemetry ingest service, built from the repository root.
#
#   docker compose build ingest     (context is the repo root: the build needs /VERSION)
#
# Two stages: the version file is copied in deliberately, because build.rs stamps the product
# version from it and a build without it silently falls back to the Cargo metadata.
#
# The builder tracks rust/rust-toolchain.toml, NOT the workspace's `rust-version` floor. The floor
# says what this crate's own source needs (1.82); the toolchain pin is what the dependency graph
# actually resolves against, and a transitive dependency requiring edition 2024 broke the image
# while every other build was green — the workspace builds with the pinned 1.93.1 everywhere else.
# Bump this with rust-toolchain.toml, in the same commit.
FROM rust:1.93.1-alpine AS build
RUN apk add --no-cache musl-dev
WORKDIR /src
COPY VERSION ./VERSION
COPY rust/Cargo.toml rust/Cargo.lock ./rust/
COPY rust/nodera-codec ./rust/nodera-codec
COPY rust/nodera-tracker ./rust/nodera-tracker
COPY rust/nodera-rendezvous ./rust/nodera-rendezvous
COPY rust/nodera-telemetry ./rust/nodera-telemetry
WORKDIR /src/rust
RUN cargo build --release -p nodera-telemetry

FROM alpine:3.20
RUN adduser -D -H -u 10001 nodera
COPY --from=build /src/rust/target/release/nodera-telemetry /usr/local/bin/nodera-telemetry
RUN mkdir -p /var/lib/nodera/spool && chown -R nodera /var/lib/nodera
USER nodera
EXPOSE 25620
ENTRYPOINT ["nodera-telemetry", "--config", "/etc/nodera/nodera-telemetry.toml"]
