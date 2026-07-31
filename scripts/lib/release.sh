# shellcheck shell=bash
#
# The release naming table, for shell.
#
# WHY THIS FILE EXISTS
# --------------------
# A release asset name is a contract with three parties that never talk to each other: the person
# downloading it, the workflow uploading it, and the running service that replaces its own
# executable from it (`library/rust/nodera-service/src/update.rs`). Before this file the names were
# composed in the workflow YAML — `build/nodera-tracker`, `build/neoforge-mod.jar` — which meant the
# only description of "what a release contains" was a `gh release create` argument list, and the
# updater's `asset_name` was a string literal typed a second time in two Rust config defaults.
#
# So: this is the one table. Nothing else may compose an asset name. `release_manifest` prints the
# complete expected contents of a release, and `scripts/release.sh --verify` holds a staged
# directory to it, so a platform that silently failed to build is a named missing file rather than a
# release that is quietly one asset short.
#
# THE NAMES
# ---------
#   nodera-neoforge-<version>.jar        the NeoForge client/server mod
#   nodera-paper-<version>.jar           the Paper/Folia endpoint plugin
#   nodera-peer-<version>.jar            the headless always-on node, runnable with `java -jar`
#   nodera-app-<system>-<arch>.<ext>     the Tauri companion app installer
#   nodera-tracker-<arch>-<version>      the tracker service binary
#   nodera-rendezvous-<arch>-<version>   the rendezvous service binary
#
# The token order differs between the jars and the service binaries because it was specified that
# way, and a name is a contract: it is copied literally rather than regularised. The app carries no
# version token for the same reason.
#
# THE TOKENS
# ----------
#   <version>  The RELEASE tag with a leading `v` stripped, NOT the product version. They differ on
#              purpose: the rolling prerelease is tagged `latest` and republished on every push
#              while `/VERSION` stays put, so an asset named for `/VERSION` would be overwritten by
#              a different build under the same name. Falls back to `/VERSION` when no tag is set,
#              which is what a local `scripts/release.sh` produces.
#   <arch>     `x64` or `arm64`. Both are always built.
#   <system>   `linux`, `macos` or `windows`.
#   <ext>      The installer format for that system: deb / dmg / msi.
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/release.sh"
#   release_version_token                 # latest | 0.1.0
#   release_arch_token                    # this machine: x64 | arm64
#   release_asset tracker arm64           # nodera-tracker-arm64-latest
#   release_manifest                      # every asset a complete release contains, one per line

# The architectures and systems a release covers. Listed once; `release_manifest` and every caller
# that loops over platforms reads these rather than repeating them.
NODERA_RELEASE_ARCHES=(x64 arm64)
NODERA_RELEASE_SYSTEMS=(linux macos windows)

# The installer format each system ships. Kept beside the system list because adding a system
# without deciding its format is how an empty asset name reaches `gh release create`.
release_app_extension() {
    case "$1" in
        linux)   printf 'deb\n' ;;
        macos)   printf 'dmg\n' ;;
        windows) printf 'msi\n' ;;
        *)       echo "release.sh: unknown system '$1'" >&2; return 1 ;;
    esac
}

# `uname -m` (or an explicit argument) as a release architecture token.
#
# The mapping is narrow on purpose: an unrecognised machine is an error, not a name containing
# whatever the kernel happened to say. A release asset called `nodera-tracker-i686-latest` would
# upload perfectly and be undownloadable by every updater in the field.
release_arch_token() {
    local machine="${1:-$(uname -m)}"
    case "$machine" in
        x86_64|amd64|x64)      printf 'x64\n' ;;
        aarch64|arm64)         printf 'arm64\n' ;;
        *) echo "release.sh: no release architecture token for '$machine'" >&2; return 1 ;;
    esac
}

# `uname -s` (or an explicit argument) as a release system token.
release_system_token() {
    local system="${1:-$(uname -s)}"
    case "$system" in
        Linux|linux)                       printf 'linux\n' ;;
        Darwin|darwin|macos|macOS)         printf 'macos\n' ;;
        MINGW*|MSYS*|CYGWIN*|Windows_NT|windows) printf 'windows\n' ;;
        *) echo "release.sh: no release system token for '$system'" >&2; return 1 ;;
    esac
}

# The version token: the release tag with a leading `v` stripped, else the product version.
#
# `NODERA_RELEASE_TAG` is what the workflow sets (`latest`, or `v0.2.0` from `refs/tags/v0.2.0`).
# Nothing else may read the tag: a second reader is a second stripping rule.
release_version_token() {
    local tag="${NODERA_RELEASE_TAG:-}"
    if [ -n "$tag" ]; then
        printf '%s\n' "${tag#v}"
        return 0
    fi
    local root="${NODERA_ROOT:-$(layout_root "$(dirname "${BASH_SOURCE[0]}")")}"
    # The same rule `settings.gradle.kts`, `build.rs` and `scripts/version.sh` apply: first
    # non-empty, non-comment line.
    #
    # Read in a `while` rather than piped into `grep -m1`: an early-exiting reader closes the pipe
    # under the writer, and callers run this with `pipefail` set, which turns that SIGPIPE into a
    # failed command. It survives today only because VERSION fits in the pipe buffer.
    local line
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%%#*}"
        line="$(printf '%s' "$line" | tr -d '[:space:]')"
        if [ -n "$line" ]; then
            printf '%s\n' "$line"
            return 0
        fi
    done <"$root/VERSION"
    echo "release.sh: $root/VERSION is empty" >&2
    return 1
}

# One asset name.
#
#   release_asset neoforge|paper|peer            # jars, version only
#   release_asset tracker|rendezvous <arch>      # service binaries, no extension
#   release_asset app <system> <arch>            # installer, no version token
release_asset() {
    local kind="$1"; shift
    local version; version="$(release_version_token)" || return 1
    case "$kind" in
        neoforge|paper|peer)
            printf 'nodera-%s-%s.jar\n' "$kind" "$version" ;;
        tracker|rendezvous)
            local arch; arch="$(release_arch_token "${1:-}")" || return 1
            printf 'nodera-%s-%s-%s\n' "$kind" "$arch" "$version" ;;
        app)
            local system arch ext
            system="$(release_system_token "${1:-}")" || return 1
            arch="$(release_arch_token "${2:-}")" || return 1
            ext="$(release_app_extension "$system")" || return 1
            printf 'nodera-app-%s-%s.%s\n' "$system" "$arch" "$ext" ;;
        *)  echo "release.sh: unknown asset kind '$kind'" >&2; return 1 ;;
    esac
}

# Every asset a COMPLETE release contains, one per line, in publication order.
#
# This is the definition of "complete". `--verify` compares a staged directory against it, and the
# release notes are generated from it, so a deliverable that is added here and nowhere else is
# reported missing rather than forgotten.
release_manifest() {
    local kind system arch
    for kind in neoforge paper peer; do
        release_asset "$kind" || return 1
    done
    for system in "${NODERA_RELEASE_SYSTEMS[@]}"; do
        for arch in "${NODERA_RELEASE_ARCHES[@]}"; do
            release_asset app "$system" "$arch" || return 1
        done
    done
    for kind in tracker rendezvous; do
        for arch in "${NODERA_RELEASE_ARCHES[@]}"; do
            release_asset "$kind" "$arch" || return 1
        done
    done
}

# The two integrity assets, which are not deliverables but are published with them. Named here so
# the workflow and `update.rs` agree on the spelling; `update.rs` pins them as constants.
NODERA_RELEASE_CHECKSUMS="SHA256SUMS"
NODERA_RELEASE_SIGNATURE="SHA256SUMS.sig"
