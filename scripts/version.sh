#!/usr/bin/env bash
# scripts/version.sh — the product version, in one place.
#
# The root `VERSION` file is the single source of truth. Gradle reads it in settings.gradle.kts,
# the Rust services read it in build.rs, and `java/core` expands it into a resource that
# `NoderaConstants.PRODUCT_VERSION` loads at runtime. Four other files must *mirror* it because
# their formats cannot read a file at build time (Cargo manifests, the Tauri config, package.json).
# This script is how those mirrors get written and how CI notices when one of them drifts.
#
#   scripts/version.sh                # print the current version
#   scripts/version.sh --check        # verify every mirror agrees with VERSION (exit 1 if not)
#   scripts/version.sh --set 0.2.0    # bump VERSION and rewrite every mirror
#
# Release procedure: `--set`, run the gate (`scripts/dev.sh --test`), commit VERSION together with
# the rewritten mirrors in ONE commit, tag it. Never bump a mirror by hand — `--check` runs in the
# gate and will fail the build.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
ROOT="$NODERA_ROOT"
VERSION_FILE="$ROOT/VERSION"

read_version() {
    # First non-empty, non-comment line — the same rule settings.gradle.kts and build.rs apply.
    local line
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%%#*}"
        line="$(printf '%s' "$line" | tr -d '[:space:]')"
        [[ -n "$line" ]] && { printf '%s' "$line"; return 0; }
    done <"$VERSION_FILE"
    echo "version.sh: $VERSION_FILE is empty" >&2
    return 1
}

# Every file that carries a COPY of the version, with the pattern that finds it. Each entry is
# "<path>|<sed match>|<sed replacement using @V@>"; @V@ is substituted with the version.
#
# The DIRECTORIES come from layout.properties; only the file names are spelled here. A crate that
# moves must not silently drop out of this list — an unmirrored version ships a mislabelled artifact,
# which is the one failure mode this script exists to prevent.
mirrors() {
    local ws app
    ws="$(layout_get dir.cargoWorkspace)"
    app="$(layout_get crate.nodera-app)"
    cat <<EOF
$ws/Cargo.toml|^version = ".*"\$|version = "@V@"
$app/Cargo.toml|^version = ".*"\$|version = "@V@"
$app/tauri.conf.json|^  "version": ".*",\$|  "version": "@V@",
$app/ui/package.json|^  "version": ".*",\$|  "version": "@V@",
EOF
}

current_of() {
    # The version currently written in a mirror file, extracted with the same anchor used to set it.
    local file="$1" match="$2"
    grep -E -m1 "$match" "$ROOT/$file" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?' | head -1
}

check() {
    local version="$1" failed=0 file match replace found
    while IFS='|' read -r file match replace; do
        [[ -z "$file" ]] && continue
        if [[ ! -f "$ROOT/$file" ]]; then
            echo "version.sh: MISSING $file" >&2
            failed=1
            continue
        fi
        found="$(current_of "$file" "$match" || true)"
        if [[ "$found" != "$version" ]]; then
            echo "version.sh: DRIFT $file has '${found:-<none>}', VERSION says '$version'" >&2
            failed=1
        fi
    done < <(mirrors)

    # The Java side has no mirror to compare — it reads VERSION through the build — so the check
    # there is that nobody re-inlined a literal.
    if grep -RnE 'PRODUCT_VERSION = "' "$ROOT/java" --include='*.java' >/dev/null 2>&1; then
        echo "version.sh: DRIFT NoderaConstants.PRODUCT_VERSION is a literal again" >&2
        failed=1
    fi

    if [[ $failed -eq 0 ]]; then
        echo "version.sh: every mirror agrees with VERSION ($version)"
    fi
    return $failed
}

set_version() {
    local version="$1" file match replace
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]; then
        echo "version.sh: '$version' is not a semantic version" >&2
        return 1
    fi
    printf '%s\n' "$version" >"$VERSION_FILE"
    # Rewritten in Python: each mirror is a one-line anchored substitution, and doing it with `sed
    # -i` across four different quoting styles (TOML, JSON, JSON) is how a release script starts
    # corrupting files it half-matched.
    while IFS='|' read -r file match replace; do
        [[ -z "$file" ]] && continue
        [[ -f "$ROOT/$file" ]] || { echo "version.sh: MISSING $file" >&2; return 1; }
        ROOT="$ROOT" FILE="$file" MATCH="$match" REPLACE="${replace//@V@/$version}" python3 - <<'PY'
import os, re, sys
path = os.path.join(os.environ["ROOT"], os.environ["FILE"])
pattern = re.compile(os.environ["MATCH"], re.M)
text = open(path).read()
new, count = pattern.subn(os.environ["REPLACE"].replace("\\", "\\\\"), text, count=1)
if count != 1:
    sys.exit(f"version.sh: no version line matched in {os.environ['FILE']}")
open(path, "w").write(new)
PY
    done < <(mirrors)
    echo "version.sh: set $version"
    check "$version"
}

case "${1:-}" in
    "")        read_version; echo ;;
    --check)   check "$(read_version)" ;;
    --set)     [[ $# -eq 2 ]] || { echo "usage: version.sh --set <x.y.z>" >&2; exit 1; }
               set_version "$2" ;;
    -h|--help) sed -n '2,17p' "$0" ;;
    *)         echo "usage: version.sh [--check | --set <x.y.z>]" >&2; exit 1 ;;
esac
