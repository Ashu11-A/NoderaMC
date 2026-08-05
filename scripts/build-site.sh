#!/usr/bin/env bash
# Build the public website into web/dist/.
#
# <!-- AI-AGENT-INSTRUCTION: THIS BODY IS A PLACEHOLDER. The website lane owns this script and is
#      expected to replace everything below the argument handling with the real (Vite/React) build.
#      What must NOT change is the contract the deployment lane is built on, because two other files
#      encode it literally:
#
#        * `docker/web/Dockerfile` runs this one command and then copies `<dir.web>/dist` into the
#          published image. Nothing else in the image knows how the site is produced.
#        * `.github/workflows/containers.yml` lists this file in both `paths:` filters, so an edit
#          here rebuilds the image. Move or rename it and the image silently stops tracking the site.
#
#      The contract: one command, run from anywhere, writes a FINISHED static site to
#      `<dir.web>/dist` — `index.html`, hashed `assets/*`, and `add-store.html`. It must be
#      repeatable (a second run over a warm tree produces the same tree) and it must fail loudly
#      rather than leave a half-written dist behind. -->
#
#   scripts/build-site.sh
#
# Today web/ holds two hand-written pages and no build system, so the "build" is a copy. That is
# deliberately still routed through this script rather than through a `cp` in the Dockerfile: the
# image must depend on the contract, not on what the site happens to be made of this week.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export

DIST="$NODERA_WEB_DIR/dist"

# Cleared rather than merged. A stale asset left behind by a previous build is the failure mode that
# produces a site which works on the machine that built it and 404s everywhere else.
rm -rf "$DIST"
mkdir -p "$DIST"

for page in index.html add-store.html; do
	[[ -f "$NODERA_WEB_DIR/$page" ]] || { echo "build-site: web/$page is missing" >&2; exit 1; }
	cp "$NODERA_WEB_DIR/$page" "$DIST/$page"
done

# Asserted, not assumed. The Dockerfile copies this directory blind, and an empty dist would publish
# an image that starts, answers, and serves nothing.
[[ -f "$DIST/index.html" ]] || { echo "build-site: nothing was written to $DIST" >&2; exit 1; }

echo "build-site: $(find "$DIST" -type f | wc -l | tr -d ' ') file(s) in $DIST (placeholder build)"
