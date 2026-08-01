#!/usr/bin/env bash
# ===========================================================================
# nodera android-apk — build, sign, and stage the Android APK.
#
# `cargo tauri android build` emits an UNSIGNED apk, and Android will not
# install one. So this script owns the three steps that turn it into
# something you can put on a phone, and leaves the result in ./build/ where
# every other artifact of this project lands:
#
#   1. build   — the Rust cdylib is cross-compiled and the Gradle project
#                assembles the apk
#   2. align   — zipalign, which the installer requires for uncompressed
#                native libraries
#   3. sign    — apksigner with the project's own development key
#
#   scripts/android-apk.sh                  # release apk into ./build
#   scripts/android-apk.sh --debug          # debug apk (bigger, no R8)
#   scripts/android-apk.sh --install        # …and adb install it
#   scripts/android-apk.sh --require-release-key   # refuse the dev key (what CI runs)
#
# ---------------------------------------------------------------------------
# TWO KEYS, AND WHY THE DIFFERENCE IS ENFORCED
# ---------------------------------------------------------------------------
#
# DEVELOPMENT (the default). Generated on first run at ~/.nodera/android-release.jks with a
# hardcoded password, because Android will not install an unsigned apk and a developer should not
# have to think about it. Every checkout of this repository can mint one.
#
# RELEASE. Supplied through the environment — `NODERA_ANDROID_KEYSTORE_BASE64` holds the keystore
# and `NODERA_ANDROID_KEY_PASS` its password, both from repository secrets. Decoded to a mode-600
# file under a temporary directory and deleted on exit, including on failure.
#
# `--require-release-key` refuses to run when the release key is absent, and the release lane always
# passes it. That flag is the whole point: without it the fallback above is silent, and the failure
# it produces is not a build error but a published apk signed with a throwaway key — one that anyone
# can reproduce, and that no future genuine release can ever update, because Android identifies an
# app by its signing certificate and refuses an update signed by a different one.
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
APP_DIR="$NODERA_APP_DIR"
OUT_DIR="$NODERA_ARTIFACTS"

# Pinned to match scripts/android-toolchain.sh. A build that follows "latest"
# produces a different artifact every month.
NDK_VERSION="26.1.10909125"
BUILD_TOOLS="34.0.0"
# Dexing needs a NEWER d8 than signing needs: Java 21 class files are only
# readable by R8 8.3+, which ships in build-tools 35.
DEX_BUILD_TOOLS="35.0.0"
# The dex floor, and therefore the app's floor. ONE constant, because they are one decision: the
# worker's classes are dexed at this API level, so an install below it is an app that starts and
# then dies inside the worker. The generated Gradle project's `minSdk` is patched to match below —
# it shipped as 24 while this said 26, and the comment beside them claimed they agreed (M-NET-4).
DEX_MIN_API="26"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
KEYSTORE="${NODERA_ANDROID_KEYSTORE:-$HOME/.nodera/android-release.jks}"
KEY_ALIAS="${NODERA_ANDROID_KEY_ALIAS:-nodera}"
KEY_PASS="${NODERA_ANDROID_KEY_PASS:-noderadev}"

PROFILE="release"
DO_INSTALL=0
REQUIRE_RELEASE_KEY=0
# Re-dexing the worker costs a minute and a lot of memory. It only has to happen when the worker
# changed, so a UI-only rebuild can skip it and reuse the staged asset.
SKIP_WORKER=0
TARGET="${NODERA_ANDROID_TARGET:-aarch64}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)   PROFILE="debug" ;;
    --require-release-key) REQUIRE_RELEASE_KEY=1 ;;
    --skip-worker) SKIP_WORKER=1 ;;
    --install) DO_INSTALL=1 ;;
    --target)  TARGET="$2"; shift ;;
    -h|--help) sed -n '2,44p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '\033[1;36m[apk]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[apk]\033[0m %s\n' "$*" >&2; exit 1; }

# ONE cleanup and ONE trap, installed here before anything creates a temporary.
#
# There used to be a `trap ... EXIT` further down with a comment warning that a second one would
# silently replace it. That warning was right and the arrangement invited exactly what it warned
# about, so there is now a single handler and nothing else may call `trap`. It matters more than it
# did: one of the temporaries is now a decoded signing key, and a leaked EXIT trap means private key
# material surviving on a runner.
STAGE=""
ALIGNED=""
KEYSTORE_TMP=""
cleanup() {
  [[ -n "$STAGE" ]] && rm -rf "$STAGE"
  [[ -n "$ALIGNED" ]] && rm -f "$ALIGNED"
  # `shred` first where it exists: the decoded keystore is the one temporary whose contents matter
  # after deletion. Best-effort — on a copy-on-write filesystem it proves nothing, and the real
  # protection is that the file was mode 600 in a private directory for the length of one build.
  if [[ -n "$KEYSTORE_TMP" && -f "$KEYSTORE_TMP" ]]; then
    command -v shred >/dev/null 2>&1 && shred -u "$KEYSTORE_TMP" 2>/dev/null || rm -f "$KEYSTORE_TMP"
  fi
  return 0
}
trap cleanup EXIT

# --- the signing key, resolved BEFORE the build --------------------------
#
# Before, not after: the build is a cross-compile plus a Gradle assemble plus a dex of the whole
# worker. Discovering a missing secret at the signing step means twenty minutes spent to produce an
# artifact that is then thrown away, and a failure whose cause is at the very bottom of a long log.
resolve_signing_key() {
  if [[ -n "${NODERA_ANDROID_KEYSTORE_BASE64:-}" ]]; then
    KEYSTORE_TMP="$(mktemp -t nodera-release-key-XXXXXX.jks)"
    chmod 600 "$KEYSTORE_TMP"
    # `tr -d` because a secret pasted through a web form arrives with newlines in it, and base64(1)
    # rejects those on some platforms rather than ignoring them.
    printf '%s' "$NODERA_ANDROID_KEYSTORE_BASE64" | tr -d '\n\r ' | base64 -d > "$KEYSTORE_TMP" \
      || die "NODERA_ANDROID_KEYSTORE_BASE64 is not valid base64"
    [[ -s "$KEYSTORE_TMP" ]] || die "NODERA_ANDROID_KEYSTORE_BASE64 decoded to an empty file"
    KEYSTORE="$KEYSTORE_TMP"
    # Proves the password matches the keystore NOW, so a wrong secret is one clear line here rather
    # than an apksigner failure after the whole build.
    keytool -list -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -storepass "$KEY_PASS" >/dev/null 2>&1 \
      || die "the release keystore does not open with alias '$KEY_ALIAS' and the supplied password"
    say "signing    RELEASE key from NODERA_ANDROID_KEYSTORE_BASE64 (alias $KEY_ALIAS)"
    return 0
  fi

  if [[ "$REQUIRE_RELEASE_KEY" == "1" ]]; then
    die "no release signing key.
    NODERA_ANDROID_KEYSTORE_BASE64 and NODERA_ANDROID_KEY_PASS must both be set.
    In CI they come from the repository secrets of the same name (plus the optional
    NODERA_ANDROID_KEY_ALIAS, default 'nodera').
    Refusing to fall back to the development key: it is generated by this script with a
    hardcoded password, so anyone can reproduce it, and Android will never let a genuinely
    signed release update an install that was signed with it."
  fi

  say "signing    development key at $KEYSTORE (not a release key)"
}
resolve_signing_key

# --- the JDK -------------------------------------------------------------
#
# The Android Gradle Plugin does not understand class files newer than JDK 21,
# and this project's own build runs on a newer JDK. So the Android build gets
# its own JAVA_HOME rather than the shell's, and says which one it picked.
pick_jdk() {
  if [[ -n "${NODERA_ANDROID_JAVA_HOME:-}" ]]; then
    echo "$NODERA_ANDROID_JAVA_HOME"; return
  fi
  local candidate
  for candidate in "$HOME/.sdkman/candidates/java/21-tem" \
                   "$HOME/.sdkman/candidates/java/21"* \
                   /usr/lib/jvm/java-21-openjdk* \
                   /usr/lib/jvm/temurin-21*; do
    [[ -x "$candidate/bin/javac" ]] && { echo "$candidate"; return; }
  done
  echo ""
}

JAVA_HOME_ANDROID="$(pick_jdk)"
[[ -n "$JAVA_HOME_ANDROID" ]] || die "no JDK 21 found — install one (sdk install java 21-tem) or set NODERA_ANDROID_JAVA_HOME"

export JAVA_HOME="$JAVA_HOME_ANDROID"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

[[ -d "$NDK_HOME" ]] || die "NDK $NDK_VERSION is missing — run scripts/android-toolchain.sh"

TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS"
[[ -x "$TOOLS/apksigner" ]] || die "build-tools $BUILD_TOOLS is missing — run scripts/android-toolchain.sh"

say "JDK        $JAVA_HOME"
say "profile    $PROFILE (target $TARGET)"

# The Tauri CLI — `cargo tauri`, and for Android it must be that one.
#
# The desktop legs in `scripts/release.sh` use the npm CLI out of `app/ui/node_modules`, because it
# is a prebuilt binary bun has already fetched and `cargo install tauri-cli` compiles for minutes.
# Doing the same here looks like a free win and is not, because `tauri android init` GENERATES a
# Gradle project that calls the CLI back, and it bakes in whichever form was used to generate it.
# Invoked through the npm binary it writes `buildSrc/.../BuildTask.kt` with
#
#     val executable = """node"""; args = ["tauri", "android", "android-studio-script"]
#
# and `node tauri` resolves `tauri` as a FILE relative to `app/`, which does not exist — the CLI
# lives in `app/ui/node_modules`. Every Gradle assemble then dies with
# `Cannot find module '.../app/tauri'`, long after init reported success.
#
# So Android requires the Rust CLI. It is a hard requirement rather than a preference, because the
# failure it prevents is delayed and its message names neither the cause nor this script.
if ! command -v cargo-tauri >/dev/null 2>&1; then
  die "the Android build needs the Rust Tauri CLI: cargo install tauri-cli --version '^2' --locked
    (the npm CLI in app/ui/node_modules cannot be used here — it generates a Gradle project that
     calls back with 'node tauri', which resolves to nothing from the app directory)"
fi
TAURI_CMD=(cargo tauri)

# --- 1. the generated Android project ------------------------------------
#
# `app/gen/` is gitignored: Tauri generates it and an edit made there is lost. That is fine on a
# machine where somebody has run `tauri android init` once, and it is why a fresh checkout — every
# CI run — had no Gradle project at all:
#
#   failed to read Android Gradle build file .../app/gen/android/app/build.gradle.kts:
#   No such file or directory (os error 2)
#
# Worse than the error: every patch below (minSdk, proguard keeps, the manifest permissions and the
# nodera:// filter) is guarded by `[[ -f ... ]]`, so they would ALL have silently skipped on a tree
# where the project existed but was incomplete. Generating it here is what makes those guards
# describe a file that is really there.
# BEFORE the worker step, and that ordering is load-bearing: the worker stages its dex into
# `gen/android/app/src/main/assets`, and `mkdir -p` on that path leaves a directory tree that is not
# a project. `tauri android init` then refuses it — "Project directory …/java/dev/nodera/app does
# not exist" — which reads like a broken identifier and is really just "something got here first".
if [[ ! -f "$APP_DIR/gen/android/app/build.gradle.kts" ]]; then
  say "gen        no Android project yet — running tauri android init"
  ( cd "$APP_DIR" && "${TAURI_CMD[@]}" android init ) \
    || die "tauri android init failed — the Gradle project could not be generated"
  [[ -f "$APP_DIR/gen/android/app/build.gradle.kts" ]] \
    || die "tauri android init reported success but produced no app/build.gradle.kts"
fi

# --- 2. the Java worker, as dex ------------------------------------------
#
# The phone runs the SAME worker the desktop supervises. Two things make that
# possible and both are load-bearing:
#
#   * D8 from build-tools 35. Build-tools 34's R8 8.2 cannot read Java 21 class
#     files at all ("Unsupported class file major version 65") — which is the
#     real reason this was long believed impossible, not any ART limitation.
#   * `d8` emits classes only. The jar's RESOURCES have to be merged back in by
#     hand, or the worker starts and then dies on a missing `VERSION` and finds
#     no SLF4J provider.
#
# Foreign-platform natives (rocksdb/zstd .so/.dll/.jnilib for desktop ABIs) are
# dropped: they cannot load on Android, they are 60 MB of the payload, and the
# worker reaches "online" without them.
DEX_TOOLS="$ANDROID_HOME/build-tools/$DEX_BUILD_TOOLS"
[[ -x "$DEX_TOOLS/d8" ]] || die "build-tools $DEX_BUILD_TOOLS is missing (needed for Java 21 dexing) — run scripts/android-toolchain.sh"

ASSETS="$APP_DIR/gen/android/app/src/main/assets"
if [[ "$SKIP_WORKER" == "1" && -f "$ASSETS/nodera-worker.jar" ]]; then
  say "worker     reusing $(numfmt --to=iec "$(stat -c %s "$ASSETS/nodera-worker.jar")") staged asset (--skip-worker)"
else
say "building the worker…"
( cd "$NODERA_ROOT" && ./gradlew :peer:installDist -q )
WORKER_DIST="$NODERA_PEER_MODULE/build/install/nodera-headless"
[[ -d "$WORKER_DIST/lib" ]] || die "the worker distribution is missing at $WORKER_DIST"

STAGE="$(mktemp -d -t nodera-worker-dex-XXXXXX)"
mkdir -p "$STAGE/dex" "$STAGE/payload"

# --min-api $DEX_MIN_API is FORCED to match the app's own minSdk (patched below, and asserted
# afterwards). It is safe ONLY because no Java 21
# type-pattern switch survives in the sources this dexes.
#
# Those compile to an `invokedynamic` on `java.lang.runtime.SwitchBootstraps`,
# and Android can neither run nor desugar it:
#
#   * at --min-api 26 D8 leaves the call site alone (invokedynamic is native
#     from API 26), and ART's own SwitchBootstraps throws on the FIRST
#     execution — `BootstrapMethodError … ClassCastException`;
#   * below 26 D8 tries to desugar, cannot find `SwitchBootstraps` (Android
#     does not ship it, and desugar_jdk_libs does not provide it), and quietly
#     replaces the instruction with a stub that throws
#     "Instruction is unrepresentable in DEX V35: invoke-dynamic".
#
# Both are LATENT: the worker boots, announces and serves state, then dies the
# moment it encodes its first peer-to-peer message. On a phone that takes the
# whole app with it, because the worker runs in this process.
#
# So the fix is in the Java, not in a flag: every type-pattern switch is an
# `instanceof` chain. `scripts/check-android-bytecode.sh` guards that.
"$DEX_TOOLS/d8" --min-api "$DEX_MIN_API" --output "$STAGE/dex" "$WORKER_DIST"/lib/*.jar

for jar in "$WORKER_DIST"/lib/*.jar; do
  ( cd "$STAGE/payload" && unzip -qo -DD "$jar" \
      -x '*.class' 'META-INF/*.SF' 'META-INF/*.DSA' 'META-INF/*.RSA' 2>/dev/null ) || true
done
find "$STAGE/payload" \( -name '*.so' -o -name '*.dll' -o -name '*.jnilib' -o -name '*.dylib' \) -delete
rm -rf "$STAGE/payload/META-INF/maven" "$STAGE/payload/META-INF/versions"
cp "$STAGE"/dex/classes*.dex "$STAGE/payload/"

mkdir -p "$ASSETS"
rm -f "$ASSETS/nodera-worker.jar"
( cd "$STAGE/payload" && zip -qr "$ASSETS/nodera-worker.jar" . )
say "worker     $(numfmt --to=iec "$(stat -c %s "$ASSETS/nodera-worker.jar")") of dex + resources"
fi

# The Kotlin that loads it. Copied from the tracked source of truth because
# gen/ is disposable — Tauri regenerates it and an edit made there is lost.
KOTLIN_DST="$APP_DIR/gen/android/app/src/main/java/dev/nodera/app"
mkdir -p "$KOTLIN_DST/ui"
cp "$APP_DIR/android/kotlin/"*.kt "$KOTLIN_DST/"
# The Compose interface lives in the `ui` sub-package, and a flat `*.kt` copy silently leaves it
# out: the build then fails with two dozen "Unresolved reference" lines naming symbols that are
# right there in the tree. Copied explicitly, and asserted, because "the file exists in the repo" is
# not the same statement as "the file reached the generated project".
cp "$APP_DIR/android/kotlin/ui/"*.kt "$KOTLIN_DST/ui/"
[[ -f "$KOTLIN_DST/ui/Theme.kt" ]] \
  || die "the Compose sources did not reach $KOTLIN_DST/ui — the interface would not compile"

# minSdk. Patched, not assumed: Tauri generates this file with its own floor (24), and the worker's
# dex floor is $DEX_MIN_API. When they disagree the APK installs happily on API 24-25 and the worker
# dies at runtime with an instruction the device cannot represent — an app that is broken only on the
# oldest phones, which are the least likely to report it (M-NET-4).
GRADLE_APP="$APP_DIR/gen/android/app/build.gradle.kts"
# Not `if [[ -f ]]` any more. The project is generated above, so an absent file here means the
# generation produced something unexpected — and skipping the minSdk patch is M-NET-4: an APK that
# installs on API 24-25 and dies inside the worker, on exactly the phones least likely to report it.
[[ -f "$GRADLE_APP" ]] || die "no $GRADLE_APP after generation — cannot patch minSdk"

MIN_SDK_NOW="$(sed -n 's/.*minSdk *= *\([0-9]\+\).*/\1/p' "$GRADLE_APP" | head -1)"
[[ -n "$MIN_SDK_NOW" ]] \
  || die "no minSdk found in $GRADLE_APP — cannot prove the APK's floor matches the dex floor"
if [[ "$MIN_SDK_NOW" != "$DEX_MIN_API" ]]; then
  say "gradle     raising minSdk $MIN_SDK_NOW -> $DEX_MIN_API (the worker's dex floor)"
  sed -i "s/minSdk *= *$MIN_SDK_NOW/minSdk = $DEX_MIN_API/" "$GRADLE_APP"
fi
MIN_SDK_NOW="$(sed -n 's/.*minSdk *= *\([0-9]\+\).*/\1/p' "$GRADLE_APP" | head -1)"
[[ "$MIN_SDK_NOW" == "$DEX_MIN_API" ]] \
  || die "minSdk is $MIN_SDK_NOW but the worker is dexed at $DEX_MIN_API — refusing to build an APK that installs where it cannot run"

# androidx.documentfile: needed to read the NAME of a folder chosen through the Storage Access
# Framework. Added here because Tauri regenerates this Gradle file.
if ! grep -q "androidx.documentfile" "$GRADLE_APP"; then
  say "gradle     adding androidx.documentfile"
  python3 - "$GRADLE_APP" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()
anchor = '    implementation("androidx.appcompat:appcompat:1.7.1")\n'
if anchor in text:
    text = text.replace(anchor, anchor + '    implementation("androidx.documentfile:documentfile:1.0.1")\n', 1)
    open(path, 'w').write(text)
PYEOF
fi
grep -q "androidx.documentfile" "$GRADLE_APP" \
  || die "androidx.documentfile is not in $GRADLE_APP — the folder picker would be missing from the APK"

# androidx.browser: Chrome Custom Tabs, the first rung of `NoderaBrowser`'s ladder — the user's own
# browser engine drawn inside this task, so the back gesture returns to Nodera rather than leaving
# it. Without the dependency the class is absent, the rung throws, and every link silently falls
# through to a full browser switch. Added here for the same reason as the line above: Tauri
# regenerates this Gradle file.
if ! grep -q "androidx.browser" "$GRADLE_APP"; then
  say "gradle     adding androidx.browser (custom tabs)"
  python3 - "$GRADLE_APP" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()
anchor = '    implementation("androidx.appcompat:appcompat:1.7.1")\n'
if anchor in text:
    text = text.replace(anchor, anchor + '    implementation("androidx.browser:browser:1.8.0")\n', 1)
    open(path, 'w').write(text)
PYEOF
fi
grep -q "androidx.browser" "$GRADLE_APP" \
  || die "androidx.browser is not in $GRADLE_APP — custom tabs would be missing from the APK"

# Jetpack Compose. The Android front end is native now — a Compose activity, not the desktop's React
# bundle in a WebView — because `dynamicDarkColorScheme(context)` is the only way to actually read
# the system wallpaper, and imitating Material You in CSS while asking the user to pick an accent
# was the honest version of not having it.
#
# Added here for the same reason as every block above: Tauri regenerates this Gradle file, so a hand
# edit to `gen/` is lost on the next `tauri android init`.
if ! grep -q "androidx.compose" "$GRADLE_APP"; then
  say "gradle     adding Jetpack Compose"
  python3 - "$GRADLE_APP" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()
anchor = '    implementation("androidx.appcompat:appcompat:1.7.1")\n'
deps = (
    '    implementation(platform("androidx.compose:compose-bom:2024.06.00"))\n'
    '    implementation("androidx.compose.ui:ui")\n'
    '    implementation("androidx.compose.material3:material3")\n'
    '    implementation("androidx.compose.material:material-icons-extended")\n'
    ''
    '    implementation("androidx.activity:activity-compose:1.9.2")\n'
    '    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")\n'
)
if anchor in text:
    text = text.replace(anchor, anchor + deps, 1)

# `buildFeatures { compose = true }` inside the existing android block.
if 'compose = true' not in text:
    marker = '    buildFeatures {\n'
    if marker in text:
        text = text.replace(marker, marker + '        compose = true\n', 1)
    else:
        text = text.replace(
            '\nandroid {\n',
            '\nandroid {\n    buildFeatures {\n        compose = true\n    }\n',
            1,
        )
open(path, 'w').write(text)
PYEOF
fi
grep -q "androidx.compose.material3:material3" "$GRADLE_APP" \
  || die "Compose is not in $GRADLE_APP — the native interface would not compile"
grep -q "compose = true" "$GRADLE_APP" \
  || die "buildFeatures { compose = true } is missing from $GRADLE_APP"

# Kotlin 2.x in the generated project.
#
# Tauri generates it on 1.9.25, and that no longer compiles here: `compileSdk 36` pulls AndroidX
# artifacts (lifecycle 2.10, activity 1.10) whose metadata a 1.9 compiler cannot read, and the
# failure is not a version message — it is `Couldn't inline method call: CompositionLocal.<get-current>`,
# an internal backend crash naming no file. Bumped rather than worked around, because pinning the
# AndroidX side down instead would mean pinning `compileSdk` too.
KOTLIN_WANTED="2.0.21"
if grep -q 'kotlin-gradle-plugin:1\.' "$APP_DIR/gen/android/build.gradle.kts" 2>/dev/null; then
  say "gradle     raising Kotlin to $KOTLIN_WANTED (1.9 cannot read the AndroidX metadata here)"
  sed -i -E "s|(kotlin-gradle-plugin:)[0-9]+\.[0-9]+\.[0-9]+|\1$KOTLIN_WANTED|" \
    "$APP_DIR/gen/android/build.gradle.kts"
fi

# The Compose compiler.
#
# THE ONE MOST LIKELY TO BREAK ON AN UPGRADE. Kotlin 1.x wants
# `composeOptions { kotlinCompilerExtensionVersion = ... }`, pinned to a version that must match the
# Kotlin version exactly; Kotlin 2.x replaced it with a Gradle *plugin* and ignores the old block.
# Applying the wrong one fails with "Compose Compiler plugin not applied", a message naming neither
# this script nor the version that decided it — so the Kotlin version is READ out of the generated
# project rather than assumed, and both paths are here.
#
# `|| true` is load-bearing: under `set -e` an assignment whose pipeline finds nothing takes the
# whole script down, which is exactly what happened the first time this block ran.
KOTLIN_VERSION="$(grep -Eo 'kotlin-gradle-plugin:[0-9]+\.[0-9]+\.[0-9]+' "$APP_DIR/gen/android/build.gradle.kts" 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
if [[ -z "$KOTLIN_VERSION" ]]; then
  KOTLIN_VERSION="$(grep -Eo 'org\.jetbrains\.kotlin[^"]*" *version *"[0-9.]+' "$APP_DIR/gen/android/build.gradle.kts" 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
fi
KOTLIN_MAJOR="${KOTLIN_VERSION%%.*}"
say "gradle     kotlin ${KOTLIN_VERSION:-unknown} in the generated project"

# Kotlin 1.9.x → compiler extension 1.5.15. The pairing is exact: a mismatch is a build failure that
# names two version numbers and no file.
COMPOSE_COMPILER="1.5.15"

if [[ "${KOTLIN_MAJOR:-1}" -ge 2 ]]; then
  if ! grep -q 'kotlin.plugin.compose' "$GRADLE_APP"; then
    say "gradle     applying the Kotlin 2.x Compose compiler plugin"
    python3 - "$GRADLE_APP" "$APP_DIR/gen/android/build.gradle.kts" "${KOTLIN_VERSION:-2.0.20}" <<'PYEOF'
import sys
app, root, version = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(app).read()
if 'plugins {' in text and 'kotlin.plugin.compose' not in text:
    text = text.replace('plugins {', 'plugins {\n    id("org.jetbrains.kotlin.plugin.compose")', 1)
    open(app, 'w').write(text)
top = open(root).read()
if 'kotlin.plugin.compose' not in top:
    line = '        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:%s")\n' % version
    if 'classpath("org.jetbrains.kotlin:kotlin-gradle-plugin' in top:
        at = top.index('classpath("org.jetbrains.kotlin:kotlin-gradle-plugin')
        eol = top.index('\n', at) + 1
        top = top[:eol] + line + top[eol:]
        open(root, 'w').write(top)
PYEOF
  fi
  grep -q 'kotlin.plugin.compose' "$GRADLE_APP" \
    || die "the Kotlin 2.x Compose plugin is not applied in $GRADLE_APP"
else
  if ! grep -q 'kotlinCompilerExtensionVersion' "$GRADLE_APP"; then
    say "gradle     pinning the Kotlin 1.x Compose compiler extension ($COMPOSE_COMPILER)"
    python3 - "$GRADLE_APP" "$COMPOSE_COMPILER" <<'PYEOF'
import sys
path, version = sys.argv[1], sys.argv[2]
text = open(path).read()
block = '    composeOptions {\n        kotlinCompilerExtensionVersion = "%s"\n    }\n' % version
marker = '    buildFeatures {\n'
if marker in text:
    text = text.replace(marker, block + marker, 1)
    open(path, 'w').write(text)
PYEOF
  fi
  grep -q 'kotlinCompilerExtensionVersion' "$GRADLE_APP" \
    || die "the Kotlin 1.x Compose compiler extension is not pinned in $GRADLE_APP"
fi

# Keep the classes the RUST side calls by name. R8 cannot see a reflective JNI call, so it renamed
# `NoderaStorage.pick` to `a` and the folder picker failed with:
#
#   java.lang.NoSuchMethodError: no static method "Ldev/nodera/app/NoderaStorage;.pick()V"
#
# Only these four types are reachable from native code; everything else stays minifiable.
#
# Checked ONE AT A TIME, and this is not fussiness. The block used to be guarded by a single
# `grep -q NoderaStorage`: add a fourth class to the list and, on any tree where `gen/` survived an
# earlier build, the guard sees NoderaStorage already there and skips the whole block — so the new
# rule never lands. R8 then keeps the class name and strips every member, and the dex holds an empty
# `NoderaBrowser`. On the device that is `NoSuchMethodError` from Rust, which is exactly how tapping
# a link came to kill the app. A guard that tests a different thing from what it protects is worse
# than no guard, because it looks like one.
PROGUARD="$APP_DIR/gen/android/app/proguard-rules.pro"
if [[ -f "$PROGUARD" ]]; then
  JNI_CLASSES=(NoderaStorage NoderaWorker NoderaBridge NoderaBrowser NoderaCore NoderaEvents)
  for CLASS in "${JNI_CLASSES[@]}"; do
    if ! grep -q "dev.nodera.app.$CLASS" "$PROGUARD"; then
      say "proguard   keeping dev.nodera.app.$CLASS (called from Rust over JNI)"
      printf '\n# Called from Rust over JNI — invisible to R8'"'"'s reachability analysis.\n-keep class dev.nodera.app.%s { *; }\n' \
        "$CLASS" >> "$PROGUARD"
    fi
  done
  # Asserted rather than assumed: a missing keep is invisible in a green build and only shows up as
  # a crash on a device, which is the whole history of this block.
  for CLASS in "${JNI_CLASSES[@]}"; do
    grep -q "dev.nodera.app.$CLASS" "$PROGUARD" \
      || die "proguard-rules.pro does not keep dev.nodera.app.$CLASS — R8 would strip it and Rust would crash calling it"
  done
fi

# Two permissions Tauri does not generate, injected here because it regenerates the manifest:
#
#  * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — without it Android refuses the per-app battery intent
#    and the user lands on a list of every installed app instead of on the one screen they need.
#  * ACCESS_NETWORK_STATE — without it ConnectivityManager answers nothing, so the app cannot tell
#    Wi-Fi from mobile data and the "never seed on mobile data" rule would silently never fire.
#    A normal permission: granted at install, no runtime prompt.
MANIFEST="$APP_DIR/gen/android/app/src/main/AndroidManifest.xml"
if [[ -f "$MANIFEST" ]]; then
  say "manifest   declaring battery + network-state permissions and the nodera:// filter"
  python3 - "$MANIFEST" <<'PYEOF'
import re
import sys
path = sys.argv[1]
text = open(path).read()
original = text

anchor = '    <uses-permission android:name="android.permission.INTERNET" />\n'
wanted = [
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.ACCESS_NETWORK_STATE",
]
if anchor in text:
    additions = "".join(
        f'    <uses-permission android:name="{name}" />\n'
        for name in wanted
        if name not in text
    )
    if additions:
        text = text.replace(anchor, anchor + additions, 1)

# The `nodera://tracker-store?url=...` filter — what makes a website's "add this store" button
# open this app, the way Keiyoushi's buttons open Mihon.
#
# BROWSABLE is the load-bearing category: without it a browser will not hand the link over, and
# the button silently does nothing. DEFAULT is what lets an implicit VIEW intent match at all.
#
# Added here rather than in gen/, which is regenerated by `cargo tauri android init` and is
# gitignored. Idempotent: the deep-link plugin may already have generated this from
# tauri.conf.json, and two identical filters would be two entries in the Android "open with" list.
if "nodera" not in text:
    launcher = text.find("<intent-filter>")
    close = text.find("</intent-filter>", launcher)
    if launcher != -1 and close != -1:
        end = close + len("</intent-filter>")
        indent = " " * (launcher - text.rfind("\n", 0, launcher) - 1)
        deep_link = (
            "\n"
            + indent + "<intent-filter android:autoVerify=\"false\">\n"
            + indent + "    <action android:name=\"android.intent.action.VIEW\" />\n"
            + indent + "    <category android:name=\"android.intent.category.DEFAULT\" />\n"
            + indent + "    <category android:name=\"android.intent.category.BROWSABLE\" />\n"
            + indent + "    <data android:scheme=\"nodera\" android:host=\"tracker-store\" />\n"
            + indent + "</intent-filter>"
        )
        text = text[:end] + deep_link + text[end:]

# `singleTask`, which the deep link now depends on.
#
# Tauri's plugin used to own the intent and could cope with a fresh task per link. The Compose
# activity reads `onNewIntent` instead, and without `singleTask` Android starts a SECOND activity
# for a link that arrives while the app is open — `onNewIntent` never fires on the instance the user
# is looking at, and the store offer is silently dropped.
if 'android:launchMode' not in text:
    text = re.sub(
        r'(<activity\b(?![^>]*android:launchMode)[^>]*android:name="[^"]*MainActivity")',
        r'\1\n        android:launchMode="singleTask"',
        text,
        count=1,
    )

# A Material 3 theme, because the activity is Compose now. Left on Tauri's generated theme, the
# window keeps an action bar the Compose scaffold then draws its own top bar underneath.
text = text.replace(
    'android:theme="@style/Theme.nodera_app"',
    'android:theme="@style/Theme.AppCompat.DayNight.NoActionBar"',
)

if text != original:
    open(path, "w").write(text)
PYEOF
  grep -q 'android:launchMode="singleTask"' "$MANIFEST" \
    || die "MainActivity is not singleTask — a warm-start nodera:// link would be dropped"
fi

# The tracker a development build comes up pointed at.
#
# `settings.rs` no longer ships anyone's LAN address as a default — a fresh install on another
# network could never reach it, and the phone reported "no trackers are answering" while looking
# like a broken app. Development builds still want the laptop that built them, so the address is
# BAKED IN HERE, from this host's own LAN address, and read by `option_env!` at compile time.
# Set NODERA_DEFAULT_TRACKERS yourself to override, or NODERA_DEFAULT_TRACKERS= (empty) to ship
# an APK with no tracker at all.
if [[ -z "${NODERA_DEFAULT_TRACKERS+x}" ]]; then
  if [[ "$REQUIRE_RELEASE_KEY" == "1" ]]; then
    # A RELEASE apk must never carry the address of the machine that built it. On a CI runner
    # `ip route get` answers with an ephemeral RFC1918 address — the first release build was about
    # to bake `tcp://10.1.0.148:25600` into every download, which on a stranger's phone is either
    # nothing at all or somebody else's device, and presents as "no trackers are answering" from an
    # app that looks broken.
    #
    # Tied to `--require-release-key` rather than to an environment variable somebody has to
    # remember, because "this is a release" is exactly what that flag already means.
    export NODERA_DEFAULT_TRACKERS=""
    say "trackers   release build: none baked in (the app uses its bundled service list)"
  else
    HOST_LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')"
    if [[ -n "$HOST_LAN_IP" ]]; then
      export NODERA_DEFAULT_TRACKERS="tcp://${HOST_LAN_IP}:25600"
      say "trackers   baking in this host: $NODERA_DEFAULT_TRACKERS"
    else
      say "trackers   no LAN address found; the apk will ship with no default tracker"
    fi
  fi
else
  say "trackers   using NODERA_DEFAULT_TRACKERS=${NODERA_DEFAULT_TRACKERS:-<none>}"
fi

# --- 2. build ------------------------------------------------------------
#
# Stale copied assets are removed first. Tauri copies bundle resources into
# the Gradle project and never removes them, so a resource that USED to be
# bundled stays in every later apk — which is how the Java worker's jars ended
# up in an Android build as ordinary (unrunnable) files.
rm -rf "$APP_DIR/gen/android/app/src/main/assets/resources"

cd "$APP_DIR"

BUILD_ARGS=(android build --apk --target "$TARGET")
[[ "$PROFILE" == "debug" ]] && BUILD_ARGS+=(--debug)
say "building…"
"${TAURI_CMD[@]}" "${BUILD_ARGS[@]}"

# Selected by its profile directory, not by a glob over every output. A glob would happily pick a
# stale debug apk left behind by an earlier run — which installs, launches, and is not the thing
# that was just built.
APK_IN="$(ls -1 "$APP_DIR/gen/android/app/build/outputs/apk/universal/$PROFILE/"*.apk 2>/dev/null | head -1)"
[[ -n "$APK_IN" ]] || die "the Gradle build produced no $PROFILE apk"
[[ "$(stat -c %s "$APK_IN")" -gt 1000000 ]] || die "the built apk is implausibly small — the build did not finish"
say "built      $(basename "$APK_IN") ($(numfmt --to=iec "$(stat -c %s "$APK_IN")"))"

# --- 3. the development key, if that is what we are using ----------------
#
# Only reachable when no release key was supplied AND --require-release-key was not passed;
# `resolve_signing_key` decided that at the top.
if [[ -z "$KEYSTORE_TMP" && ! -f "$KEYSTORE" ]]; then
  say "creating a development signing key at $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -dname "CN=NoderaMC Development, OU=NoderaMC, O=NoderaMC, C=BR" >/dev/null 2>&1
  chmod 600 "$KEYSTORE"
fi

# --- 4. align + sign -----------------------------------------------------
mkdir -p "$OUT_DIR"
ALIGNED="$(mktemp -t nodera-aligned-XXXXXX.apk)"
APK_OUT="$OUT_DIR/nodera-$PROFILE.apk"

say "aligning…"
"$TOOLS/zipalign" -p -f 4 "$APK_IN" "$ALIGNED"

say "signing…"
"$TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$APK_OUT" "$ALIGNED"

# Verified rather than assumed: an apk that fails verification installs on
# nothing, and finding that out on the phone wastes a whole round trip.
"$TOOLS/apksigner" verify --print-certs "$APK_OUT" >/dev/null \
  || die "the signed apk does not verify"

say "ready      $APK_OUT ($(numfmt --to=iec "$(stat -c %s "$APK_OUT")"))"

# --- 5. install ----------------------------------------------------------
if [[ "$DO_INSTALL" == "1" ]]; then
  command -v adb >/dev/null || die "adb is not on PATH"
  [[ -n "$(adb devices | sed -n '2p')" ]] || die "no device is connected (adb devices is empty)"
  say "installing…"
  # -r reinstalls over an existing copy, keeping the app's data — so a rebuild
  # does not wipe the device's peer identity, which would give it a new node id
  # and make every "is it the same device" observation meaningless.
  adb install -r "$APK_OUT"
  say "installed  dev.nodera.app"
fi
