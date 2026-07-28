#!/usr/bin/env python3
"""Read and validate a Nodera service index.

The one place that turns `services/official.json` into something else: a pass/fail for CI, and a
comma-separated endpoint list for the e2e launcher and for anyone exporting `NODERA_*_ENDPOINTS`.

Validation is driven by `services/index.schema.json` rather than by rules restated here, because a
schema published for third parties and a validator that disagrees with it is worse than no schema at
all. Only the subset of JSON Schema the file actually uses is implemented, and an unknown keyword is
an error rather than something quietly skipped — the failure mode of a partial validator is a file
that passes CI and breaks a client.

    scripts/services.py --validate
    scripts/services.py --endpoints tracker
    scripts/services.py --endpoints rendezvous --index some/other/index.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INDEX = ROOT / "services" / "official.json"
DEFAULT_SCHEMA = ROOT / "services" / "index.schema.json"

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
    parser.add_argument("--index", type=Path, default=DEFAULT_INDEX)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    parser.add_argument("--validate", action="store_true", help="validate and report")
    parser.add_argument("--endpoints", choices=["tracker", "rendezvous"],
                        help="print a comma-separated endpoint list for that kind")
    args = parser.parse_args()

    try:
        index = load(args.index, args.schema)
    except Invalid as e:
        print(f"services: {e}", file=sys.stderr)
        return 1

    if args.endpoints:
        found = endpoints(index, args.endpoints)
        if not found:
            print(f"services: {args.index} lists no {args.endpoints}", file=sys.stderr)
            return 1
        print(",".join(found))
        return 0

    trackers = len(endpoints(index, "tracker"))
    relays = len(endpoints(index, "rendezvous"))
    print(f"services: {args.index.name} is valid — "
          f"{len(index['services'])} service(s), {trackers} tracker endpoint(s), "
          f"{relays} rendezvous endpoint(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
