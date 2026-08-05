#!/usr/bin/env python3
"""Prepare and launch an **unmodified** Minecraft client.

Why this exists
---------------

The LAN lane's whole claim is that two people can play together with *nothing installed into
Minecraft*. A harness that tested it with the NeoForge dev client would be testing the opposite:
`runClient` loads the mod, and the mod is exactly what this feature exists to do without. So the
only honest way to exercise it is to launch the real, unmodified game — which means doing what a
launcher does: read the version manifest, assemble a classpath, unpack natives, and run
`net.minecraft.client.main.Main`.

That is less exotic than it sounds. A version JSON names every file it needs with a URL and a
SHA-1, so "assemble a client" is a download-and-verify loop and a `-cp`.

What it will not do
-------------------

* **It never writes into your `~/.minecraft`.** Everything it creates lives under its own root
  (`~/.nodera/vanilla` by default). It will happily *read* your existing libraries and assets, since
  those are content-addressed and shared by every launcher — that is the difference between a 25 MB
  first run and a 1.2 GB one.
* **It does not authenticate.** The client is launched offline, the way every third-party launcher's
  offline mode does: a fixed name, a UUID derived from it, and a placeholder token. Singleplayer and
  Open-to-LAN work; multiplayer against an online-mode server does not, and is not what this is for.
* **It is not a launcher.** No profiles, no version selection UI, no mod loaders, no updates. If you
  want those, use a launcher.

Usage
-----

    vanilla-client.py prepare --version 1.21.1
    vanilla-client.py command --version 1.21.1 --game-dir run/lan/p1 --username LanHost

`command` prints the argv, one element per line, for a shell to read with `mapfile`. Printing the
argv rather than executing it keeps process supervision — logs, PIDs, teardown — in the shell that
already does that for every other service in the harness.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import urllib.request
import uuid
import zipfile
from pathlib import Path

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

# Where a launcher-installed Minecraft keeps its shared, content-addressed files. Read-only to us.
DEFAULT_MC_DIR = Path(os.environ.get("NODERA_MC_DIR", Path.home() / ".minecraft"))

# Ours. Never inside the user's install: a harness that writes into somebody's game directory is one
# bad path away from deleting a world.
DEFAULT_ROOT = Path(os.environ.get("NODERA_VANILLA_ROOT", Path.home() / ".nodera" / "vanilla"))


def log(message: str) -> None:
    print(f"[vanilla] {message}", file=sys.stderr)


def die(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"[vanilla] ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


# --------------------------------------------------------------------------- platform


def os_name() -> str:
    """The name Mojang's rules use for this platform."""
    system = platform.system()
    return {"Linux": "linux", "Darwin": "osx", "Windows": "windows"}.get(system, system.lower())


def wrong_arch(library_name: str) -> bool:
    """
    Whether a native library is for a different CPU than this one.

    Mojang's rules gate natives by **operating system only**, so a version JSON offers both the
    x86-64 and the ARM64 Linux natives to a Linux machine and lets the runtime sort it out. That is
    fine for a classpath and wrong for an extraction directory: unpacking both leaves two `.so` files
    with the same name racing for one `java.library.path`, and which one wins is the file order in a
    zip. So the architecture filter that the rules do not do happens here.
    """
    machine = platform.machine().lower()
    arm = machine in {"aarch64", "arm64"}
    name = library_name.lower()
    if "arm64" in name or "aarch_64" in name or "aarch64" in name:
        return not arm
    if "x86_64" in name or "x64" in name:
        return arm
    return False


def rules_allow(rules) -> bool:
    """Evaluate a Mojang `rules` block for this platform (features are treated as absent)."""
    if not rules:
        return True
    allowed = False
    for rule in rules:
        applies = True
        os_rule = rule.get("os")
        if os_rule:
            if "name" in os_rule and os_rule["name"] != os_name():
                applies = False
            if "arch" in os_rule and os_rule["arch"] != ("x86" if platform.machine() == "i386" else platform.machine()):
                applies = False
        if rule.get("features"):
            # Demo mode, custom resolution, quick-play — none of which this harness asks for.
            applies = False
        if applies:
            allowed = rule.get("action") == "allow"
    return allowed


# --------------------------------------------------------------------------- fetching


def sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(url: str, target: Path, expected_sha1: str | None = None) -> Path:
    """
    Download one file, unless a good copy is already there.

    The hash check is the point of doing this at all rather than shelling out to `curl`: every entry
    in a version manifest carries a SHA-1, and a half-written jar from an interrupted run is
    indistinguishable from a good one by size alone.
    """
    if target.exists() and (expected_sha1 is None or sha1(target) == expected_sha1):
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix(target.suffix + ".part")
    log(f"downloading {target.name}")
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temp.open("wb") as out:
            shutil.copyfileobj(response, out)
    except OSError as error:
        temp.unlink(missing_ok=True)
        die(f"could not download {url}: {error}")
    if expected_sha1 is not None and sha1(temp) != expected_sha1:
        temp.unlink(missing_ok=True)
        die(f"{target.name} downloaded but its checksum does not match — refusing to use it")
    temp.replace(target)
    return target


def version_json(version: str, root: Path, mc_dir: Path) -> dict:
    """
    The version manifest, preferring one an installed launcher already fetched.

    Reusing it is not just a saved download: it is the *same* JSON, so this harness and the user's
    launcher agree about what "1.21.1" means.
    """
    local = root / "versions" / version / f"{version}.json"
    if local.is_file():
        return json.loads(local.read_text())

    installed = mc_dir / "versions" / version / f"{version}.json"
    if installed.is_file():
        local.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(installed, local)
        log(f"using the version manifest already installed at {installed}")
        return json.loads(local.read_text())

    log(f"looking up {version} in Mojang's version manifest")
    try:
        with urllib.request.urlopen(MANIFEST_URL, timeout=30) as response:
            manifest = json.load(response)
    except OSError as error:
        die(f"no local copy of {version} and the manifest could not be fetched: {error}")
    entry = next((v for v in manifest["versions"] if v["id"] == version), None)
    if entry is None:
        die(f"Minecraft {version} is not in Mojang's version manifest")
    fetch(entry["url"], local, entry.get("sha1"))
    return json.loads(local.read_text())


# --------------------------------------------------------------------------- assembly


def maven_path(name: str) -> str:
    """
    The repository path a Maven coordinate lives at: `group:artifact:version[:classifier][@ext]`.

    Needed because `path` is **optional** in a version manifest and several launchers rewrite the
    file without it. Mojang's own copy has it; the one sitting in an installed `.minecraft` may not,
    and a resolver that only reads `path` finds zero libraries in a perfectly good install — which
    is exactly the silent, confusing failure this reconstruction avoids.
    """
    coordinate, _, extension = name.partition("@")
    extension = extension or "jar"
    parts = coordinate.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{classifier}.{extension}"


def library_path(library: dict) -> str | None:
    artifact = library.get("downloads", {}).get("artifact")
    if not artifact:
        return None
    path = artifact.get("path")
    if path:
        return path
    name = library.get("name")
    return maven_path(name) if name else None


def resolve_libraries(manifest: dict, root: Path, mc_dir: Path) -> tuple[list[Path], list[Path]]:
    """
    Every jar this version needs, and the subset that carries native code.

    A library already present in the user's install is used from there. They are content-addressed
    under `libraries/<group>/<artifact>/<version>/…`, so the copy in a launcher's directory is the
    same bytes as the copy we would download — and 327 MB of them is not worth fetching twice.
    """
    classpath: list[Path] = []
    natives: list[Path] = []
    for library in manifest.get("libraries", []):
        if not rules_allow(library.get("rules")):
            continue
        path = library_path(library)
        if path is None:
            continue
        name = library.get("name", "")
        is_native = ":natives-" in name or "natives-" in path
        if is_native and wrong_arch(name):
            continue

        artifact = library["downloads"]["artifact"]
        shared = mc_dir / "libraries" / path
        if shared.is_file():
            target = shared
        else:
            target = fetch(artifact["url"], root / "libraries" / path, artifact.get("sha1"))
        classpath.append(target)
        if is_native:
            natives.append(target)
    return classpath, natives


def extract_natives(natives: list[Path], into: Path) -> Path:
    """
    Unpack native libraries where the JVM can find them.

    LWJGL 3 can load its own natives straight off the classpath, so this is belt and braces — but it
    is cheap, and the failure it prevents (a black window and an `UnsatisfiedLinkError` deep in a
    stack trace) costs an hour to diagnose.
    """
    into.mkdir(parents=True, exist_ok=True)
    for jar in natives:
        try:
            with zipfile.ZipFile(jar) as archive:
                for entry in archive.namelist():
                    if entry.endswith((".so", ".dylib", ".dll")):
                        # Flattened: the JVM's library path is a directory list, not a tree.
                        destination = into / Path(entry).name
                        if not destination.exists():
                            with archive.open(entry) as source, destination.open("wb") as out:
                                shutil.copyfileobj(source, out)
        except zipfile.BadZipFile:
            log(f"skipping {jar.name}: not a readable archive")
    return into


def resolve_assets(manifest: dict, root: Path, mc_dir: Path) -> tuple[Path, str]:
    """
    Where the game's assets live, and which index describes them.

    The objects themselves are over a gigabyte and are **not** downloaded: an installed launcher
    almost certainly has them, and a client with missing assets still starts, still hosts a LAN
    world, and still lets somebody join it — it just looks wrong and has no sound. For a test of the
    networking lane that is an acceptable trade, and it is reported rather than hidden.
    """
    index = manifest["assetIndex"]
    shared = mc_dir / "assets"
    if (shared / "objects").is_dir() and any((shared / "indexes").glob("*.json")):
        assets = shared
    else:
        assets = root / "assets"
        log("no installed asset store found — the game will run with missing textures and no sound")
    fetch(index["url"], assets / "indexes" / f"{index['id']}.json", index.get("sha1"))
    return assets, index["id"]


def pick_java(manifest: dict) -> str:
    """
    A JVM of the major version this Minecraft asks for.

    Not a preference. A client run on a JVM too old refuses to start with a class-file error, and one
    run on a JVM far newer trips on removed internals in ways that surface as unrelated crashes. The
    version JSON states what it wants, so honour it — and say so plainly when nothing on the machine
    matches, rather than launching something that will fail confusingly.
    """
    wanted = int(manifest.get("javaVersion", {}).get("majorVersion", 21))
    override = os.environ.get("NODERA_MC_JAVA")
    if override:
        return override

    candidates: list[Path] = []
    home = os.environ.get("JAVA_HOME")
    if home:
        candidates.append(Path(home) / "bin" / "java")
    for pattern in (
        "/usr/lib/jvm/*/bin/java",
        str(Path.home() / ".gradle/jdks/*/bin/java"),
        str(Path.home() / ".gradle/jdks/*/*/bin/java"),
        str(Path.home() / ".sdkman/candidates/java/*/bin/java"),
        str(Path.home() / ".vscode/extensions/*/jre/*/bin/java"),
        "/usr/libexec/java_home",
    ):
        candidates.extend(sorted(Path("/").glob(pattern.lstrip("/"))))
    found = shutil.which("java")
    if found:
        candidates.append(Path(found))

    fallback: str | None = None
    for candidate in candidates:
        if not candidate.is_file() or not os.access(candidate, os.X_OK):
            continue
        try:
            output = subprocess.run(
                [str(candidate), "-version"], capture_output=True, text=True, timeout=20
            ).stderr
        except (OSError, subprocess.SubprocessError):
            continue
        # `openjdk version "21.0.11"` / `"1.8.0_412"` — the major is the first or second field.
        digits = "".join(c if c.isdigit() else " " for c in output.split("\n")[0]).split()
        if not digits:
            continue
        major = int(digits[0]) if int(digits[0]) != 1 else int(digits[1])
        if major == wanted:
            return str(candidate)
        fallback = fallback or str(candidate)

    if fallback:
        log(f"no Java {wanted} found; using {fallback}, which Minecraft {manifest['id']} may reject")
        log("set NODERA_MC_JAVA to a Java %d if the client fails to start" % wanted)
        return fallback
    die(f"no Java runtime found (Minecraft {manifest['id']} needs Java {wanted})")


def offline_uuid(username: str) -> str:
    """
    The UUID an offline player gets, by the same rule the server uses.

    `OfflinePlayer:<name>`, MD5, version 3. Deriving it rather than randomising means the same test
    player keeps the same inventory and position between runs — which matters when the thing you are
    testing is two named players meeting each other.

    MD5 is not a choice here and cannot be substituted: a version-3 UUID *is* an MD5 UUID by
    definition (RFC 4122 §4.3), and this has to produce the byte-for-byte identifier the Minecraft
    server derives for the same name. A stronger hash would produce a different player. Hence
    `usedforsecurity=False` — this is an identifier derivation, not a security primitive, and saying
    so is more honest than suppressing the finding.
    """
    digest = bytearray(
        hashlib.md5(f"OfflinePlayer:{username}".encode(), usedforsecurity=False).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


# --------------------------------------------------------------------------- commands


def prepare(version: str, root: Path, mc_dir: Path) -> dict:
    manifest = version_json(version, root, mc_dir)
    client = fetch(
        manifest["downloads"]["client"]["url"],
        root / "versions" / version / f"{version}.jar",
        manifest["downloads"]["client"].get("sha1"),
    )
    classpath, natives = resolve_libraries(manifest, root, mc_dir)
    natives_dir = extract_natives(natives, root / "natives" / version)
    assets_dir, assets_index = resolve_assets(manifest, root, mc_dir)
    return {
        "manifest": manifest,
        "client": client,
        "classpath": classpath,
        "natives_dir": natives_dir,
        "assets_dir": assets_dir,
        "assets_index": assets_index,
    }


def build_command(prepared: dict, game_dir: Path, username: str, memory: str) -> list[str]:
    manifest = prepared["manifest"]
    game_dir.mkdir(parents=True, exist_ok=True)
    classpath = [str(path) for path in prepared["classpath"]] + [str(prepared["client"])]
    command = [
        pick_java(manifest),
        f"-Xmx{memory}",
        f"-Djava.library.path={prepared['natives_dir']}",
        "-Djava.net.preferIPv4Stack=true",
        # The window title is how you tell two clients apart on one screen, and both of them are
        # going to be called "Minecraft 1.21.1" otherwise.
        f"-Dnodera.player={username}",
    ]
    if os_name() == "osx":
        command.append("-XstartOnFirstThread")
    command += [
        "-cp",
        os.pathsep.join(classpath),
        manifest["mainClass"],
        "--username", username,
        "--version", manifest["id"],
        "--gameDir", str(game_dir),
        "--assetsDir", str(prepared["assets_dir"]),
        "--assetIndex", prepared["assets_index"],
        "--uuid", offline_uuid(username).replace("-", ""),
        "--accessToken", "0",
        "--userType", "legacy",
        "--versionType", "release",
    ]
    return command


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "command"))
    parser.add_argument("--version", default=os.environ.get("NODERA_MC_VERSION", "1.21.1"))
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--mc-dir", type=Path, default=DEFAULT_MC_DIR)
    parser.add_argument("--game-dir", type=Path)
    parser.add_argument("--username", default="Player")
    parser.add_argument("--memory", default=os.environ.get("NODERA_MC_MEMORY", "2G"))
    args = parser.parse_args()

    prepared = prepare(args.version, args.root, args.mc_dir)
    if args.action == "prepare":
        log(f"Minecraft {args.version} ready under {args.root}")
        log(f"  client      {prepared['client']}")
        log(f"  classpath   {len(prepared['classpath'])} libraries")
        log(f"  natives     {prepared['natives_dir']}")
        log(f"  assets      {prepared['assets_dir']} (index {prepared['assets_index']})")
        return 0

    if args.game_dir is None:
        die("command needs --game-dir")
    for element in build_command(prepared, args.game_dir, args.username, args.memory):
        print(element)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
