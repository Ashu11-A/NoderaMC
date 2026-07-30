# The rendezvous/relay service, built from the repository root.
#
#   docker build -f docker/rendezvous/Dockerfile -t nodera-rendezvous .
#
# <!-- AI-AGENT-INSTRUCTION: The three service Dockerfiles under docker/ are deliberately near
#      identical. Change one and change the other two in the same commit. `COPY rust ./rust` copies
#      the WHOLE workspace on purpose — an earlier version listed members one COPY at a time and
#      adding a crate silently broke the image. Do not turn it back into a member list. -->
#
# The context is the repository root because `build.rs` stamps the product version from `/VERSION`.
# The builder tracks rust-toolchain.toml, not the workspace `rust-version` floor.
FROM rust:1.93.1-alpine AS build
# build-base, not just musl-dev: the update lane's TLS provider (rustls/ring) compiles C.
RUN apk add --no-cache build-base
WORKDIR /src
# The cargo workspace manifest lives at the repository root, so the build context is rooted
# there too. Only the Rust tree is copied — the Java modules are not inputs to this image,
# and copying them would bust the dependency layer on every unrelated edit.
COPY VERSION Cargo.toml Cargo.lock rust-toolchain.toml ./
# Every cargo workspace MEMBER, because cargo refuses to parse a manifest whose members are absent.
# Enumerated rather than `COPY . .`: the Java tree is not an input to this image, and copying it
# would bust the dependency layer on every unrelated edit. `scripts/check-layout-drift.sh` fails the
# build if one of these stops resolving — the previous version of this file said `COPY rust ./rust`
# and silently broke the image once already.
COPY library/rust ./library/rust
COPY tracker ./tracker
COPY rendezvous ./rendezvous
COPY telemetry ./telemetry
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,target=/src/target,sharing=locked \
    cargo build --release -p nodera-rendezvous \
    && install -m 0755 target/release/nodera-rendezvous /usr/local/bin/nodera-rendezvous

FROM alpine:3.20
RUN apk add --no-cache ca-certificates \
    && adduser -D -H -u 10001 nodera \
    && mkdir -p /var/lib/nodera \
    && chown -R nodera /var/lib/nodera
COPY --from=build /usr/local/bin/nodera-rendezvous /usr/local/bin/nodera-rendezvous

# Every setting is an environment variable — `NODERA_RENDEZVOUS_` plus the TOML key, uppercased.
# The full list is in docs/rendezvous/SELF-HOSTING.md; an unrecognised variable in this prefix
# refuses the start rather than being ignored.
#
# 7500 is this image's convention, not the binary's default (which stays 25601).
#
# NODERA_RENDEZVOUS_TRACKER_ENDPOINTS is unset on purpose and is the one variable an operator must
# supply: a relay that announces to no tracker is reachable only by peers already holding its
# address, which is the whole discovery lane not working.
ENV NODERA_RENDEZVOUS_BIND_ADDR=0.0.0.0:7500 \
    NODERA_RENDEZVOUS_IDENTITY_FILE=/var/lib/nodera/rendezvous-identity.bin

# The identity must outlive the container: a regenerated one presents this relay to every peer as a
# brand-new service with no measured availability.
# NOT a `VOLUME` declaration. Declaring one forces an anonymous volume onto every `docker run`,
# which is a surprise (they accumulate, they are never reclaimed, and on some storage drivers the
# copy-up fails outright), and it does not make the data any more durable than the compose file
# below already does. Mount something at /var/lib/nodera — docker/compose.yml does.
USER nodera
WORKDIR /var/lib/nodera

EXPOSE 7500/tcp

# `--healthcheck` speaks the real protocol, so this fails when the service is listening but not
# answering — the outage a TCP connect test reports as healthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["nodera-rendezvous", "--healthcheck", "127.0.0.1:7500"]

# Exec form, so the binary is PID 1 and receives SIGTERM directly. That starts a drain, and a drain
# here is not cosmetic: peers holding reservations are told to move to another relay before this one
# stops. `docker stop` allows 10 seconds by default against a 30-second drain grace, so set
# `stop_grace_period` (compose) or `--time` (CLI) or the drain is cut short every deploy.
ENTRYPOINT ["nodera-rendezvous"]
