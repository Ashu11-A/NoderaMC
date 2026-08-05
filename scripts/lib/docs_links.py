"""What a link in a `docs/` markdown file means.

WHY THIS FILE EXISTS
--------------------
`docs/` is around a thousand files that cross-reference each other by relative path, and a category
that gets renamed takes about a hundred links with it. `scripts/check-docs.sh` has resolved them
since the worker→peer rename, and it did so in a Python heredoc inside the script.

The website now has to resolve the same links a second time, for a different purpose: nine documents
are mirrored onto noderamc.org, and every relative link inside them has to become either a site route
or a GitHub URL. Two resolvers eventually disagree about what a link means, and the one that
disagrees silently is the website's — a link that quietly renders as text, or as a URL pointing at a
file that moved, looks exactly like a link that works.

So the rule lives here, once, and both read it.

USAGE
-----
    import sys; sys.path.insert(0, "scripts/lib")
    from docs_links import links_in, resolve

    for link in links_in(text):            # every [text](target) that is a path into this repository
        target = resolve(markdown_file, link.target)
"""

from __future__ import annotations

import pathlib
import re
import sys
from dataclasses import dataclass

# `[text](target)`. Deliberately not a markdown parser: the corpus is hand-written prose whose links
# are all on one line, and a parser here would be a dependency `check-docs.sh` cannot take (it runs
# from a heredoc with nothing installed).
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")

# The three schemes that are never a path into this tree. A bare `#anchor` is an in-page link.
EXTERNAL = re.compile(r"^(https?:|mailto:|#)")


@dataclass(frozen=True)
class Link:
    """One `[text](target)` occurrence: its raw target, split into a path and an anchor."""

    target: str
    path: str
    anchor: str


def links_in(text: str):
    """Every link in `text` that is a path into this repository.

    External links, `mailto:` and pure in-page anchors are dropped, because none of them can be
    stale in the way this module is about. A target with a title — `](file.md "Title")` — keeps only
    the path, which is what markdown itself does with it.
    """
    for raw in LINK.findall(text):
        target = raw.split(" ", 1)[0].strip()
        if not target or EXTERNAL.match(target):
            continue
        path, _, anchor = target.partition("#")
        if not path:
            continue
        yield Link(target=target, path=path, anchor=f"#{anchor}" if anchor else "")


def resolve(markdown_file: pathlib.Path, path: str) -> pathlib.Path:
    """Where a relative link points, as an absolute path.

    Relative to the linking file's own directory, which is what every markdown renderer does and
    what a reader clicking the link in a repository browser gets.
    """
    return (pathlib.Path(markdown_file).parent / path).resolve()


def submodule_roots(docs: pathlib.Path) -> tuple[pathlib.Path, ...]:
    """Directories whose links belong to another project.

    `docs/minecraft/upstream/*` are git SUBMODULES. Whether they are on disk is a fact about how the
    repository was cloned, not about the documentation: they are present locally and absent on a CI
    runner that does not recurse. Checking them makes a gate environment-dependent, which is the one
    thing a gate must not be.
    """
    return ((pathlib.Path(docs) / "minecraft" / "upstream").resolve(),)


def is_submodule(resolved: pathlib.Path, roots) -> bool:
    """True when a resolved target lives inside one of the submodule roots."""
    return any(root in resolved.parents or resolved == root for root in roots)


def _repository_root(start: pathlib.Path) -> pathlib.Path:
    """The directory holding both `VERSION` and `settings.gradle.kts`."""
    directory = start.resolve()
    while True:
        if (directory / "VERSION").is_file() and (directory / "settings.gradle.kts").is_file():
            return directory
        if directory.parent == directory:
            raise SystemExit("docs_links: no repository root above " + str(start))
        directory = directory.parent


def main(argv) -> int:
    """`python3 scripts/lib/docs_links.py <markdown-file> [--base <dir>]` → JSON, one row per link.

    The website's mirror runs under Node and has to answer the identical question this module
    answers for `check-docs.sh`. Rather than reimplement it there — which is precisely the second
    resolver this file exists to prevent — the mirror shells out to this entry point once per
    document and gets back what each link resolves to and whether it exists.

    `--base` names the directory the links are relative to, for a source that is not on disk at that
    path: the service-list README lives on the `services` orphan branch and is read with `git show`,
    so its links resolve as if it sat at the repository root.
    """
    import json

    if not argv:
        print(main.__doc__, file=sys.stderr)
        return 2
    markdown = pathlib.Path(argv[0])
    base = pathlib.Path(argv[argv.index("--base") + 1]) if "--base" in argv else markdown.parent
    root = _repository_root(base if base.is_dir() else pathlib.Path.cwd())
    text = markdown.read_text(encoding="utf-8")
    rows = []
    for link in links_in(text):
        resolved = (base / link.path).resolve()
        rows.append(
            {
                "target": link.target,
                "path": link.path,
                "anchor": link.anchor,
                "resolved": str(resolved.relative_to(root)) if root in resolved.parents else None,
                "exists": resolved.exists(),
            }
        )
    json.dump(rows, sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
