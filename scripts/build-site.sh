#!/usr/bin/env bash
# Build the public website into web/dist/.
#
#   scripts/build-site.sh [--no-install] [--skip-tests]
#
# The deployment lane is promised exactly one thing by this file, and two other files encode that
# promise literally:
#
#   * `docker/web/Dockerfile` runs this one command and then copies `<dir.web>/dist` into the
#     published image. Nothing else in the image knows how the site is produced.
#   * `.github/workflows/containers.yml` lists this file in both `paths:` filters, so an edit here
#     rebuilds the image. Move or rename it and the image silently stops tracking the site.
#
# The contract: one command, run from anywhere, writes a FINISHED static site to `<dir.web>/dist` —
# `index.html`, hashed `assets/*`, and `add-store.html`. It is repeatable, and it fails loudly rather
# than leaving a half-written dist behind.
#
# Everything else here — where the site's source is, where the shared kit is — resolves through
# `layout.properties`. `web/dist` is COMPOSED rather than looked up, because that manifest holds
# directories and says in its own header that a build output is a property of a build system.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
source "$(dirname "${BASH_SOURCE[0]}")/lib/bun.sh"
layout_export

SITE="$NODERA_WEB_DIR"
DIST="$SITE/dist"

INSTALL=1
TESTS=1
for arg in "$@"; do
	case "$arg" in
		--no-install) INSTALL=0 ;;
		--skip-tests) TESTS=0 ;;
		-h|--help) sed -n '2,20p' "$0"; exit 0 ;;
		*) echo "build-site: unknown flag $arg" >&2; exit 2 ;;
	esac
done

cd "$NODERA_ROOT"

if [[ $INSTALL -eq 1 ]]; then
	nodera_bun_install "$SITE"
fi

# Read the repository: the release manifest, the published service list, the roadmap and the
# limitation registers, the launcher's palette, and the documents this site mirrors. Every one of
# these fails closed — a missing release asset or an unresolvable link is a non-zero exit naming it,
# not a page with a hole in it.
#
# The three steps are named `generate` / `build` / `sitemap` rather than `prebuild` / `build` /
# `postbuild` because bun runs the `pre` and `post` forms by ITSELF around `build` — so those names
# plus these calls generated the whole site twice, at two GitHub API round trips per build.
bun run --cwd "$SITE" generate

# Cleared rather than merged. A stale asset left behind by a previous build is the failure mode that
# produces a site which works on the machine that built it and 404s everywhere else.
rm -rf "$DIST"

bun run --cwd "$SITE" build
bun run --cwd "$SITE" sitemap

# The deep-link page ships as the bytes that were reviewed, never as a re-render.
#
# It is the target of the root README's badge and the only genuinely functional page this project
# has today, and its three rules — nothing fires on load, the URL is shown exactly as received, and
# not-installed is the common case — are the kind a rewrite loses quietly. `web/tests/add-store.test.mjs`
# holds the copy here to all seventeen of them, and a React port may replace these bytes only by
# passing that suite unmodified.
install -m 644 "$SITE/add-store.html" "$DIST/add-store.html"

# …and nothing may take that URL away from it. A route called `/add-store` in the site generator
# would emit `add-store/index.html`, which most web servers prefer, and the reviewed page would sit
# beside it being served to nobody.
if [[ -e "$DIST/add-store/index.html" ]]; then
	echo "build-site: the site generator emitted /add-store; the hand-written page owns that URL" >&2
	exit 1
fi

# Asserted, not assumed. The Dockerfile copies this directory blind, and an empty dist would publish
# an image that starts, answers, and serves nothing.
[[ -f "$DIST/index.html" ]] || { echo "build-site: nothing was written to $DIST" >&2; exit 1; }

if [[ $TESTS -eq 1 ]]; then
	bun run --cwd "$SITE" test
fi

echo "build-site: $(find "$DIST" -type f | wc -l | tr -d ' ') file(s) in $DIST"
