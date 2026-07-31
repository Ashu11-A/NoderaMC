#!/usr/bin/env bash
# ===========================================================================
# nodera release — build the deliverables, stage them under their release names, and hold the
# result to the manifest.
#
# ---------------------------------------------------------------------------
# WHY THIS EXISTS
# ---------------------------------------------------------------------------
#
# The release used to be three lines of `gh release create` in a workflow. That made the workflow
# the only description of what a release contains, which had three consequences worth naming,
# because each of them is what this script is shaped to prevent:
#
#   * It shipped three of six deliverables. The companion app, the Paper plugin and the headless
#     peer were built and tested in CI and then thrown away — a `cargo build` and a `./gradlew`
#     whose output nobody could download.
#   * It shipped one architecture. `ubuntu-latest` is x64, so every arm64 operator built from
#     source, and nothing said so.
#   * A leg that silently failed to produce a file would have published a release one asset short,
#     because `gh release create` was handed a literal list and nothing compared that list to
#     anything.
#
# So the deliverables are declared in one place (`scripts/lib/release.sh`), built by one script
# (this one) that CI and a developer run identically, and `--verify` is what turns "we uploaded
# what we had" into "this release is complete or the run is red".
#
# ---------------------------------------------------------------------------
# COMPONENTS
# ---------------------------------------------------------------------------
#
# A release is assembled from three legs, because they need three different machines:
#
#   jars      Architecture-independent. Built once, anywhere with a JDK.
#             nodera-neoforge / nodera-paper / nodera-peer
#   services  Native binaries, one build per architecture.
#             nodera-tracker / nodera-rendezvous
#   app       Native installer, one build per system AND architecture, each needing that
#             platform's own toolchain (a deb and an msi cannot be produced from one machine).
#             No macOS installer is published — see NODERA_RELEASE_SYSTEMS in lib/release.sh.
#             nodera-app
#
# `--component` selects one; the default builds every leg this machine can. CI runs one leg per
# matrix job and the publish job merges the staged directories.
#
# ---------------------------------------------------------------------------
# USAGE
# ---------------------------------------------------------------------------
#
#   scripts/release.sh                       # build every leg this machine can, stage, verify
#   scripts/release.sh --component jars      # one leg (what a CI matrix job runs)
#   scripts/release.sh --component app --target aarch64-pc-windows-msvc
#   scripts/release.sh --no-build            # stage from existing build output
#   scripts/release.sh --names               # print the complete manifest and exit
#   scripts/release.sh --verify              # hold the staging directory to the manifest
#   scripts/release.sh --checksums           # write SHA256SUMS over the staged assets
#   scripts/release.sh --sign                # detached Ed25519 signature over SHA256SUMS
#
# Environment:
#   NODERA_RELEASE_TAG    the release this is for (`latest`, `v0.2.0`). Absent = /VERSION.
#   NODERA_RELEASE_DIR    where assets are staged. Default: build/release.
#   NODERA_RELEASE_STRICT 1 = `--verify` fails on a missing asset (set for tagged releases),
#                         0 = it warns. Default 1.
#   NODERA_SIGNING_KEY    PEM Ed25519 private key for `--sign`. Absent = a loud skip.
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
source "$(dirname "${BASH_SOURCE[0]}")/lib/release.sh"

RELEASE_DIR="${NODERA_RELEASE_DIR:-$NODERA_ARTIFACTS/release}"
STRICT="${NODERA_RELEASE_STRICT:-1}"

log()  { printf '\033[1;36m[release]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[release]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[release] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,66p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# --- args ----------------------------------------------------------------
COMPONENT=""
DO_BUILD=1
RUST_TARGET=""
ACTION="stage"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --component) [[ $# -ge 2 ]] || die "--component needs jars|services|app"
                     COMPONENT="$2"; shift 2 ;;
        --target)    [[ $# -ge 2 ]] || die "--target needs a Rust target triple"
                     RUST_TARGET="$2"; shift 2 ;;
        --no-build)  DO_BUILD=0; shift ;;
        --names)     ACTION="names"; shift ;;
        --verify)    ACTION="verify"; shift ;;
        --checksums) ACTION="checksums"; shift ;;
        --sign)      ACTION="sign"; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "unknown option: $1 (see --help)" ;;
    esac
done

case "${COMPONENT:-all}" in
    all|jars|services|app) ;;
    *) die "unknown component '$COMPONENT' (jars|services|app)" ;;
esac

VERSION_TOKEN="$(release_version_token)"
HOST_ARCH="$(release_arch_token)"
HOST_SYSTEM="$(release_system_token)"

# When the leg DECLARED what it is building and the shell disagrees, say so once, loudly.
#
# The declaration wins — that is the point of it — but the disagreement is worth printing, because
# it is the only visible trace of an emulated shell. `windows-11-arm` runs an x86-64 Git bash and
# answers `uname -m` with `x86_64`; the sole symptom was one line of log reading "host windows/x64"
# on the arm64 leg, and nothing else in the run looked wrong until an asset went missing three jobs
# later. A build that silently disagrees with itself about its own target is worth a line.
if [[ -n "${NODERA_RELEASE_ARCH:-}" ]]; then
    detected="$(release_arch_token "$(uname -m)" 2>/dev/null || echo "unknown")"
    [[ "$detected" != "$HOST_ARCH" ]] && \
        warn "this shell reports $(uname -m) ($detected) but the build declares $HOST_ARCH — trusting the declaration"
fi

# `install` on a target that already exists replaces it; every stage step goes through here so a
# rerun is idempotent and a staged file can never be a half-copy of a previous run.
stage() {
    local source="$1" name="$2" mode="${3:-0644}"
    [[ -f "$source" ]] || die "expected artefact not found: $source"
    mkdir -p "$RELEASE_DIR"
    install -m "$mode" "$source" "$RELEASE_DIR/$name"
    log "staged $name"
}

# ---------------------------------------------------------------------------
# jars — architecture-independent, built once
# ---------------------------------------------------------------------------
#
# `:peer:peerJar` is the fat jar, NOT `installDist`: an operator downloads one file. The dist
# directory is still built by the app leg, which bundles it as a Tauri resource.
build_jars() {
    log "Gradle: :neoforge-mod:jar :paper-plugin:jar :peer:peerJar"
    ( cd "$NODERA_ROOT" && ./gradlew :neoforge-mod:jar :paper-plugin:jar :peer:peerJar )
}

stage_jars() {
    stage "$NODERA_MOD_DIR/build/libs/nodera-neoforge.jar"    "$(release_asset neoforge)"
    stage "$NODERA_PAPER_MODULE/build/libs/nodera-paper.jar"  "$(release_asset paper)"
    stage "$NODERA_PEER_MODULE/build/libs/nodera-peer.jar"    "$(release_asset peer)"
}

# ---------------------------------------------------------------------------
# services — one native build per architecture
# ---------------------------------------------------------------------------
#
# The asset names carry an architecture and no system, which is the shape they were specified in
# and is also the truth: these are the two processes the project itself deploys, on Linux servers.
# A Windows operator runs the container.
build_services() {
    command -v cargo >/dev/null 2>&1 || die "cargo not found. Install the Rust toolchain (rustup)."
    log "cargo build --release (nodera-tracker + nodera-rendezvous), arch $HOST_ARCH"
    ( cd "$NODERA_CARGO_WS" && cargo build --release \
        --bin nodera-tracker --bin nodera-rendezvous )
}

stage_services() {
    local out="$NODERA_RUST_TARGET/release"
    stage "$out/nodera-tracker"    "$(release_asset tracker "$HOST_ARCH")"    0755
    stage "$out/nodera-rendezvous" "$(release_asset rendezvous "$HOST_ARCH")" 0755
}

# ---------------------------------------------------------------------------
# app — one native installer per system and architecture
# ---------------------------------------------------------------------------
#
# The app bundles the headless peer distribution — `build/nodera-headless`, produced by
# `:peer:installDist`. The DIRECTORY shape, not the fat jar, because the app supervises a launcher
# process (`daemon.rs` execs `resources/nodera-headless/bin/nodera-headless`). It is declared as a
# Tauri resource in `app/tauri.<system>.conf.json` and must exist BEFORE the bundler runs: a missing
# resource is not a build failure, it is an installer that ships without the node it supervises.
#
# Those configs map `bin/*` and `lib/*` SEPARATELY rather than using one `**/*` glob, and that is
# load-bearing. Tauri places every file a glob matches at the map's target path, so `**/*` flattened
# the whole distribution into one directory — `resources/nodera-headless/nodera-headless` beside
# fifteen jars. It bundled cleanly and produced an app whose supervisor looks for
# `resources/nodera-headless/bin/nodera-headless` and finds nothing, which is a runtime failure in a
# shipped installer with no build-time symptom at all. It was only visible once the release lane
# started producing real bundles.
app_bundle_format() { release_app_extension "$HOST_SYSTEM"; }

build_app() {
    command -v cargo >/dev/null 2>&1 || die "cargo not found."
    command -v bun   >/dev/null 2>&1 || die "bun not found (the companion UI is a bun project)."

    log "Gradle: :peer:installDist (the app's bundled headless node)"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist )
    rm -rf "$NODERA_ARTIFACTS/nodera-headless"
    mkdir -p "$NODERA_ARTIFACTS"
    cp -r "$NODERA_PEER_MODULE/build/install/nodera-headless" "$NODERA_ARTIFACTS/nodera-headless"

    log "UI: bun install + bun run build"
    ( cd "$NODERA_APP_DIR/ui" && bun install --frozen-lockfile && bun run build )

    # The Tauri CLI comes from the UI's own node_modules rather than `cargo install tauri-cli`:
    # the npm package is a prebuilt binary that bun has already cached, and building the CLI from
    # source costs minutes on every one of six release runners.
    local tauri="$NODERA_APP_DIR/ui/node_modules/.bin/tauri"
    [[ -x "$tauri" ]] || die "tauri CLI missing at $tauri (is @tauri-apps/cli in app/ui devDependencies?)"

    local -a args=(build --bundles "$(app_bundle_format)")
    [[ -n "$RUST_TARGET" ]] && args+=(--target "$RUST_TARGET")
    log "Tauri: tauri ${args[*]}"
    ( cd "$NODERA_APP_DIR" && "$tauri" "${args[@]}" )
}

stage_app() {
    local format target_arch
    format="$(app_bundle_format)"
    # The architecture of the FILE, which is the cross target when there is one and the host
    # otherwise. Naming a cross-built installer after the host is the mistake this line exists to
    # not make.
    if [[ -n "$RUST_TARGET" ]]; then
        target_arch="$(release_arch_token "${RUST_TARGET%%-*}")"
    else
        target_arch="$HOST_ARCH"
    fi

    local bundle_root="$NODERA_APP_DIR/target"
    [[ -n "$RUST_TARGET" ]] && bundle_root="$bundle_root/$RUST_TARGET"
    bundle_root="$bundle_root/release/bundle/$format"

    # Tauri names the file from the product name and version (`Nodera_0.1.0_amd64.deb`), which is
    # neither of the tokens the release uses. Glob for the one file of that format rather than
    # predicting the name: the predicted form has changed twice across Tauri releases.
    local -a found=()
    while IFS= read -r -d '' file; do found+=("$file"); done \
        < <(find "$bundle_root" -maxdepth 1 -type f -name "*.$format" -print0 2>/dev/null)
    [[ ${#found[@]} -gt 0 ]] || die "no .$format bundle under $bundle_root"
    [[ ${#found[@]} -eq 1 ]] || die "${#found[@]} .$format bundles under $bundle_root — expected one: ${found[*]}"

    stage "${found[0]}" "$(release_asset app "$HOST_SYSTEM" "$target_arch")"
}

# ---------------------------------------------------------------------------
# verify / checksums / sign
# ---------------------------------------------------------------------------

# Hold the staging directory to the manifest, in both directions.
#
# Missing assets are the failure this exists for. UNEXPECTED assets are reported too, and that is
# not pedantry: a file in the staging directory that the manifest does not name is either a stale
# artefact from a previous tag (which would be published beside the current one, under a name that
# claims to be a different version) or a deliverable somebody added without declaring.
verify() {
    local name missing=0 unexpected=0
    [[ -d "$RELEASE_DIR" ]] || die "nothing staged: $RELEASE_DIR does not exist"

    local -a expected=()
    while IFS= read -r name; do expected+=("$name"); done < <(release_manifest)

    for name in "${expected[@]}"; do
        if [[ -s "$RELEASE_DIR/$name" ]]; then
            printf '  \033[1;32mok\033[0m      %s\n' "$name"
        else
            printf '  \033[1;31mMISSING\033[0m %s\n' "$name"
            missing=$((missing + 1))
        fi
    done

    # Membership without a pipeline. `printf … | grep -qxF` would be the obvious spelling and is a
    # latent bug: `-q` exits on the first match, the writer takes SIGPIPE, and this script runs
    # under `pipefail` — so a file that IS in the manifest would be reported as unexpected, as soon
    # as the manifest outgrew the pipe buffer.
    contains() {
        local needle="$1" candidate
        shift
        for candidate in "$@"; do
            [[ "$candidate" == "$needle" ]] && return 0
        done
        return 1
    }

    local file base
    for file in "$RELEASE_DIR"/*; do
        [[ -f "$file" ]] || continue
        base="$(basename "$file")"
        [[ "$base" == "$NODERA_RELEASE_CHECKSUMS" || "$base" == "$NODERA_RELEASE_SIGNATURE" ]] && continue
        if ! contains "$base" "${expected[@]}"; then
            printf '  \033[1;33mUNEXPECTED\033[0m %s\n' "$base"
            unexpected=$((unexpected + 1))
        fi
    done

    if [[ $unexpected -gt 0 ]]; then
        die "$unexpected staged file(s) the manifest does not name"
    fi
    if [[ $missing -gt 0 ]]; then
        if [[ "$STRICT" == "1" ]]; then
            die "$missing deliverable(s) missing from the ${VERSION_TOKEN} release"
        fi
        warn "$missing deliverable(s) missing — publishing an INCOMPLETE ${VERSION_TOKEN} release"
        return 0
    fi
    log "all ${#expected[@]} deliverables present for ${VERSION_TOKEN}"
}

# The digest manifest the services' self-update lane reads.
#
# It covers EVERY staged asset, not just the two binaries it used to: the mod jar and the app
# installer are downloaded by people, and a digest they can check costs one line here. The names in
# it are the release names, which is what `update.rs` composes and looks up.
checksums() {
    [[ -d "$RELEASE_DIR" ]] || die "nothing staged: $RELEASE_DIR does not exist"
    ( cd "$RELEASE_DIR"
      rm -f "$NODERA_RELEASE_CHECKSUMS" "$NODERA_RELEASE_SIGNATURE"
      # Sorted rather than glob-ordered: a stable order makes two runs of the same commit produce
      # byte-identical manifests, which is the only way a human can diff two releases.
      for file in *; do [ -f "$file" ] && printf '%s\n' "$file"; done \
          | sort | xargs sha256sum > "$NODERA_RELEASE_CHECKSUMS" )
    cat "$RELEASE_DIR/$NODERA_RELEASE_CHECKSUMS"
}

# Provenance, not just integrity (L-81). The digest above proves a download was not corrupted; it
# proves nothing about who wrote the manifest, because whoever can publish to the release can
# publish a self-consistent pair. A detached Ed25519 signature over the manifest is what the updater
# checks BEFORE it reads a digest.
#
# Skipped LOUDLY when no key is configured. A release that silently ships unsigned is exactly the
# state this step exists to end.
sign() {
    local manifest="$RELEASE_DIR/$NODERA_RELEASE_CHECKSUMS"
    [[ -f "$manifest" ]] || die "no $NODERA_RELEASE_CHECKSUMS to sign — run --checksums first"
    if [[ -z "${NODERA_SIGNING_KEY:-}" ]]; then
        warn "NODERA_SIGNING_KEY is not set — this release is UNSIGNED and the update lane can only verify integrity (L-81)."
        return 0
    fi
    ( umask 077
      cd "$RELEASE_DIR"
      printf '%s' "$NODERA_SIGNING_KEY" > signing.pem
      openssl pkeyutl -sign -rawin -inkey signing.pem \
          -in "$NODERA_RELEASE_CHECKSUMS" -out "$NODERA_RELEASE_SIGNATURE"
      # Verify what was just produced. A signature nobody checked can be malformed for a whole
      # release cycle before anyone notices.
      openssl pkey -in signing.pem -pubout -out signing.pub
      openssl pkeyutl -verify -rawin -pubin -inkey signing.pub \
          -in "$NODERA_RELEASE_CHECKSUMS" -sigfile "$NODERA_RELEASE_SIGNATURE"
      rm -f signing.pem signing.pub )
    chmod 0644 "$RELEASE_DIR/$NODERA_RELEASE_SIGNATURE"
    log "signed $NODERA_RELEASE_CHECKSUMS"
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
case "$ACTION" in
    names)     release_manifest; exit 0 ;;
    verify)    verify; exit 0 ;;
    checksums) checksums; exit 0 ;;
    sign)      sign; exit 0 ;;
esac

log "release $VERSION_TOKEN — component ${COMPONENT:-all}, host $HOST_SYSTEM/$HOST_ARCH"
mkdir -p "$RELEASE_DIR"

if [[ "$COMPONENT" == "jars" || "$COMPONENT" == "" ]]; then
    [[ "$DO_BUILD" -eq 1 ]] && build_jars
    stage_jars
fi
if [[ "$COMPONENT" == "services" || "$COMPONENT" == "" ]]; then
    [[ "$DO_BUILD" -eq 1 ]] && build_services
    stage_services
fi
if [[ "$COMPONENT" == "app" ]]; then
    [[ "$DO_BUILD" -eq 1 ]] && build_app
    stage_app
fi

log "staged into $RELEASE_DIR:"
ls -la "$RELEASE_DIR"

# A single-component run stages one leg of a release and cannot be complete, so it is not held to
# the manifest — the publish job is, after it has merged every leg. A full local run reports the
# gaps without failing: the app installers for five other platforms cannot be built here, and
# refusing to finish over that would make the script useless on a developer's machine.
if [[ -z "$COMPONENT" ]]; then
    STRICT=0
    verify
fi
