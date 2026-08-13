#!/usr/bin/env bash
#
# Stage the Paper and Folia server jars the `server` category's live scenarios run against.
#
# WHY THIS EXISTS
# ---------------
# `ServerEndpointSupport` resolves a platform jar from `NODERA_PAPER_JAR` / `NODERA_FOLIA_JAR` or
# from `run/servers/`, and reports SKIPPED when neither is there. That is the right behaviour for a
# developer's laptop and the wrong one for CI: nothing in `.github/` ever staged a jar, so the
# `endpoint`, `folia` and `plugins` scenarios reported SKIP on every machine that has ever run
# them — a STRUCTURAL skip, which `docs/testing/Task.0.md` bans outright. A suite that never runs
# is not evidence of anything.
#
# The suites still download nothing themselves: a suite that fetched its own jar would make a local
# run and a CI run different code paths. This script is the one place the fetch happens, and CI
# calls exactly it.
#
# RESOLUTION
# ----------
# PaperMC's v2 API is sunset (HTTP 410). Everything here goes through **v3 (fill)**:
#
#   GET /v3/projects/<project>/versions/<version>/builds
#     -> newest build first; downloads["server:default"] carries {name, checksums.sha256, url}
#
# Paper is resolved at the Minecraft version the mod pins. Folia is resolved at the newest version
# Folia actually publishes below 1.21.9, because Folia has NO 1.21.1 build — that gap is L-66, and
# this script cannot close it, only make the part that IS testable (plugin enable, ALIGN-1, the
# cross-region refusal — all platform properties) actually run.
#
# Folia's builds are published on the ALPHA channel, so a STABLE-only filter would resolve nothing.
#
# Usage:
#   scripts/stage-server-jars.sh                 # both, into run/servers/
#   scripts/stage-server-jars.sh paper           # one
#   scripts/stage-server-jars.sh --force folia   # re-download even if present
set -euo pipefail

# shellcheck source=scripts/lib/layout.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/layout.sh"

ROOT="$(layout_root "$(dirname "${BASH_SOURCE[0]}")")"
SERVERS="$ROOT/$(layout_get dir.run)/servers"

# The Minecraft version the mod pins. Kept in step with ServerEndpointSupport.MINECRAFT_VERSION and
# docs/README.md §4.6 — a mismatch means a client cannot join what the suite started.
PAPER_MC_VERSION="${NODERA_PAPER_MC_VERSION:-1.21.1}"
# The newest Minecraft version Folia publishes that the endpoint suites have been run against.
# Raising it is a deliberate act: read L-66 first.
FOLIA_MC_VERSION="${NODERA_FOLIA_MC_VERSION:-1.21.4}"

API="https://fill.papermc.io/v3"
UA="nodera-endpoint-suites (+https://noderamc.org)"

FORCE=0
WANTED=()
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        paper|folia) WANTED+=("$arg") ;;
        -h|--help) sed -n '2,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "stage-server-jars.sh: unknown argument '$arg' (paper|folia|--force)" >&2; exit 2 ;;
    esac
done
[ ${#WANTED[@]} -gt 0 ] || WANTED=(paper folia)

stage() {
    local project="$1" version="$2" target="$SERVERS/$1.jar"

    if [ "$FORCE" -eq 0 ] && [ -s "$target" ]; then
        echo "stage-server-jars: $project already staged at $target"
        return 0
    fi

    local builds
    if ! builds="$(curl -fsSL -m 60 -A "$UA" "$API/projects/$project/versions/$version/builds")"; then
        echo "stage-server-jars: $API/projects/$project/versions/$version/builds is unreachable" >&2
        return 1
    fi

    # The newest build, preferring a STABLE channel when the project publishes one. Folia does not,
    # so an unconditional STABLE filter would resolve nothing at all — which would present as "no
    # Folia exists" rather than as "this project ships alpha builds".
    local picked
    picked="$(printf '%s' "$builds" | python3 -c '
import json, sys
builds = json.load(sys.stdin)
if not builds:
    sys.exit("no builds published")
stable = [b for b in builds if b.get("channel") == "STABLE"]
build = (stable or builds)[0]
download = build.get("downloads", {}).get("server:default")
if not download:
    sys.exit("build %s carries no server:default download" % build.get("id"))
print(build.get("id"), build.get("channel"), download["checksums"]["sha256"], download["url"])
')" || { echo "stage-server-jars: cannot resolve a $project build: $picked" >&2; return 1; }

    read -r build_id channel sha url <<<"$picked"
    mkdir -p "$SERVERS"
    echo "stage-server-jars: $project $version build $build_id ($channel) -> $target"
    curl -fsSL -m 900 -A "$UA" -o "$target.part" "$url"

    local actual
    actual="$(sha256sum "$target.part" | cut -d' ' -f1)"
    if [ "$actual" != "$sha" ]; then
        rm -f "$target.part"
        echo "stage-server-jars: $project checksum mismatch (want $sha, got $actual)" >&2
        return 1
    fi
    mv "$target.part" "$target"
}

for project in "${WANTED[@]}"; do
    case "$project" in
        paper) stage paper "$PAPER_MC_VERSION" ;;
        folia) stage folia "$FOLIA_MC_VERSION" ;;
    esac
done

ls -l "$SERVERS"
