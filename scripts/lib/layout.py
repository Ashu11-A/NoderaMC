"""The repository layout table, for Python.

Where a module or a crate lives is a property of the tree, not of any one toolchain, so it is
written down once — in ``/layout.properties`` — and read by Gradle, the Java harness, the structural
report, the shell scripts and the Rust tests. Before that file existed the same table lived in five
places and two of them were already stale.

The Java counterpart is ``dev.nodera.testkit.harness.LayoutManifest``; the Rust one is
``nodera_codec::repo``; the shell one is ``scripts/lib/layout.sh``.

Usage::

    import sys, pathlib
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "lib"))
    import layout

    layout.root()                      # the repository root
    layout.get("module.peer")          # the value, relative to the root
    layout.path("module.peer")         # the same value, absolute
    layout.modules()                   # {"core": Path(...), ...}
"""

from __future__ import annotations

import functools
import pathlib

_MANIFEST = "layout.properties"


@functools.lru_cache(maxsize=1)
def root(start: str | None = None) -> pathlib.Path:
    """The repository root: the directory holding both VERSION and settings.gradle.kts.

    Both markers are required so a stray VERSION in a subdirectory cannot end the walk early.
    Walking up rather than counting ``..`` segments is the point — it is what lets a script change
    depth without every path it computes going quietly wrong.
    """
    begin = pathlib.Path(start) if start else pathlib.Path(__file__).resolve()
    for candidate in [begin, *begin.parents]:
        if (candidate / "VERSION").is_file() and (candidate / "settings.gradle.kts").is_file():
            return candidate
    raise RuntimeError(
        f"no repository root (VERSION + settings.gradle.kts) above {begin}"
    )


@functools.lru_cache(maxsize=1)
def _values() -> dict[str, str]:
    """Parse the manifest.

    A deliberately small subset of ``java.util.Properties``: ``key = value``, ``#`` comments, blank
    lines. No escapes, no continuations, no interpolation — that restriction is exactly what lets
    five languages each parse the file in ten lines instead of agreeing on a library.
    """
    manifest = root() / _MANIFEST
    entries: dict[str, str] = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        key, sep, value = line.partition("=")
        if sep:
            entries[key.strip()] = value.strip()
    if not entries:
        raise RuntimeError(f"{manifest} parsed to nothing")
    return entries


def get(key: str) -> str:
    """One value from the manifest, relative to the repository root."""
    try:
        return _values()[key]
    except KeyError:
        raise KeyError(
            f"{root() / _MANIFEST} has no key '{key}' "
            f"(keys: {sorted(_values())})"
        ) from None


def path(key: str) -> pathlib.Path:
    """One value from the manifest, as an absolute path."""
    return root() / get(key)


def module(name: str) -> pathlib.Path:
    """The directory of one Gradle module, by its project name."""
    return path(f"module.{name}")


def crate(name: str) -> pathlib.Path:
    """The directory of one Cargo crate, by its package name."""
    return path(f"crate.{name}")


def directory(name: str) -> pathlib.Path:
    """A well-known directory that is neither a module nor a crate."""
    return path(f"dir.{name}")


def modules() -> dict[str, pathlib.Path]:
    """Every Gradle module, project name to absolute directory."""
    return _by_prefix("module.")


def crates() -> dict[str, pathlib.Path]:
    """Every Cargo crate, package name to absolute directory."""
    return _by_prefix("crate.")


def workspace_crates() -> dict[str, pathlib.Path]:
    """Every crate in the cargo workspace — that is, every crate except the excluded app."""
    return {name: p for name, p in crates().items() if name != "nodera-app"}


def _by_prefix(prefix: str) -> dict[str, pathlib.Path]:
    base = root()
    return {
        key[len(prefix):]: base / value
        for key, value in sorted(_values().items())
        if key.startswith(prefix)
    }
