# Frontend Task 20 — Shipping it

<!-- AI-AGENT-INSTRUCTION: The rules the SSH script earned the hard way, which any replacement
     inherits whole. (1) THE HOST SERVES OTHER PEOPLE'S SITES. Whatever ships may only ever write
     /var/www/noderamc and the single file /etc/caddy/conf.d/noderamc.caddy — never another conf.d
     entry, never the parent Caddyfile. (2) `caddy validate` does NOT open log files, so a block that
     validates can still fail its reload; roll back on EITHER. (3) RELOAD, NEVER RESTART: a restart
     drops live connections for sites that are nothing to do with us. (4) The site is static and
     nothing on the network may depend on it. (5) A moving tag a deployment follows must never point
     at an unmerged branch — containers.yml already holds that line and this lane must not break it.
     Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** frontend · **Owns:** — · **Last audit:** 2026-08-05
**Depends on:** [frontend 18](Task.18.md), [frontend 19](Task.19.md)
**Consumed by:** players (it is how they reach the site and the downloads)

---

## Goal

The website is published the way the three services already are: **built and pushed as a container
image by GitHub Actions**, and **pulled by the VPS**, instead of being copied over SSH from whichever
laptop last ran a script. `scripts/deploy-site.sh` stops being the deployment and becomes, at most, a
break-glass tool.

## Status detail

**Opened 2026-08-05, and the implementation lands in this same pull request.** Nothing below is
claimed as published or measured.

How the site is deployed today, which is the thing being replaced:
[`scripts/deploy-site.sh`](../../scripts/deploy-site.sh) reads a PuTTY key from
`~/Documents/vps/ChavePrivada.ppk`, converts it in a temp file, `scp`s three files —
`index.html`, `add-store.html`, `noderamc.caddy` — to `150.230.84.206`, installs them under
`/var/www/noderamc` and `/etc/caddy/conf.d/noderamc.caddy`, validates, reloads Caddy, rolls back on
failure, and curls the two URLs. It is careful, it is idempotent, and it has three properties no
amount of care fixes:

1. **It runs from a person's machine**, with that person's key. Nothing in CI has ever published the
   site, so there is no record of what is on that host beyond whoever ran it last.
2. **It hard-codes the file list.** [Task 18](Task.18.md) replaces two hand-written pages with a
   generated directory; `for f in index.html add-store.html noderamc.caddy` stops describing the
   site the moment that lands.
3. **It pushes.** The host cannot fetch a known-good version; it receives whatever it is sent.

What already exists and is the model to follow:
[`.github/workflows/containers.yml`](../../.github/workflows/containers.yml) publishes
`tracker`, `rendezvous` and `telemetry` to `ghcr.io/ashu11-a/noderamc` for `linux/amd64` and
`linux/arm64`, building per platform by digest and merging into one manifest list. Its tag policy is
already correct for this lane and must not be reinvented: `sha-<short>` on every push,
`canary` from `main` only, `latest` + the `/VERSION` number on a `v*` tag, and **a feature branch
gets the sha tag and nothing else** — a moving tag that a deployment follows must never point at an
unmerged branch. `docker/compose.yml` is the file a self-hoster copies and pulls images with.

## Dependencies

- [Task 18](Task.18.md) — there has to be a built site before there is an image of one.
- [Task 19](Task.19.md) — the build command that produces the site's static output is what the image
  runs. If the trees unify after the image lane is written, the image is rewritten twice.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `docker/site/Dockerfile` — build the static site, serve it, nothing else in the image | 🚧 |
| 2 | The site added to **both** matrices in `containers.yml` (build-by-digest and manifest merge) | 🚧 |
| 3 | The same tag policy as the services: `sha-`, `canary`, `latest`, the `/VERSION` number | 🚧 |
| 4 | The VPS pulls rather than receives — a compose service on `150.230.84.206` behind Caddy | 🚧 |
| 5 | Caddy reverse-proxies `noderamc.org` to the container, still writing only its own `conf.d` file | 🚧 |
| 6 | `/add-store` still answers, still extensionless, with its headers intact | 🚧 |
| 7 | `scripts/deploy-site.sh` demoted: it says what now publishes the site, or it is removed | 🚧 |
| 8 | `docker/README.md` and the deployment documentation describe four images, not three | 🚧 |

## Design

**Pull, do not push.** The three services already work this way and the difference is not
convenience: a host that pulls a tagged image can be re-created from nothing, and what it is running
is a name anybody can resolve. A host that receives files over SSH is in whatever state the last
successful `scp` left it, and the only way to find out is to log in and look — which is exactly what
`--status` exists to do today.

**One image, one server, no application.** The site is static; the image builds it and serves it.
Nothing in it needs a Node runtime at request time, and putting one there would turn a signpost into
a service with a security surface. This is the same rule the site itself is held to in
[task 18](Task.18.md).

**Caddy stays in front, and stays constrained.** The host runs other people's sites out of the same
Caddy, and the existing block is careful about it: TLS comes from the global `acme_dns cloudflare`
directive in the parent Caddyfile and must not be re-declared here; `/.well-known` is deliberately
served so an Android App Links `assetlinks.json` can land without a Caddy change. Whatever replaces
`root`+`file_server` with a `reverse_proxy` must keep both of those and must keep the security
headers, which are currently `default-src 'none'` plus HSTS, `nosniff`, `no-referrer` and
`DENY`.

**The rollback rule survives the transport change.** The one hard-won lesson in the current script is
that `caddy validate` parses a configuration without opening its log files, so a block that validates
can still fail its reload — and a broken block takes every other site on the box with it. Any lane
that installs a Caddy file must still stage, validate, reload, and roll back on **either** failure.

**`sha-` tags are what a deployment pins.** The service lane already publishes them, and the reason is
written into `containers.yml`: it lets a deployment be tested against a real, CI-built image before
the branch merges, while no tag anybody else follows moves. The site should be deployable the same
way, which also means the VPS's compose file must be able to name an exact tag rather than always
tracking `canary`.

**Two architectures, natively.** `containers.yml` builds each platform on its own runner and merges
digests, because a Rust release build under QEMU takes the better part of an hour. A static site does
not have that problem, but it must not be the reason the workflow's shape is broken: if the site is
added to the matrices, it is added to **both** of them, which is the instruction already at the top
of that file.

## Files

| Path | Role |
|---|---|
| `docker/site/Dockerfile` | build the site, serve the output |
| `docker/compose.yml` | how a host runs it — including this project's own |
| `.github/workflows/containers.yml` | both matrices, and the tag policy |
| `web/noderamc.caddy` | the site block, becoming a reverse proxy |
| `scripts/deploy-site.sh` | demoted or removed |
| `docker/README.md` | the published-image table |

## Testing

The tests this task will be **closed by**:

- **The image builds in CI on a pull request**, amd64 only and pushing nothing — which is what
  `containers.yml` already does for the services, and what catches a broken Dockerfile without
  spending six image builds to prove it.
- **The image serves the site.** The container is started in the workflow and the two URLs that
  matter are requested: `/` and `/add-store?url=…`, both expected `200`, the second with its headers
  asserted rather than eyeballed.
- **The extensionless route survives.** `/add-store` resolving to `add-store.html` is a `try_files`
  rule today; whatever serves it in the image must be asserted to do the same, because the address
  people paste into a page must never grow an extension.
- **The security headers are asserted on the response**, not read from the config file.
- **A post-deploy check from outside the host**, which is what the current script's closing `curl`
  already does — kept, and run by CI rather than by a person.

The one thing no test replaces is the first live cut-over: the domain answering from the container,
with the old file tree removed, and every other site on that host still answering. That is a human
step and this file says so rather than implying a green workflow proves it.

## Acceptance criteria

1. ⬜ A push builds and publishes a site image beside the three service images, on both architectures.
2. ⬜ The VPS runs that image and is not sent files over SSH by anybody.
3. ⬜ `noderamc.org` and `www.noderamc.org` answer `200`, with the current security headers.
4. ⬜ `/add-store?url=…` behaves exactly as it does now, extensionless and click-gated.
5. ⬜ Nothing outside `/var/www/noderamc` and `/etc/caddy/conf.d/noderamc.caddy` is written on that
   host, and the other sites it serves are unaffected.
6. ⬜ `scripts/deploy-site.sh` no longer describes itself as how the site is published.
7. ⬜ `docker/README.md` lists the site image and its tags.

## Limitations

Owns none yet. The candidate is the cut-over's blast radius: this lane changes a Caddy configuration
on a host serving unrelated sites, and the existing script's rollback is the only thing standing
between a bad block and all of them going down. If the image lane cannot reproduce that rollback
exactly, it becomes a row here with "the previous configuration is restored and reloaded when either
validate or reload fails" as its exit test.
