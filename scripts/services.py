#!/usr/bin/env python3
"""Read and validate a Nodera service index.

The one place that turns the published service index into something else: a pass/fail for CI, a
comma-separated endpoint list for the e2e launcher and for anyone exporting `NODERA_*_ENDPOINTS`,
and a resolved local path for any other consumer that needs the file itself.

The index does not live in this tree. It lives on the orphan `services` branch — see
`/layout.properties`, which is where the branch name, the file names and the raw URL are written
down, and `git show services:README.md` for what that branch is. This script resolves it the same
way `:core`'s `generateOfficialServices` task and the companion app's `build.rs` do, because three
resolvers that disagree about which list a build compiled in is a defect nobody can see:

    1. `git show <branch>:<index>`           — the local ref.
    2. `git show <remote>/<branch>:<index>`  — the fetched remote ref.
    3. the raw URL                           — if this host has a network.
    4. the cache under `dir.services`        — whatever a previous build resolved.
    5. otherwise: fail, naming the fetch that would fix it.

Every success rewrites the cache, so one `git fetch origin services:services` is enough and every
later run is offline.

Validation is driven by the branch's own `index.schema.json` rather than by rules restated here,
because a schema published for third parties and a validator that disagrees with it is worse than no
schema at all. Only the subset of JSON Schema the file actually uses is implemented, and an unknown
keyword is an error rather than something quietly skipped — the failure mode of a partial validator
is a file that passes CI and breaks a client.

    scripts/services.py --validate
    scripts/services.py --endpoints tracker
    scripts/services.py --resolve                       # print the resolved index path
    scripts/services.py --endpoints rendezvous --index some/other/index.json
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
import layout  # noqa: E402  (path is set immediately above)

ROOT = layout.root()

# How long to wait on the raw URL before giving up and trying the cache. A build must not hang on a
# network that answers slowly; the ref and the cache are the paths that are meant to be fast.
FETCH_TIMEOUT_SECONDS = 15

# Keywords this validator understands. Anything else in the schema means the schema grew a feature
# the validator does not enforce, which must fail loudly rather than silently pass everything.
KNOWN = {
    "$schema", "$id", "$defs", "$ref", "title", "description", "const", "enum",
    "type", "properties", "required", "additionalProperties", "items",
    "minItems", "maxItems", "minLength", "maxLength", "pattern", "format",
}

TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "integer": int,
    "number": (int, float),
    "boolean": bool,
}


class Invalid(Exception):
    pass


class Unresolved(Exception):
    """The index could not be obtained from any source."""


def _cache_dir() -> Path:
    return ROOT / layout.get("dir.services")


def _git_show(ref: str, name: str) -> str | None:
    """One file out of a git ref, or None if the ref or the file is not there.

    `git show <ref>:<file>` and not a checkout: resolving the branch must never touch the working
    tree, which on `main` is somebody's uncommitted work.
    """
    try:
        result = subprocess.run(
            ["git", "-C", str(ROOT), "show", f"{ref}:{name}"],
            capture_output=True, text=True, check=False,
        )
    except OSError:
        return None
    return result.stdout if result.returncode == 0 else None


def _git_fetch(remote: str, branch: str) -> bool:
    """Fetch the branch into FETCH_HEAD, shallowly.

    Only reached when the ref is absent, which is what a `git clone --depth 1` of `main` and every
    `actions/checkout` look like. Without this step every one of those falls through to the raw URL,
    and a build that could have read the branch reads a web server instead. Shallow because one
    commit of a four-file branch is all anybody wants.
    """
    try:
        result = subprocess.run(
            ["git", "-C", str(ROOT), "fetch", "--depth", "1", "--quiet", remote, branch],
            capture_output=True, text=True, check=False, timeout=FETCH_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


def _http_get(url: str) -> str | None:
    try:
        with urllib.request.urlopen(url, timeout=FETCH_TIMEOUT_SECONDS) as response:
            return response.read().decode("utf-8")
    except (urllib.error.URLError, OSError, UnicodeDecodeError):
        return None


def check_manifest() -> None:
    """The published URL must be the base plus the minified file name.

    Two spellings of one address: `services.rawUrl`, which `stores.rs` needs as a compile-time
    constant and a shipped client refreshes from, and the base every resolver composes from. Drift
    between them breaks nothing at build time — it ships a built-in store pointing at a file nobody
    publishes, and the only symptom is a row that never refreshes.
    """
    composed = layout.get("services.rawBase") + layout.get("services.minifiedIndex")
    declared = layout.get("services.rawUrl")
    if composed != declared:
        raise Invalid(
            f"layout.properties: services.rawUrl is {declared!r} but services.rawBase + "
            f"services.minifiedIndex is {composed!r}"
        )


def resolve(name_key: str) -> Path:
    """Resolve one file from the published index branch to a local path.

    Writes what it found into the cache under `dir.services` and returns that path, so every caller
    downstream of this — the validator, a shell script, a human — is looking at a real file rather
    than at a string that came from somewhere unnamed.

    :param name_key: the `layout.properties` key naming the file on the branch.
    """
    name = layout.get(name_key)
    branch = layout.get("services.branch")
    remote = layout.get("services.remote")
    cached = _cache_dir() / name

    body = _git_show(branch, name) or _git_show(f"{remote}/{branch}", name)
    if body is None and _git_fetch(remote, branch):
        body = _git_show("FETCH_HEAD", name)
    if body is None:
        body = _http_get(layout.get("services.rawBase") + name)
    if body is not None:
        cached.parent.mkdir(parents=True, exist_ok=True)
        cached.write_text(body, encoding="utf-8")
        return cached
    if cached.is_file():
        return cached
    raise Unresolved(
        f"cannot resolve {name} from the '{branch}' branch: no local ref, no {remote}/{branch}, "
        f"no network and no cache at {cached}. Run:\n"
        f"    git fetch {remote} {branch}:{branch}"
    )


def check(schema: dict, value, path: str, root: dict) -> None:
    unknown = set(schema) - KNOWN
    if unknown:
        raise Invalid(f"schema at {path} uses unsupported keywords: {sorted(unknown)}")

    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/$defs/"):
            raise Invalid(f"schema at {path} uses an unsupported $ref: {ref}")
        check(root["$defs"][ref.removeprefix("#/$defs/")], value, path, root)
        return

    if "type" in schema:
        expected = TYPES[schema["type"]]
        # bool is a subclass of int in Python; an integer field must not accept `true`.
        if isinstance(value, bool) and schema["type"] in ("integer", "number"):
            raise Invalid(f"{path}: expected {schema['type']}, got boolean")
        if not isinstance(value, expected):
            raise Invalid(f"{path}: expected {schema['type']}, got {type(value).__name__}")

    if "const" in schema and value != schema["const"]:
        raise Invalid(f"{path}: must be {schema['const']!r}, got {value!r}")
    if "enum" in schema and value not in schema["enum"]:
        raise Invalid(f"{path}: must be one of {schema['enum']}, got {value!r}")

    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            raise Invalid(f"{path}: shorter than {schema['minLength']} characters")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            raise Invalid(f"{path}: longer than {schema['maxLength']} characters")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            raise Invalid(f"{path}: {value!r} does not match {schema['pattern']}")

    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            raise Invalid(f"{path}: needs at least {schema['minItems']} item(s)")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            raise Invalid(f"{path}: at most {schema['maxItems']} item(s)")
        if "items" in schema:
            for index, item in enumerate(value):
                check(schema["items"], item, f"{path}[{index}]", root)

    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                raise Invalid(f"{path}: missing required key {key!r}")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            extra = set(value) - set(properties)
            if extra:
                raise Invalid(f"{path}: unrecognised key(s) {sorted(extra)}")
        for key, item in value.items():
            if key in properties:
                check(properties[key], item, f"{path}.{key}", root)


def load(index_path: Path, schema_path: Path) -> dict:
    try:
        index = json.loads(index_path.read_text())
    except json.JSONDecodeError as e:
        raise Invalid(f"{index_path}: not valid JSON — {e}") from e
    schema = json.loads(schema_path.read_text())
    check(schema, index, "$", schema)

    # Beyond the schema: two services of the same kind may not claim the same endpoint. The schema
    # cannot express it, and the failure it prevents is a directory where a peer's two "different"
    # relays are one host — redundancy that is not.
    seen: dict[tuple[str, str], str] = {}
    for service in index["services"]:
        for endpoint in service["endpoints"]:
            key = (service["kind"], endpoint)
            if key in seen:
                raise Invalid(
                    f"{endpoint} is listed by both {seen[key]!r} and {service['name']!r} "
                    f"as a {service['kind']}"
                )
            seen[key] = service["name"]
    return index


def endpoints(index: dict, kind: str) -> list[str]:
    """Every endpoint of one kind, in index order.

    Order is the file's, not a ranking. A peer measures these itself and reorders them; nothing here
    is a promise about which one is fastest, and code that treats position as quality is wrong.
    """
    return [e for s in index["services"] if s["kind"] == kind for e in s["endpoints"]]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", type=Path, default=None,
                        help="a local index file; default: resolve it from the services branch")
    parser.add_argument("--schema", type=Path, default=None,
                        help="a local schema file; default: resolve it from the services branch")
    parser.add_argument("--validate", action="store_true", help="validate and report")
    parser.add_argument("--endpoints", choices=["tracker", "rendezvous"],
                        help="print a comma-separated endpoint list for that kind")
    parser.add_argument("--resolve", action="store_true",
                        help="print the resolved index path and exit, without validating")
    parser.add_argument("--minified", action="store_true",
                        help="with --resolve, resolve the minified index clients fetch")
    parser.add_argument("--minify", action="store_true",
                        help="print the index minified, the form clients fetch, and exit")
    args = parser.parse_args()

    # A caller may hand this script a file — a third-party store under review, or a contributor's
    # working copy of the branch. Only the default reaches for the branch.
    try:
        check_manifest()
        if args.resolve:
            print(resolve("services.minifiedIndex" if args.minified else "services.index"))
            return 0
        index_path = args.index or resolve("services.index")
        # A schema beside the index wins over the published one. That is how a contributor validates
        # a working copy of the branch without a second flag, and how a `schema_version: 2` list is
        # checked against the schema it was actually written for.
        beside = index_path.parent / layout.get("services.schema")
        schema_path = args.schema or (beside if args.index and beside.is_file()
                                      else resolve("services.schema"))
    except (Unresolved, Invalid) as e:
        print(f"services: {e}", file=sys.stderr)
        return 1

    try:
        index = load(index_path, schema_path)
    except Invalid as e:
        print(f"services: {e}", file=sys.stderr)
        return 1

    if args.minify:
        # The one definition of "minified" — `index.min.json` on the branch is what clients fetch,
        # and CI compares it against this. Two people minifying with two tools produce two files
        # that differ in whitespace and agree in meaning, and the diff is unreadable noise forever.
        print(json.dumps(index, separators=(",", ":"), ensure_ascii=False))
        return 0

    if args.endpoints:
        found = endpoints(index, args.endpoints)
        if not found:
            print(f"services: {index_path} lists no {args.endpoints}", file=sys.stderr)
            return 1
        print(",".join(found))
        return 0

    trackers = len(endpoints(index, "tracker"))
    relays = len(endpoints(index, "rendezvous"))
    print(f"services: {index_path.name} is valid — "
          f"{len(index['services'])} service(s), {trackers} tracker endpoint(s), "
          f"{relays} rendezvous endpoint(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
