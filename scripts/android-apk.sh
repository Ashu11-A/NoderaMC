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
#
# THE SIGNING KEY IS A DEVELOPMENT KEY. It lives outside the repository
# (~/.nodera/android-release.jks), it is generated on first run, and it is
# not a Play Store upload key. It exists so the apk installs, not so it can
# be published; shipping to a store needs a key you have deliberately backed
# up, which is a decision for a person and not for a build script.
# ===========================================================================
set -euo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$NODERA_ROOT/rust/nodera-app"
OUT_DIR="$NODERA_ROOT/build"

# Pinned to match scripts/android-toolchain.sh. A build that follows "latest"
# produces a different artifact every month.
NDK_VERSION="26.1.10909125"
BUILD_TOOLS="34.0.0"
# Dexing needs a NEWER d8 than signing needs: Java 21 class files are only
# readable by R8 8.3+, which ships in build-tools 35.
DEX_BUILD_TOOLS="35.0.0"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
KEYSTORE="${NODERA_ANDROID_KEYSTORE:-$HOME/.nodera/android-release.jks}"
KEY_ALIAS="${NODERA_ANDROID_KEY_ALIAS:-nodera}"
KEY_PASS="${NODERA_ANDROID_KEY_PASS:-noderadev}"

PROFILE="release"
DO_INSTALL=0
# Re-dexing the worker costs a minute and a lot of memory. It only has to happen when the worker
# changed, so a UI-only rebuild can skip it and reuse the staged asset.
SKIP_WORKER=0
TARGET="${NODERA_ANDROID_TARGET:-aarch64}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)   PROFILE="debug" ;;
    --skip-worker) SKIP_WORKER=1 ;;
    --install) DO_INSTALL=1 ;;
    --target)  TARGET="$2"; shift ;;
    -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '\033[1;36m[apk]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[apk]\033[0m %s\n' "$*" >&2; exit 1; }

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

# --- 1. the Java worker, as dex ------------------------------------------
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
( cd "$NODERA_ROOT" && ./gradlew :worker:installDist -q )
WORKER_DIST="$NODERA_ROOT/java/worker/build/install/nodera-headless"
[[ -d "$WORKER_DIST/lib" ]] || die "the worker distribution is missing at $WORKER_DIST"

STAGE="$(mktemp -d -t nodera-worker-dex-XXXXXX)"
ALIGNED=""
# One trap for both temporaries: a second `trap ... EXIT` later would silently replace this one and
# leak a few hundred megabytes of staged dex per run.
trap 'rm -rf "$STAGE"; [[ -n "$ALIGNED" ]] && rm -f "$ALIGNED"' EXIT
mkdir -p "$STAGE/dex" "$STAGE/payload"

# --min-api 26 matches the app's own minSdk. It is safe ONLY because no Java 21
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
"$DEX_TOOLS/d8" --min-api 26 --output "$STAGE/dex" "$WORKER_DIST"/lib/*.jar

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
mkdir -p "$KOTLIN_DST"
cp "$APP_DIR/android/kotlin/"*.kt "$KOTLIN_DST/"

# androidx.documentfile: needed to read the NAME of a folder chosen through the Storage Access
# Framework. Added here because Tauri regenerates this Gradle file.
GRADLE_APP="$APP_DIR/gen/android/app/build.gradle.kts"
if [[ -f "$GRADLE_APP" ]] && ! grep -q "androidx.documentfile" "$GRADLE_APP"; then
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

# Keep the classes the RUST side calls by name. R8 cannot see a reflective JNI call, so it renamed
# `NoderaStorage.pick` to `a` and the folder picker failed with:
#
#   java.lang.NoSuchMethodError: no static method "Ldev/nodera/app/NoderaStorage;.pick()V"
#
# Only these three types are reachable from native code; everything else stays minifiable.
PROGUARD="$APP_DIR/gen/android/app/proguard-rules.pro"
if [[ -f "$PROGUARD" ]] && ! grep -q "dev.nodera.app.NoderaStorage" "$PROGUARD"; then
  say "proguard   keeping the JNI entry points"
  cat >> "$PROGUARD" <<'PROEOF'

# Called from Rust over JNI — invisible to R8's reachability analysis.
-keep class dev.nodera.app.NoderaStorage { *; }
-keep class dev.nodera.app.NoderaWorker { *; }
-keep class dev.nodera.app.NoderaBridge { *; }
PROEOF
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

if text != original:
    open(path, "w").write(text)
PYEOF
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
  HOST_LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')"
  if [[ -n "$HOST_LAN_IP" ]]; then
    export NODERA_DEFAULT_TRACKERS="tcp://${HOST_LAN_IP}:25600"
    say "trackers   baking in this host: $NODERA_DEFAULT_TRACKERS"
  else
    say "trackers   no LAN address found; the apk will ship with no default tracker"
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
cargo tauri "${BUILD_ARGS[@]}"

# Selected by its profile directory, not by a glob over every output. A glob would happily pick a
# stale debug apk left behind by an earlier run — which installs, launches, and is not the thing
# that was just built.
APK_IN="$(ls -1 "$APP_DIR/gen/android/app/build/outputs/apk/universal/$PROFILE/"*.apk 2>/dev/null | head -1)"
[[ -n "$APK_IN" ]] || die "the Gradle build produced no $PROFILE apk"
[[ "$(stat -c %s "$APK_IN")" -gt 1000000 ]] || die "the built apk is implausibly small — the build did not finish"
say "built      $(basename "$APK_IN") ($(numfmt --to=iec "$(stat -c %s "$APK_IN")"))"

# --- 3. the signing key --------------------------------------------------
if [[ ! -f "$KEYSTORE" ]]; then
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
