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
#   <arch>     `x64` or `arm64` — or `universal` for Android, which ships one APK carrying every
#              ABI. See `release_app_arches`.
#   <system>   `linux`, `windows` or `android`. See below for why macOS is not among them.
#   <ext>      The installer format for that system: deb / msi / apk.
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/release.sh"
#   release_version_token                 # latest | 0.1.0
#   release_arch_token                    # this machine: x64 | arm64
#   release_asset tracker arm64           # nodera-tracker-arm64-latest
#   release_manifest                      # every asset a complete release contains, one per line

# The architectures and systems a release covers. Listed once; `release_manifest` and every caller
# that loops over platforms reads these rather than repeating them.
#
# # Why macOS is not here
#
# It was, and the arm64 leg worked — `macos-14` produced a valid `.dmg` on the first run. The x64
# leg did not: `macos-13` is the last x86-64 macOS image GitHub offers, it is deprecated, and a job
# requesting it sat at "Waiting for a runner to pick up this job" for **fifteen hours** without ever
# starting. That blocks `publish`, which needs every leg to finish, so one unobtainable runner held
# the entire release — including the Linux and Windows installers that had built in minutes.
#
# Shipping only macOS-arm64 was the other option and is worse than shipping neither: an Intel Mac
# user downloading the one macOS asset on the page gets a binary that will not run, and nothing on
# the release says which Mac it is for. A platform is supported or it is not.
#
# Reviving it is one matrix entry in `.github/workflows/release.yml` plus `macos` here — the
# `.dmg` path is known to work. What it needs is an x86-64 macOS runner that can actually be
# scheduled, or a decision to cross-build x64 from `macos-14` with `--target x86_64-apple-darwin`
# (`scripts/release.sh --target` already handles the bundle path for a cross build).
NODERA_RELEASE_ARCHES=(x64 arm64)
NODERA_RELEASE_SYSTEMS=(linux windows android)

# The installer format each system ships. Kept beside the system list because adding a system
# without deciding its format is how an empty asset name reaches `gh release create`.
release_app_extension() {
    case "$1" in
        linux)   printf 'deb\n' ;;
        windows) printf 'msi\n' ;;
        android) printf 'apk\n' ;;
        *)       echo "release.sh: unknown system '$1'" >&2; return 1 ;;
    esac
}

# Which architectures each system's installer is published for.
#
# Not every system publishes the same set, which is why this is a function and not one global list.
# Android ships a SINGLE apk carrying every ABI: that is the platform's own convention for a
# sideloaded build, per-ABI splits exist to shrink Play Store downloads, and offering a person two
# APKs would ask them a question about their own phone that they cannot answer. Its arch token is
# therefore `universal`, and it is a real answer rather than a placeholder.
release_app_arches() {
    case "$1" in
        linux|windows) printf '%s\n' "${NODERA_RELEASE_ARCHES[@]}" ;;
        android)       printf 'universal\n' ;;
        *)             echo "release.sh: unknown system '$1'" >&2; return 1 ;;
    esac
}

# `uname -m` (or an explicit argument) as a release architecture token.
#
# The mapping is narrow on purpose: an unrecognised machine is an error, not a name containing
# whatever the kernel happened to say. A release asset called `nodera-tracker-i686-latest` would
# upload perfectly and be undownloadable by every updater in the field.
#
# `NODERA_RELEASE_ARCH` overrides the detection, and on one platform it is REQUIRED rather than a
# convenience: Git for Windows ships an x86-64 build of bash, which runs under emulation on an
# arm64 Windows machine and answers `uname -m` with `x86_64`. The `windows-11-arm` release leg
# therefore reported "host windows/x64" while its Rust toolchain (`win.rustup.rs/aarch64`) built a
# genuine arm64 binary, and would have staged it as `nodera-app-windows-x64.msi` — a second file
# under the name the x64 leg also produces, which `merge-multiple` resolves by silently keeping one.
# A build must not learn its own target by asking the shell it happens to be running in, so CI
# declares it and detection is the local-developer fallback.
release_arch_token() {
    local machine="${1:-${NODERA_RELEASE_ARCH:-$(uname -m)}}"
    case "$machine" in
        x86_64|amd64|x64)      printf 'x64\n' ;;
        aarch64|arm64)         printf 'arm64\n' ;;
        *) echo "release.sh: no release architecture token for '$machine'" >&2; return 1 ;;
    esac
}

# `uname -s` (or an explicit argument) as a release system token.
#
# `NODERA_RELEASE_SYSTEM` overrides it, for the same reason as the architecture above: the leg that
# knows which installer it is producing should say so rather than let a shell infer it.
release_system_token() {
    local system="${1:-${NODERA_RELEASE_SYSTEM:-$(uname -s)}}"
    case "$system" in
        Linux|linux)                       printf 'linux\n' ;;
        MINGW*|MSYS*|CYGWIN*|Windows_NT|windows) printf 'windows\n' ;;
        # Never a `uname -s`: Android is always a cross-build target, so this token only ever
        # arrives declared — from the release matrix, or from `--component android`.
        android|Android)                   printf 'android\n' ;;
        # Named rather than falling through to the generic error, because "unknown system Darwin"
        # would read as a gap in this mapping. It is a decision, not an omission — see the note on
        # NODERA_RELEASE_SYSTEMS.
        Darwin|darwin|macos|macOS)
            echo "release.sh: Nodera publishes no macOS installer — see NODERA_RELEASE_SYSTEMS" >&2
            return 1 ;;
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
            # `universal` is a published architecture token but never a machine, so it does not go
            # through the uname mapping — that one stays narrow, and refusing to recognise a
            # machine it cannot name is the property that makes it worth having.
            if [[ "${2:-}" == "universal" ]]; then
                arch="universal"
            else
                arch="$(release_arch_token "${2:-}")" || return 1
            fi
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
        while IFS= read -r arch; do
            release_asset app "$system" "$arch" || return 1
        done < <(release_app_arches "$system")
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
