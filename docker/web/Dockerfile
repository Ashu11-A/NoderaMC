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
#
# python3 is not incidental either: `mirror-docs.mjs` shells out to `scripts/lib/docs_links.py`
# because that file is the ONE definition of what a relative link in `docs/` means, and a JavaScript
# reimplementation would be a second one whose disagreements are silent — a link that renders as
# text, or points at a file that moved, looks exactly like a link that works.
#
# git, because /src is a repository and not merely a directory of files. The ninth mirrored document
# lives on the orphan `services` branch and has no working-tree path; `mirror-docs.mjs` reads it with
# `git show` and fails closed when the ref is missing. The same history dates every mirrored page.
#
# nodejs-current, because `bun` is the package manager here and not the runtime, and because the
# version matters. Two separate traps, both of which end in a test step that runs NOTHING:
#
#   * This image ships a `node` SHIM at /usr/local/bun-node-fallback-bin/node that forwards to bun,
#     and bun does not implement `node --test`'s file globbing. `node --test "tests/*.test.mjs"`
#     exits "Module not found" having executed no test. A real node in /usr/bin precedes the shim
#     on PATH, which is what fixes that.
#   * Alpine 3.20's `nodejs` is 20.15, which predates glob support in the test runner and answers
#     "Could not find 'tests/*.test.mjs'" — the same nothing, one version short. `nodejs-current`
#     is 21.7, which has it.
#
# Both failures are loud here only because `build-site.sh` runs the suite as its last step and
# propagates the exit code. Pin-watch this if the base image's Alpine moves.
RUN apk add --no-cache bash python3 git nodejs-current
WORKDIR /src
#
# WHY THIS COPIES THE REPOSITORY AND NOT A LIST OF FILES
# -----------------------------------------------------
# It used to be `web/` and three files, which was correct against a placeholder `build-site.sh` with
# no inputs. The real one READS THE REPOSITORY, because that is what the site is: the roadmap table,
# the ten limitation registers, the launcher's palette and nine mirrored documents are generated
# from their sources rather than retyped, so a published page cannot disagree with the repository it
# describes.
#
# The obvious repair — enumerate what the generators open — was tried and does not converge. The
# generators need `docs/`, `COMPATIBILITY.md`, two sibling workspace packages, four `scripts/lib`
# helpers and `scripts/services.py`; the mirror then resolves every relative link inside those nine
# documents and fails closed on one whose target is not in the context; and the site's own suite,
# which `build-site.sh` runs as its last step, reads `README.md` and `.github/workflows/build.yml`
# on top of that. The real input set is "whatever a site generator or a site test opens", which is
# not a set a COPY list can track — and every miss is a build that fails deep inside a generator
# with a message about a document rather than about this file.
#
# So the builder takes the working tree, `.dockerignore` says what never needs to reach the daemon,
# and there is one statement of that instead of two that drift. This costs build cache — any source
# change invalidates this layer — which is honest, because almost any source change really can
# change the site. The final stage below copies only `web/dist`, so none of it reaches the image.
#
# /src is a REAL repository root, not just a directory with the site in it: VERSION and
# settings.gradle.kts are the two markers every layout-aware consumer walks up to find, and
# layout.properties is the table they then read. It must also be a real *git* checkout carrying the
# orphan `services` ref — see `.dockerignore`, and `containers.yml`'s fetch step.
COPY . .
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
