"""Line classification for the three source languages in this tree.

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
``rust``
    Raw strings (``r"…"``, ``r#"…"#``, ``br#"…"#``, 57 files here), lifetimes and loop labels that
    look like an unterminated character literal, and block comments that DO nest.
``ts``
    Template literals (55 files here) and regex literals, which is where the ``/*`` trap lives.

Usage::

    import sys, pathlib
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "lib"))
    import loc_classify

    loc_classify.language_of(path)        # "java" | "rust" | "ts" | None
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

# Extension to language. The key is what `pathlib.Path.suffix` returns, lowercased.
_EXTENSIONS = {
    ".java": "java",
    ".rs": "rust",
    ".ts": "ts",
    ".tsx": "ts",
    ".mts": "ts",
    ".cts": "ts",
}

LANGUAGES = ("java", "rust", "ts")

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
            if lang == "java" and line.startswith('"""', index):
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
    """Consume block-comment text from `index`. Rust nests its block comments; Java and TS do not.

    Returns the position after the comment closed, plus the state to carry to the next line.
    """
    length = len(line)
    while index < length:
        if lang == "rust" and line.startswith("/*", index):
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
