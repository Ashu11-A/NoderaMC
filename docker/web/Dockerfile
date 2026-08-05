# The public website (noderamc.org), built from the repository root.
#
#   docker build -f docker/web/Dockerfile -t nodera-web .
#   docker run --rm -p 127.0.0.1:8080:8080 nodera-web
#
# <!-- AI-AGENT-INSTRUCTION: This is the fourth image .github/workflows/containers.yml publishes and
#      it must stay in BOTH of that file's matrices, exactly like the three services. It is
#      deliberately shaped like the Dockerfiles beside it — repository root as the build context,
#      the same non-root uid 10001, an exec-form ENTRYPOINT, a HEALTHCHECK that performs a real
#      request, no VOLUME, no LABEL — but it is NOT a Rust build. Do not "align" it by copying the
#      cargo stanzas in, and do not give it a `USER root` to make a permission problem go away.
#
#      The one contract with the website lane is `scripts/build-site.sh` writing a finished static
#      site to `<dir.web>/dist`. This file runs that command and copies that directory; it knows
#      nothing else about how the site is made, and it must stay that way. -->
#
# WHY CADDY IS THE RUNTIME, and not `alpine:3.20` plus a small static server like the three
# services use:
#
#   * Caddy is already this project's HTTP vocabulary. The site block on the VPS is Caddy, so the
#     SPA fallback and the /add-store pin are written once in a syntax the operator debugging this
#     box already reads, instead of in a second server's config language nobody here maintains.
#   * `try_files` is a first-class directive. On Alpine the alternative is busybox httpd, which
#     cannot express an SPA fallback at all, or shipping another binary this repository would then
#     own the version of.
#   * The image stays Alpine-based (caddy:*-alpine), so the runtime is the same distribution, the
#     same package manager and the same `adduser` invocation as its three siblings.
#
# Base tags are pinned to a patch version for the same reason the builders track
# rust-toolchain.toml: a floating `caddy:2-alpine` means the image an operator pulls is not the
# image CI tested, and the difference only ever shows up in production.

FROM oven/bun:1.2.23-alpine AS build
# bash, because every script in this repository is `#!/usr/bin/env bash` and uses arrays and
# BASH_SOURCE; Alpine's default shell is busybox ash and would fail on the first of those. This is
# the web builder's counterpart to the services' `apk add build-base`.
RUN apk add --no-cache bash
WORKDIR /src
# /src is made a REAL repository root, not just a directory with the site in it: VERSION and
# settings.gradle.kts are the two markers every layout-aware consumer in this repository walks up to
# find, and layout.properties is the table they then read. With all three present,
# scripts/build-site.sh resolves web/ from the manifest in here exactly as it does on a laptop —
# no NODERA_* override to keep in sync, and no second definition of where the site lives.
COPY VERSION settings.gradle.kts layout.properties ./
COPY scripts/lib/layout.sh ./scripts/lib/layout.sh
COPY scripts/build-site.sh ./scripts/build-site.sh
COPY web ./web
# The cache mount makes a dependency install cheap across rebuilds, and — like the cargo mounts in
# the service builders — it is not part of the layer, so everything the next stage needs has to be
# on disk under /src when this RUN ends. `build-site.sh` writes web/dist, which is.
RUN --mount=type=cache,target=/root/.bun/install/cache,sharing=locked \
    bash scripts/build-site.sh

FROM caddy:2.10.2-alpine
# ca-certificates is not for the site itself — nothing here makes an outbound request — but for the
# same reason the services carry it: a container that cannot verify a certificate at all fails in a
# far more confusing way than one that declines to make the call.
RUN apk add --no-cache ca-certificates \
    && adduser -D -H -u 10001 nodera \
    && mkdir -p /srv/noderamc \
    && chown -R nodera /srv/noderamc /config /data
COPY docker/web/Caddyfile /etc/caddy/Caddyfile
COPY --from=build --chown=nodera /src/web/dist /srv/noderamc

# NOT a `VOLUME` declaration — the same ban the three service Dockerfiles carry, and here it is not
# even a close call: this image holds no state at all. The site is baked in, a new site is a new
# image, and an anonymous volume over /srv/noderamc would pin the FIRST build's files forever while
# every later pull appeared to do nothing.
USER nodera
WORKDIR /srv/noderamc

# 8080, not 80, because the process is not root and cannot bind a privileged port. docker/compose.yml
# publishes this on 127.0.0.1 only: the VPS Caddy is the sole path to it from outside the host.
EXPOSE 8080/tcp

# A real GET of the real document root. A TCP-connect check reports "healthy" for a Caddy that came
# up with an empty /srv/noderamc and answers 404 to everything, which is precisely the outage this
# image can have — the site is copied in at build time, so a bad build is a running server with
# nothing behind it. busybox wget exits non-zero on any non-2xx, so this fails on that.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/"]

# Exec form, so caddy is PID 1 and receives SIGTERM directly. There is no drain to protect here —
# the responses are static files — but a server that has to be killed still costs every in-flight
# request its response, and Docker's default 10-second stop timeout is ample for this one.
ENTRYPOINT ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
