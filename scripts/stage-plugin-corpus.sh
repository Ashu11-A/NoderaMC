#!/usr/bin/env bash
#
# Stage the pinned plugin corpus the `plugins` live scenario runs against.
#
# WHY THIS EXISTS
# ---------------
# `PluginsScenario` stages whatever jars it finds in `run/plugins` (or `$NODERA_PLUGIN_CORPUS_DIR`)
# and, until 2026-08-10, *noted* an empty corpus and carried on — so the compatibility scenario
# passed on a server with no plugins on it. A compatibility test with an empty corpus proves
# nothing, and L-65's exit clause names two plugins by name: a WorldEdit `//set` must be certified
# rather than suppressed, and CoreProtect must still log every change.
#
# So the corpus is staged the same way the platform jars are (`scripts/stage-server-jars.sh`): one
# script, called by CI and by a developer, downloading nothing from inside the suite itself. A suite
# that fetched its own subjects would test whatever the internet happened to serve that morning.
#
# PINNING
# -------
# Every member is pinned by VERSION, not by "latest". `docs/server/TESTING.md` §1.5 is the table
# this implements, and `gradle/libs.versions.toml` pins the SAME WorldEdit the plugin compiles
# against — one number, so the compile surface and the tested runtime cannot drift apart.
#
# Resolution is Modrinth's v2 API, which is the only registry that serves all of these without a
# login wall. Each file is verified against the sha512 the API publishes for it.
#
#   CoreProtect: the upstream build is behind SpigotMC's login wall and cannot be fetched by any
#   script. `CoreProtect-CE` is the community continuation, is a drop-in for the `/co` command
#   surface the exit clause names, and is what this stages. Stated here because a corpus that
#   silently tests a fork of the plugin it claims to test is exactly the shape of a green test that
#   asserts nothing.
#
# NOT STAGED, and why: EssentialsX, LuckPerms, Vault, PlaceholderAPI and ViaVersion are in the
# corpus table for co-existence coverage (C1/C2) and none of them is named by an exit clause. They
# can be dropped into `run/plugins` by hand and the scenario will pick them up and name them; they
# are not downloaded by default because five more jars per CI leg buys no assertion.
#
# Usage:
#   scripts/stage-plugin-corpus.sh                    # the whole pinned corpus
#   scripts/stage-plugin-corpus.sh worldedit          # one member
#   scripts/stage-plugin-corpus.sh --force            # re-download even if present
set -euo pipefail

# shellcheck source=scripts/lib/layout.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/layout.sh"

ROOT="$(layout_root "$(dirname "${BASH_SOURCE[0]}")")"
CORPUS="${NODERA_PLUGIN_CORPUS_DIR:-$ROOT/$(layout_get dir.run)/plugins}"

API="https://api.modrinth.com/v2"
UA="nodera-endpoint-suites (+https://noderamc.org)"

# member            modrinth-project   pinned version_number
# WorldEdit's pin is `worldedit` in gradle/libs.versions.toml. Keep them equal.
CORPUS_MEMBERS=(
    "worldedit:worldedit:7.3.9"
    "coreprotect:coreprotect:23.2"
)

FORCE=0
WANTED=()
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        -h|--help) sed -n '2,48p' "${BASH_SOURCE[0]}"; exit 0 ;;
        -*) echo "stage-plugin-corpus.sh: unknown flag '$arg'" >&2; exit 2 ;;
        *) WANTED+=("$arg") ;;
    esac
done

wanted() {
    [ ${#WANTED[@]} -eq 0 ] && return 0
    local candidate
    for candidate in "${WANTED[@]}"; do
        [ "$candidate" = "$1" ] && return 0
    done
    return 1
}

stage() {
    local member="$1" project="$2" version="$3"

    local versions
    if ! versions="$(curl -fsSL -m 60 -A "$UA" "$API/project/$project/version")"; then
        echo "stage-plugin-corpus: $API/project/$project/version is unreachable" >&2
        return 1
    fi

    local picked
    picked="$(printf '%s' "$versions" | python3 -c '
import json, sys
wanted = sys.argv[1]
versions = json.load(sys.stdin)
match = [v for v in versions if v.get("version_number") == wanted]
if not match:
    sys.exit("no version %r is published (have: %s)"
             % (wanted, ", ".join(v.get("version_number", "?") for v in versions[:8])))
files = [f for f in match[0]["files"] if f.get("primary")] or match[0]["files"]
if not files:
    sys.exit("version %r publishes no file" % wanted)
print(files[0]["filename"], files[0]["hashes"]["sha512"], files[0]["url"])
' "$version")" || {
        echo "stage-plugin-corpus: cannot resolve $member $version: $picked" >&2
        return 1
    }

    read -r filename sha url <<<"$picked"
    local target="$CORPUS/$filename"
    if [ "$FORCE" -eq 0 ] && [ -s "$target" ]; then
        echo "stage-plugin-corpus: $member already staged at $target"
        return 0
    fi

    mkdir -p "$CORPUS"
    echo "stage-plugin-corpus: $member $version -> $target"
    curl -fsSL -m 900 -A "$UA" -o "$target.part" "$url"

    local actual
    actual="$(sha512sum "$target.part" | cut -d' ' -f1)"
    if [ "$actual" != "$sha" ]; then
        rm -f "$target.part"
        echo "stage-plugin-corpus: $member checksum mismatch (want $sha, got $actual)" >&2
        return 1
    fi
    mv "$target.part" "$target"
}

for entry in "${CORPUS_MEMBERS[@]}"; do
    IFS=':' read -r member project version <<<"$entry"
    if wanted "$member"; then
        stage "$member" "$project" "$version"
    fi
done

ls -l "$CORPUS"
