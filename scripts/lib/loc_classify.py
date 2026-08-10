"""Line classification for the four source languages in this tree.

Every line of a source file is exactly one of three things — ``code``, ``comment`` or ``blank`` —
and the whole point of this module is that the answer comes from a lexer rather than from a regex.
A regex cannot tell ``// a comment`` from ``url = "https://example"``, cannot tell Rust's ``'a``
lifetime from a ``'x'`` character literal, and eats the rest of a file the first time it mistakes
the ``/*`` inside a JavaScript regex literal for the start of a block comment. Those three mistakes
are not hypothetical: all three shapes exist in this repository, so all three are handled here.

The counting rules are cloc's, because cloc is the tool anyone will reach for to check this one:

* a line carrying any code is ``code``, even if a comment trails it on the same line;
* a line carrying only a comment — including a line in the middle of an open block comment — is
  ``comment``;
* a line that is empty or whitespace-only is ``blank``, including inside a block comment or inside
  a multi-line string, and it never changes the lexer state;
* a line inside an open multi-line string literal is ``code``, because the string is a value the
  program uses.

Languages, and what each one needs beyond ``//`` and ``/* */``:

``java``
    Text blocks (``\"\"\" … \"\"\"``, 13 files here). Block comments do not nest.
``kotlin``
    Raw strings spelled the same way Java spells a text block, and block comments that DO nest.
    Otherwise it lexes as Java does, which is why it shares Java's rules here rather than getting a
    scanner of its own: no regex literal, no raw-string prefix, the same ``//``, ``/* */`` and
    ``'c'``. The 30 ``.kt``/``.kts`` files are the Android front end and the Gradle build logic.
``rust``
    Raw strings (``r"…"``, ``r#"…"#``, ``br#"…"#``, 57 files here), lifetimes and loop labels that
    look like an unterminated character literal, and block comments that DO nest.
``ts``
    Template literals (55 files here) and regex literals, which is where the ``/*`` trap lives.

Usage::

    import sys, pathlib
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "lib"))
    import loc_classify

    loc_classify.language_of(path)        # "java" | "kotlin" | "rust" | "ts" | None
    loc_classify.extensions()             # every suffix the module counts
    loc_classify.classify(path)           # Counts(code=…, comment=…, blank=…)
    loc_classify.classify_text(src, "rust")
    loc_classify.is_generated(path)       # header says a generator wrote it

Generated files are reported separately by ``scripts/loc-metrics.py`` and are excluded from its
ratchet: ``library/rust/nodera-codec/src/kinds.rs`` grows every time a wire kind is appended, and a
size gate that fails on that would be a gate against adding messages.
"""

from __future__ import annotations

import dataclasses
import pathlib
import re

# Extension to language. The key is what `pathlib.Path.suffix` returns, lowercased.
_EXTENSIONS = {
    ".java": "java",
    ".rs": "rust",
    ".ts": "ts",
    ".tsx": "ts",
    ".mts": "ts",
    ".cts": "ts",
    # JavaScript lexes identically to TypeScript for every construct this module cares about, and
    # leaving it out was not a gap in coverage but a hole in the gate: 45 tracked `.mjs`/`.js` files
    # carrying 7,468 lines sat outside the ratchet entirely, which made *moving TypeScript into a
    # `.mjs` file* read as pure reduction. Two of `nodera-ui`'s largest modules are already `.mjs`
    # with hand-written `.d.mts` companions, so the move was not hypothetical.
    ".js": "ts",
    ".mjs": "ts",
    ".cjs": "ts",
    # Kotlin was missing until 2026-08-09, and it is the same hole as `.mjs` in the one language
    # with an actively-developed front end: 30 tracked files carrying 4,033 code lines — the whole
    # Android app (`ui/Settings.kt` alone is 1,654 lines) and every Gradle build script and
    # convention plugin — sat outside the ratchet, so moving Java or TypeScript into `.kt` read as
    # pure reduction. It showed up structurally too: `--by-module` reported `nodera-app` as its
    # 818-line Tauri shell, and the registered `build-logic` component did not appear in the table
    # at all, because every file it owns is `.kt` or `.kts`.
    ".kt": "kotlin",
    ".kts": "kotlin",
}

LANGUAGES = ("java", "kotlin", "rust", "ts")

# Where one language's rule is another's too. Kotlin spells a raw string the way Java spells a text
# block, and nests its block comments the way Rust does; naming the sets is what keeps `_scan_line`
# from growing a per-language branch for each of them.
_TEXT_BLOCK_LANGUAGES = frozenset({"java", "kotlin"})
_NESTING_COMMENT_LANGUAGES = frozenset({"kotlin", "rust"})

# How many lines from the top of a file are searched for a generator's disclaimer. Generators in
# this tree put it in the first comment block; five lines covers all of them without matching a
# sentence in the middle of a long prose header that merely mentions the word.
_GENERATED_HEADER_LINES = 5
_GENERATED_MARKERS = ("@generated", "DO NOT EDIT")

# A `/` that follows one of these characters opens a regex literal rather than dividing; anything
# else (an identifier, a digit, a closing paren or bracket) means division. This is the standard
# heuristic and it is only ever consulted in TypeScript.
#
# `<` is deliberately absent even though it is an operator: this tree is full of JSX, where `</div>`
# puts a slash straight after one, and reading that as a regex would consume the rest of the line.
# The cost is that `a < /re/.test(b)` is misread, which does not occur and would not change a line's
# classification if it did.
_REGEX_PRECEDERS = set("(,=:[!&|?{};+-*%~^>\n")


@dataclasses.dataclass(frozen=True)
class Counts:
    """One file's, or one group's, line classification."""

    code: int = 0
    comment: int = 0
    blank: int = 0

    @property
    def total(self) -> int:
        return self.code + self.comment + self.blank

    @property
    def comment_ratio(self) -> float:
        """Comment lines per code line — the density the reduction programme targets."""
        return self.comment / self.code if self.code else 0.0

    def __add__(self, other: "Counts") -> "Counts":
        return Counts(
            self.code + other.code,
            self.comment + other.comment,
            self.blank + other.blank,
        )


def language_of(path: str | pathlib.Path) -> str | None:
    """The language of a file, by extension, or None if it is not one this module counts."""
    return _EXTENSIONS.get(pathlib.Path(path).suffix.lower())


def extensions() -> frozenset[str]:
    """Every suffix this module counts, so a caller cannot keep a second list that drifts from it.

    `scripts/loc-metrics.py` asks git for exactly these — an extension known here but not asked for
    there is a file the ratchet never sees, which is the shape of both holes this table has had.
    """
    return frozenset(_EXTENSIONS)


def is_generated(path: str | pathlib.Path) -> bool:
    """True when the file's header says a generator produced it."""
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for index, line in enumerate(handle):
                if index >= _GENERATED_HEADER_LINES:
                    break
                if any(marker in line for marker in _GENERATED_MARKERS):
                    return True
    except OSError:
        return False
    return False


def classify(path: str | pathlib.Path, lang: str | None = None) -> Counts:
    """Classify one file. The language is taken from the extension unless it is given."""
    language = lang or language_of(path)
    if language is None:
        raise ValueError(f"no language for {path} (extensions: {sorted(_EXTENSIONS)})")
    text = pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
    return classify_text(text, language)


_CFG_TEST = re.compile(r"^\s*#\[cfg\(test\)\]")


def classify_partitioned(text: str, lang: str) -> tuple[Counts, Counts]:
    """Split one source string into (production, test) counts.

    Only Rust partitions: it puts its unit tests inside the file they test, in a
    ``#[cfg(test)] mod tests { … }`` block, and counting that block as production code overstates
    every Rust production figure in this repository by a third. Measured at the time this was
    written: 11,432 of 32,347 Rust code lines — 35.3% — were inside such a block, so
    ``tracker/src/service.rs`` read as a 1,228-line monolith when 811 of those lines are its tests
    and only 417 are the service.

    This is the third appearance of one bug. `:testing`'s `src/main` was reported as production
    because the split was made by Gradle source set; `.mjs` was reported as nothing at all because
    the split was made by extension. In each case the tree was fine and the question was wrong.

    Java, Kotlin and TypeScript return everything as production here; their test code is identified
    by path in ``scripts/loc-metrics.py``, which is the correct split for languages that keep tests
    in separate files.
    """
    main_text, test_text = split_test_blocks(text, lang)
    return classify_text(main_text, lang), classify_text(test_text, lang)


def split_test_blocks(text: str, lang: str) -> tuple[str, str]:
    """Split one source string into (production text, test text).

    The same rule `classify_partitioned` counts with, exposed as text so a second reader can ask a
    different question of the same split — `scripts/reference-check.py` needs to know whether a
    symbol's only caller is the file's own `#[cfg(test)] mod tests`, which is a reference and is not
    a production one.

    Only Rust splits; for every other language the whole string is production, because their tests
    live in separate files and are identified by path.
    """
    if lang != "rust":
        return text, ""

    lines = text.splitlines()
    test_lines: list[str] = []
    main_lines: list[str] = []
    index = 0
    while index < len(lines):
        if _CFG_TEST.match(lines[index]):
            end = _block_end(lines, index)
            test_lines.extend(lines[index:end + 1])
            index = end + 1
        else:
            main_lines.append(lines[index])
            index += 1
    return "\n".join(main_lines), "\n".join(test_lines)


def _block_end(lines: list[str], start: int) -> int:
    """The last line of the item beginning at or just after ``start``.

    Two shapes reach here and the terminator that appears first decides which. A braced item —
    ``#[cfg(test)] mod tests { … }`` — ends where its brace depth returns to zero. An unbraced one —
    ``#[cfg(test)] mod test_support;``, the idiomatic way to gate a whole test-support *file* — ends
    at its own semicolon, and reading that as braced is not a hypothetical: it ran past the item
    to the next depth-0 ``}`` and handed 32 lines of `tracker/src/main.rs` to the test bucket —
    nine `use` lines and the entire body of `async fn main`, the entry point of a shipped service —
    with `rendezvous/src/main.rs` doing the same. A gate only fails on a rise, so 54 lines leaving
    `rust.main.code` passed green and were stamped as reduction.

    Brace counting is enough here and string-aware lexing is not needed: an unbalanced brace inside
    a string literal in a test module would have to survive `cargo fmt` and `rustc`, and the failure
    mode if one ever did is a miscount, not a crash — the loop is bounded by the file.
    """
    depth = 0
    opened = False
    for index in range(start, len(lines)):
        line = lines[index]
        if not opened and _terminates_unbraced(line):
            return index
        depth += line.count("{") - line.count("}")
        if "{" in line:
            opened = True
        if opened and depth <= 0:
            return index
    return len(lines) - 1


def _terminates_unbraced(line: str) -> bool:
    """True when this line ends an item with ``;`` before any brace has opened.

    Skipped: comment lines, because a ``;`` in the doc comment above a braced module is not that
    module's end, and lines that are nothing but an attribute, because ``#[cfg(test)]`` on its own
    terminates nothing. An attribute *followed by* the item — ``#[cfg(test)] mod test_support;`` on
    one line — is not skipped, which is the whole case this exists for.
    """
    stripped = line.strip()
    if stripped.startswith(("//", "/*", "*")):
        return False
    if stripped.startswith(("#[", "#!")) and stripped.endswith("]"):
        return False
    semicolon = line.find(";")
    if semicolon < 0:
        return False
    brace = line.find("{")
    return brace < 0 or semicolon < brace


def classify_text(text: str, lang: str) -> Counts:
    """Classify a source string. This is the whole state machine."""
    if lang not in LANGUAGES:
        raise ValueError(f"unknown language '{lang}' (known: {LANGUAGES})")

    code = comment = blank = 0
    # Carried between lines: what construct is still open at the newline.
    #   ("normal", 0)      nothing open
    #   ("block", depth)   inside /* */ — depth only ever exceeds 1 in Rust, which nests them
    #   ("raw", hashes)    inside a Rust r#"…"# with that many hashes
    #   ("text", 0)        inside a Java """ text block
    #   ("template", 0)    inside a TypeScript `…`
    state, extra = "normal", 0

    for line in text.splitlines():
        if not line.strip():
            blank += 1
            continue
        saw_code, saw_comment, state, extra = _scan_line(line, lang, state, extra)
        if saw_code:
            code += 1
        elif saw_comment:
            comment += 1
        else:
            # Reachable only for a line of pure punctuation the scanner consumed as nothing; a
            # non-blank line that is neither code nor comment does not exist, so call it code.
            code += 1

    return Counts(code, comment, blank)


def _scan_line(
    line: str, lang: str, state: str, extra: int
) -> tuple[bool, bool, str, int]:
    """Walk one line. Returns (saw_code, saw_comment, next_state, next_extra)."""
    saw_code = False
    saw_comment = False
    length = len(line)
    index = 0
    # The last character that was not whitespace, used only to decide whether a `/` in TypeScript
    # opens a regex. A newline stands in for "start of line", which is a regex position.
    last_significant = "\n"

    while index < length:
        # --- constructs that were already open when the line began -------------------------
        if state == "block":
            saw_comment = True
            index, state, extra = _resume_block(line, index, lang, extra)
            if state == "block":
                break
            continue

        if state == "raw":
            saw_code = True
            terminator = '"' + "#" * extra
            found = line.find(terminator, index)
            if found < 0:
                break
            index = found + len(terminator)
            state, extra = "normal", 0
            last_significant = '"'
            continue

        if state == "text":
            saw_code = True
            found = _find_unescaped(line, index, '"""')
            if found < 0:
                break
            index = found + 3
            state = "normal"
            last_significant = '"'
            continue

        if state == "template":
            saw_code = True
            found = _find_unescaped(line, index, "`")
            if found < 0:
                break
            index = found + 1
            state = "normal"
            last_significant = "`"
            continue

        # --- normal code ------------------------------------------------------------------
        char = line[index]

        if char.isspace():
            index += 1
            continue

        following = line[index + 1] if index + 1 < length else ""

        if char == "/" and following == "/":
            saw_comment = True
            break

        if char == "/" and following == "*":
            saw_comment = True
            index, state, extra = _resume_block(line, index + 2, lang, 1)
            if state == "block":
                break
            continue

        if lang == "ts" and char == "/" and last_significant in _REGEX_PRECEDERS:
            saw_code = True
            index = _skip_regex(line, index)
            last_significant = "/"
            continue

        if lang == "rust":
            raw = _rust_raw_string(line, index)
            if raw is not None:
                saw_code = True
                start, hashes = raw
                terminator = '"' + "#" * hashes
                found = line.find(terminator, start)
                if found < 0:
                    state, extra = "raw", hashes
                    break
                index = found + len(terminator)
                last_significant = '"'
                continue

        if char == '"':
            saw_code = True
            if lang in _TEXT_BLOCK_LANGUAGES and line.startswith('"""', index):
                found = _find_unescaped(line, index + 3, '"""')
                if found < 0:
                    state = "text"
                    break
                index = found + 3
            else:
                index = _skip_quoted(line, index, '"')
            last_significant = '"'
            continue

        if char == "'":
            saw_code = True
            if lang == "rust" and not _is_rust_char_literal(line, index):
                # A lifetime or a loop label: `'a`, `'static`, `'outer:`. Nothing to close.
                index += 1
                last_significant = "'"
                continue
            index = _skip_quoted(line, index, "'")
            last_significant = "'"
            continue

        if lang == "ts" and char == "`":
            saw_code = True
            found = _find_unescaped(line, index + 1, "`")
            if found < 0:
                state = "template"
                break
            index = found + 1
            last_significant = "`"
            continue

        saw_code = True
        last_significant = char
        index += 1

    return saw_code, saw_comment, state, extra


def _resume_block(line: str, index: int, lang: str, depth: int) -> tuple[int, str, int]:
    """Consume block-comment text from `index`. Rust and Kotlin nest block comments; Java does not.

    Returns the position after the comment closed, plus the state to carry to the next line.
    """
    length = len(line)
    while index < length:
        if lang in _NESTING_COMMENT_LANGUAGES and line.startswith("/*", index):
            depth += 1
            index += 2
            continue
        if line.startswith("*/", index):
            depth -= 1
            index += 2
            if depth <= 0:
                return index, "normal", 0
            continue
        index += 1
    return index, "block", depth


def _skip_quoted(line: str, index: int, quote: str) -> int:
    """Consume a single-line quoted literal starting at its opening quote, honouring backslashes.

    An unterminated literal — which is what a line continuation in a broken file looks like —
    returns the end of the line rather than raising, because this module measures files, it does
    not validate them.
    """
    index += 1
    length = len(line)
    while index < length:
        if line[index] == "\\":
            index += 2
            continue
        if line[index] == quote:
            return index + 1
        index += 1
    return length


def _find_unescaped(line: str, index: int, terminator: str) -> int:
    """The next `terminator` at or after `index` that is not preceded by a backslash escape."""
    length = len(line)
    while index < length:
        if line[index] == "\\":
            index += 2
            continue
        if line.startswith(terminator, index):
            return index
        index += 1
    return -1


def _skip_regex(line: str, index: int) -> int:
    """Consume a TypeScript regex literal starting at its opening slash.

    Character classes are tracked because `/` is an ordinary character inside `[...]`, and a regex
    like `/[/]/` would otherwise close two characters early — which matters far less than the case
    this whole function exists for: `/\\/\\*/` must not open a block comment.
    """
    index += 1
    length = len(line)
    in_class = False
    while index < length:
        char = line[index]
        if char == "\\":
            index += 2
            continue
        if char == "[":
            in_class = True
        elif char == "]":
            in_class = False
        elif char == "/" and not in_class:
            return index + 1
        index += 1
    return length


def _rust_raw_string(line: str, index: int) -> tuple[int, int] | None:
    """If a Rust raw string opens at `index`, return (position after the opening quote, hash count).

    Recognises `r"`, `r#"`, `r##"` … and the byte-string forms `br"`, `br#"`. Returns None when the
    `r` is just a letter inside an identifier, which is why the preceding character is checked.
    """
    if index > 0 and (line[index - 1].isalnum() or line[index - 1] == "_"):
        return None
    cursor = index
    if line.startswith("br", cursor):
        cursor += 2
    elif line.startswith("r", cursor):
        cursor += 1
    else:
        return None
    hashes = 0
    while cursor < len(line) and line[cursor] == "#":
        hashes += 1
        cursor += 1
    if cursor < len(line) and line[cursor] == '"':
        return cursor + 1, hashes
    return None


def _is_rust_char_literal(line: str, index: int) -> bool:
    """Tell `'x'` and `'\\n'` from the lifetime `'a` and the loop label `'outer:`.

    A character literal is a quote, one character (or one escape), then a closing quote. A lifetime
    is a quote followed by an identifier and no closing quote, so looking two characters ahead
    settles it — with the escape case checked first, since `'\\''` is four characters wide.
    """
    if index + 1 >= len(line):
        return False
    if line[index + 1] == "\\":
        return True
    return index + 2 < len(line) and line[index + 2] == "'"
