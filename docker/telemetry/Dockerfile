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
# <!-- AI-AGENT-INSTRUCTION: The three service Dockerfiles under docker/ are deliberately near
#      identical. Change one and change the other two in the same commit. `COPY rust ./rust` copies
#      the WHOLE workspace on purpose — this file used to list members one COPY at a time, and
#      adding `nodera-service` to the workspace silently broke the image while every other build was
#      green. Do not turn it back into a member list. -->
FROM rust:1.93.1-alpine AS build
# build-base, not just musl-dev: the shared service crate's TLS provider (rustls/ring) compiles C.
RUN apk add --no-cache build-base
WORKDIR /src
COPY VERSION ./VERSION
COPY rust ./rust
WORKDIR /src/rust
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,target=/src/rust/target,sharing=locked \
    cargo build --release -p nodera-telemetry \
    && install -m 0755 target/release/nodera-telemetry /usr/local/bin/nodera-telemetry

FROM alpine:3.20
RUN apk add --no-cache ca-certificates \
    && adduser -D -H -u 10001 nodera \
    && mkdir -p /var/lib/nodera/spool \
    && chown -R nodera /var/lib/nodera
COPY --from=build /usr/local/bin/nodera-telemetry /usr/local/bin/nodera-telemetry

# Every setting is an environment variable — `NODERA_TELEMETRY_` plus the TOML key, uppercased —
# so the config file below is optional. The pseudonymisation key is not among them: it is minted
# in memory from the OS CSPRNG each rotation period and never read from config or disk.
ENV NODERA_TELEMETRY_BIND_ADDR=0.0.0.0:25620 \
    NODERA_TELEMETRY_SPOOL_DIR=/var/lib/nodera/spool

# NOT a `VOLUME` declaration. Declaring one forces an anonymous volume onto every `docker run`,
# which is a surprise (they accumulate, they are never reclaimed, and on some storage drivers the
# copy-up fails outright), and it does not make the data any more durable than the compose file
# below already does. Mount something at /var/lib/nodera — docker/compose.yml does.
USER nodera
WORKDIR /var/lib/nodera
EXPOSE 25620

# The config path is still passed because the telemetry deployment mounts a reviewable file for the
# policy settings. Both layers work; the file wins over the defaults and the environment wins over
# the file. No pseudonymisation secret is supplied at either layer — it is minted in memory.
ENTRYPOINT ["nodera-telemetry", "--config", "/etc/nodera/nodera-telemetry.toml"]
