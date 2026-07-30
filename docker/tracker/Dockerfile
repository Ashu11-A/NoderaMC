# The tracker service, built from the repository root.
#
#   docker build -f docker/tracker/Dockerfile -t nodera-tracker .
#
# <!-- AI-AGENT-INSTRUCTION: The three service Dockerfiles under docker/ are deliberately near
#      identical. Change one and change the other two in the same commit, or the drift shows up as a
#      single arch or a single service failing in a way nobody reproduces locally. In particular:
#      `COPY rust ./rust` copies the WHOLE workspace on purpose — an earlier version listed members
#      one COPY at a time and adding a crate silently broke the image, which is the one thing this
#      shape makes impossible. Do not "optimise" it back into a member list. -->
#
# The context is the repository root because `build.rs` stamps the product version from `/VERSION`,
# and a build without that file silently falls back to the Cargo metadata.
#
# The builder tracks rust-toolchain.toml, NOT the workspace's `rust-version` floor. The floor
# says what this crate's own source needs (1.82); the toolchain pin is what the dependency graph
# actually resolves against. Bump this with rust-toolchain.toml, in the same commit.
FROM rust:1.93.1-alpine AS build
# build-base, not just musl-dev: the update lane's TLS provider (rustls/ring) compiles C.
RUN apk add --no-cache build-base
WORKDIR /src
# The cargo workspace manifest lives at the repository root, so the build context is rooted
# there too. Only the Rust tree is copied — the Java modules are not inputs to this image,
# and copying them would bust the dependency layer on every unrelated edit.
COPY VERSION Cargo.toml Cargo.lock rust-toolchain.toml ./
COPY rust ./rust
# The cache mounts make a rebuild cheap; the binary is copied out inside the same RUN because a
# cache mount is not part of the layer and /src/target does not survive it.
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,target=/src/target,sharing=locked \
    cargo build --release -p nodera-tracker \
    && install -m 0755 target/release/nodera-tracker /usr/local/bin/nodera-tracker

FROM alpine:3.20
# ca-certificates is for the self-update lane's HTTPS fetch. It is off by default (see below), but
# an image that cannot verify a certificate at all fails in a much more confusing way than one that
# declines to update.
RUN apk add --no-cache ca-certificates \
    && adduser -D -H -u 10001 nodera \
    && mkdir -p /var/lib/nodera \
    && chown -R nodera /var/lib/nodera
COPY --from=build /usr/local/bin/nodera-tracker /usr/local/bin/nodera-tracker

# Every setting is an environment variable — `NODERA_TRACKER_` plus the TOML key, uppercased. The
# full list is in docs/tracker/SELF-HOSTING.md, and an unrecognised variable in this prefix refuses
# the start rather than being ignored.
#
# 6969 is this image's convention, not the binary's default (which stays 25600 so the development
# scripts are unaffected). It is the port the project's own deployment publishes.
ENV NODERA_TRACKER_BIND_ADDR=0.0.0.0:6969 \
    NODERA_TRACKER_IDENTITY_FILE=/var/lib/nodera/tracker-identity.bin \
    NODERA_TRACKER_PERSIST_DIR=/var/lib/nodera/state

# The identity must outlive the container. A tracker that regenerates it presents itself to the
# network as a brand-new, unmeasured service after every restart — exactly when the availability
# score it has earned matters most.
# NOT a `VOLUME` declaration. Declaring one forces an anonymous volume onto every `docker run`,
# which is a surprise (they accumulate, they are never reclaimed, and on some storage drivers the
# copy-up fails outright), and it does not make the data any more durable than the compose file
# below already does. Mount something at /var/lib/nodera — docker/compose.yml does.
USER nodera
WORKDIR /var/lib/nodera

# TCP is the complete surface; UDP is the cheap one for peers sweeping many trackers, and it binds
# the same port. Publishing only TCP works and costs a round trip per query.
EXPOSE 6969/tcp 6969/udp

# `--healthcheck` speaks the real protocol, so this fails when the service is listening but not
# answering — which is the outage a TCP connect test reports as healthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["nodera-tracker", "--healthcheck", "127.0.0.1:6969"]

# Exec form, so the binary is PID 1 and receives SIGTERM directly. That signal starts a drain:
# refuse new work, tell the peers, tell the trackers, wait for in-flight work. Give it room —
# `docker stop` allows 10 seconds by default and the drain grace is 30, so a stop without a
# `stop_grace_period` (compose) or `--time` (CLI) kills the service mid-drain.
ENTRYPOINT ["nodera-tracker"]
