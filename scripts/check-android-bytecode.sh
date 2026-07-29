#!/usr/bin/env bash
# ===========================================================================
# nodera check-android-bytecode — refuse bytecode Android cannot run.
#
# Java 21 type-pattern switches (`case SomeType v ->`) compile to an
# `invokedynamic` on `java.lang.runtime.SwitchBootstraps`. Android can neither
# run nor desugar it:
#
#   * at --min-api 26 D8 keeps the call site and ART's SwitchBootstraps throws
#     on its FIRST execution (BootstrapMethodError → ClassCastException);
#   * below 26 D8 cannot find SwitchBootstraps to desugar against and replaces
#     the instruction with a stub that throws at runtime.
#
# Both are latent: everything works until the first message is encoded. On a
# phone the worker shares the app's process, so it takes the UI down with it.
# That is exactly how it was found — `scripts/e2e-android-mesh.sh` crashed at
# the mesh join while every earlier phase passed.
#
# This checks the compiled artefacts, not the source, because the property that
# matters is "no invoke-custom in the dex" and only the compiler can say.
#
#   scripts/check-android-bytecode.sh
# ===========================================================================
set -euo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="$ANDROID_HOME/build-tools/35.0.0"
DIST="$NODERA_ROOT/java/worker/build/install/nodera-headless"

say()  { printf '\033[1;36m[bytecode]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[bytecode] FAIL\033[0m %s\n' "$*" >&2; exit 1; }

[[ -x "$TOOLS/d8" ]] || fail "build-tools 35 is missing — run scripts/android-toolchain.sh"
# Always rebuild. Gradle is incremental, so this costs nothing when nothing moved — and reusing a
# stale distribution is how a guard reports yesterday's answer about today's sources.
if [[ "${NODERA_SKIP_BUILD:-0}" == "1" ]]; then
    [[ -d "$DIST/lib" ]] || fail "NODERA_SKIP_BUILD=1 but there is no distribution at $DIST"
else
    say "building the worker…"
    ( cd "$NODERA_ROOT" && ./gradlew :worker:installDist -q )
fi

STAGE="$(mktemp -d -t nodera-bytecode-XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT

say "dexing the worker's closure…"
"$TOOLS/d8" --min-api 26 --output "$STAGE" "$DIST"/lib/*.jar 2>/dev/null

SITES=$("$TOOLS/dexdump" -d "$STAGE"/classes*.dex 2>/dev/null | grep -c 'invoke-custom' || true)
BOOTSTRAPS=$(strings "$STAGE"/classes*.dex | grep -c 'SwitchBootstraps' || true)

if [[ "$SITES" -ne 0 || "$BOOTSTRAPS" -ne 0 ]]; then
    say "invoke-custom sites: $SITES · SwitchBootstraps references: $BOOTSTRAPS"
    say "the offending methods:"
    "$TOOLS/dexdump" -d "$STAGE"/classes*.dex 2>/dev/null | awk '
      /^[[:space:]]*name[[:space:]]*:/ { m=$3 }
      /invoke-custom/ { print "    " m }' | sort -u
    fail "a type-pattern switch reached the Android build — rewrite it as an instanceof chain"
fi

say "0 invoke-custom sites, 0 SwitchBootstraps references — this dexes to bytecode ART can run"
