#!/usr/bin/env python3
# ===========================================================================
# nodera sparkprofile — read a .sparkprofile without a browser.
#
# A capture from `/spark profiler stop --save-to-file` is a protobuf-encoded
# SamplerData message. The normal way to read one is to drag it onto
# https://spark.lucko.me/, which parses it client-side. That is fine for a human
# and useless for a test: a suite cannot assert against a web page, and CI has
# no one to do the dragging.
#
# So this reads the same bytes and prints the one table the profile exists to
# produce — how much of the sampled time belongs to each mod or plugin, and
# which of OUR frames inside it were the expensive ones.
#
#   scripts/lib/sparkprofile.py profile.sparkprofile
#   scripts/lib/sparkprofile.py profile.sparkprofile --source nodera --top 25
#   scripts/lib/sparkprofile.py profile.sparkprofile --assert-source nodera
#
# Pure standard library on purpose. The e2e harness already requires python3 and
# nothing else; adding `protobuf` to that contract to read a diagnostic artifact
# would be a poor trade. The wire format is stable and the four field numbers we
# need are pinned by docs/minecraft/upstream/spark's own .proto files.
#
# ---------------------------------------------------------------------------
# WHAT THE NUMBERS MEAN — read this before quoting one
#
# SELF is the time sampled in a frame belonging to that source, with the time of
# its callees removed. It is the honest answer to "whose bytecode was executing".
#
# SUBTREE is what spark's own Sources view shows: for each topmost frame owned by
# a source, the WHOLE subtree beneath it, including the vanilla and JDK work that
# frame called into. It is bigger, often much bigger, and it is the right number
# for "how much tick time did this mod cause". It is also double-counted across
# sources whenever one mod calls another.
#
# Neither is a percentage "of the tick" unless the capture covered whole ticks.
# Both are percentages of SAMPLED time on the threads that were dumped.
# Unattributed time is not overhead — it is Minecraft, the JDK, lambdas whose
# classes could not be resolved, and (on NeoForge) every mixin-injected frame.
# See docs/minecraft/spark/05-source-attribution.md.
# ===========================================================================

import argparse
import re
import struct
import sys
from collections import defaultdict

# --- protobuf wire format ---------------------------------------------------

WIRE_VARINT, WIRE_64, WIRE_LEN, WIRE_32 = 0, 1, 2, 5


def _varint(buf, i):
    result = shift = 0
    while True:
        if i >= len(buf):
            raise ValueError("truncated varint")
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7
        if shift > 70:
            raise ValueError("varint too long")


def parse(buf):
    """Decode one message into {field_number: [value, ...]}.

    Values are ints for varint/fixed fields and memoryview-backed bytes for
    length-delimited ones. Unknown fields are kept, which is what makes this
    survive spark adding a field.
    """
    out = defaultdict(list)
    i, n = 0, len(buf)
    while i < n:
        key, i = _varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == WIRE_VARINT:
            val, i = _varint(buf, i)
        elif wire == WIRE_64:
            val = buf[i:i + 8]
            i += 8
        elif wire == WIRE_LEN:
            ln, i = _varint(buf, i)
            val = buf[i:i + ln]
            i += ln
        elif wire == WIRE_32:
            val = buf[i:i + 4]
            i += 4
        else:
            raise ValueError("unsupported wire type %d for field %d" % (wire, field))
        out[field].append((wire, val))
    return out


def _first(msg, field, default=None):
    vals = msg.get(field)
    return vals[0][1] if vals else default


def _text(msg, field, default=""):
    raw = _first(msg, field)
    return raw.decode("utf-8", "replace") if raw is not None else default


def _int(msg, field, default=0):
    vals = msg.get(field)
    if not vals:
        return default
    wire, val = vals[0]
    return val if wire == WIRE_VARINT else default


def _doubles(msg, field):
    """A `repeated double`, packed or not. spark packs them; older captures may not."""
    out = []
    for wire, val in msg.get(field, []):
        if wire == WIRE_LEN:
            count = len(val) // 8
            out.extend(struct.unpack("<%dd" % count, val[:count * 8]))
        elif wire == WIRE_64:
            out.append(struct.unpack("<d", val)[0])
    return out


def _int32s(msg, field):
    """A `repeated int32`, packed or not."""
    out = []
    for wire, val in msg.get(field, []):
        if wire == WIRE_LEN:
            i = 0
            while i < len(val):
                v, i = _varint(val, i)
                out.append(v)
        elif wire == WIRE_VARINT:
            out.append(val)
    return out


def _str_map(msg, field):
    """A proto3 map<string, string>: repeated entries of {1: key, 2: value}."""
    out = {}
    for wire, val in msg.get(field, []):
        if wire != WIRE_LEN:
            continue
        entry = parse(val)
        out[_text(entry, 1)] = _text(entry, 2)
    return out


def _mod_map(msg, field):
    """map<string, PluginOrModMetadata> — we want the name, version and builtin flag."""
    out = {}
    for wire, val in msg.get(field, []):
        if wire != WIRE_LEN:
            continue
        entry = parse(val)
        key = _text(entry, 1)
        body = _first(entry, 2)
        meta = parse(body) if body is not None else {}
        out[key] = {
            "name": _text(meta, 1, key),
            "version": _text(meta, 2),
            "builtin": bool(_int(meta, 5)),
        }
    return out


# --- the sampler model ------------------------------------------------------

# SamplerData
F_METADATA, F_THREADS, F_CLASS_SOURCES, F_METHOD_SOURCES, F_LINE_SOURCES = 1, 2, 3, 4, 5
# ThreadNode
F_TN_NAME, F_TN_CHILDREN, F_TN_TIMES, F_TN_CHILD_REFS = 1, 3, 4, 5
# StackTraceNode
F_SN_CLASS, F_SN_METHOD, F_SN_LINE, F_SN_DESC, F_SN_TIMES, F_SN_CHILD_REFS = 3, 4, 6, 7, 8, 9

UNATTRIBUTED = "(unattributed)"

SAMPLER_MODE = {0: "execution", 1: "allocation"}
SAMPLER_ENGINE = {0: "built-in java", 1: "async-profiler"}
AGGREGATOR_TYPE = {0: "simple", 1: "ticked"}


class Node:
    """One flattened StackTraceNode: its own sampled time and its children's indices."""

    __slots__ = ("cls", "method", "desc", "line", "total", "children", "source")

    def __init__(self, msg):
        self.cls = _text(msg, F_SN_CLASS)
        self.method = _text(msg, F_SN_METHOD)
        self.desc = _text(msg, F_SN_DESC)
        self.line = _int(msg, F_SN_LINE, -1)
        self.total = sum(_doubles(msg, F_SN_TIMES))
        self.children = _int32s(msg, F_SN_CHILD_REFS)
        self.source = None

    @property
    def frame(self):
        return "%s.%s" % (self.cls, self.method) if self.cls else self.method


class Profile:
    def __init__(self, raw):
        root = parse(raw)
        meta_raw = _first(root, F_METADATA)
        self.meta = parse(meta_raw) if meta_raw is not None else {}
        self.class_sources = _str_map(root, F_CLASS_SOURCES)
        self.method_sources = _str_map(root, F_METHOD_SOURCES)
        self.line_sources = _str_map(root, F_LINE_SOURCES)
        self.installed = _mod_map(self.meta, 13)

        agg_raw = _first(self.meta, 5)
        agg = parse(agg_raw) if agg_raw is not None else {}
        self.aggregator = AGGREGATOR_TYPE.get(_int(agg, 1), "?")
        self.tick_threshold = _int(agg, 3, 0)
        self.ticks_included = _int(agg, 4, 0)

        self.interval = _int(self.meta, 3, 0)
        self.comment = _text(self.meta, 6)
        self.ticks = _int(self.meta, 12, 0)
        self.mode = SAMPLER_MODE.get(_int(self.meta, 15), "execution")
        self.engine = SAMPLER_ENGINE.get(_int(self.meta, 16), "?")
        self.engine_version = _text(self.meta, 17)

        self.threads = []
        for wire, val in root.get(F_THREADS, []):
            if wire != WIRE_LEN:
                continue
            tn = parse(val)
            nodes = [Node(parse(c)) for w, c in tn.get(F_TN_CHILDREN, []) if w == WIRE_LEN]
            self.threads.append({
                "name": _text(tn, F_TN_NAME),
                "total": sum(_doubles(tn, F_TN_TIMES)),
                "roots": _int32s(tn, F_TN_CHILD_REFS),
                "nodes": nodes,
            })
        self._attribute()

    # -- attribution ---------------------------------------------------------

    def _lookup(self, node):
        """Resolve a frame to its owning mod/plugin, mirroring spark's own precedence.

        The exporter fills whichever of the three maps it could: the async engine
        knows a method descriptor, the Java engine knows a line number, and both
        always know the class. Most specific wins, because a mixin-aware platform
        can attribute two methods of the same class to two different mods.
        """
        if self.method_sources and self.desc_key(node) in self.method_sources:
            return self.method_sources[self.desc_key(node)]
        if self.line_sources and node.line >= 0:
            key = "%s;%d" % (node.cls, node.line)
            if key in self.line_sources:
                return self.line_sources[key]
        return self.class_sources.get(node.cls)

    @staticmethod
    def desc_key(node):
        return "%s;%s;%s" % (node.cls, node.method, node.desc)

    def _attribute(self):
        for thread in self.threads:
            for node in thread["nodes"]:
                node.source = self._lookup(node) or UNATTRIBUTED

    # -- measures ------------------------------------------------------------

    def _selected(self, pattern):
        if pattern is None:
            return self.threads
        return [t for t in self.threads if pattern.search(t["name"])]

    def self_times(self, pattern=None):
        """Sampled time per source with callees removed — whose bytecode ran."""
        acc = defaultdict(float)
        hottest = {}
        for thread in self._selected(pattern):
            nodes = thread["nodes"]
            for node in nodes:
                child_total = sum(nodes[i].total for i in node.children if i < len(nodes))
                own = node.total - child_total
                if own <= 0:
                    continue
                acc[node.source] += own
                best = hottest.get(node.source)
                if best is None or own > best[1]:
                    hottest[node.source] = (node.frame, own)
        return acc, {k: v[0] for k, v in hottest.items()}

    def subtree_times(self, pattern=None):
        """spark's own Sources measure: the whole subtree under each topmost owned frame.

        Walks down from the thread roots and, the first time a frame belonging to
        a source is met, charges that source the entire subtree and stops
        descending for it. Time a mod caused inside Minecraft counts here; time
        two mods share is counted for both.
        """
        acc = defaultdict(float)
        for thread in self._selected(pattern):
            nodes = thread["nodes"]
            stack = [(i, None) for i in thread["roots"] if i < len(nodes)]
            while stack:
                idx, claimed = stack.pop()
                node = nodes[idx]
                src = node.source
                if claimed is None and src != UNATTRIBUTED:
                    acc[src] += node.total
                    claimed = src
                for c in node.children:
                    if c < len(nodes):
                        stack.append((c, claimed))
        return acc

    def frames_for(self, source, limit=20, pattern=None):
        """The hottest individual frames owned by one source, by self time."""
        acc = defaultdict(float)
        for thread in self._selected(pattern):
            nodes = thread["nodes"]
            for node in nodes:
                if node.source != source:
                    continue
                child_total = sum(nodes[i].total for i in node.children if i < len(nodes))
                own = node.total - child_total
                if own > 0:
                    acc[node.frame] += own
        return sorted(acc.items(), key=lambda kv: -kv[1])[:limit]

    def sampled_total(self, pattern=None):
        return sum(t["total"] for t in self._selected(pattern))


# --- rendering --------------------------------------------------------------

def _fmt_ms(v):
    return "%.1f" % v if v < 100000 else "%.0f" % v


def render(profile, path, top, focus, pattern=None):
    unit = "ms" if profile.mode == "execution" else "bytes"
    total = profile.sampled_total(pattern)
    selected = profile._selected(pattern)
    out = []
    out.append("profile: %s" % path)
    detail = "engine=%s" % profile.engine
    if profile.engine_version:
        detail += " (%s)" % profile.engine_version
    # SamplerMetadata.interval is MICROseconds in execution mode — spark's own
    # SamplerMode.EXECUTION divides by 1000 to display it. In allocation mode the
    # same field is a byte count and must not be scaled.
    if profile.mode == "execution":
        detail += "  mode=execution  interval=%gms" % (profile.interval / 1000.0)
    else:
        detail += "  mode=allocation  interval=%d bytes" % profile.interval
    out.append(detail)
    tickline = "threads=%d/%d  sampled=%s %s  ticks=%d" % (
        len(selected), len(profile.threads), _fmt_ms(total), unit, profile.ticks)
    if profile.aggregator == "ticked":
        tickline += "  (only ticks over %dms: %d included)" % (
            profile.tick_threshold, profile.ticks_included)
    out.append(tickline)
    if profile.comment:
        out.append("comment: %s" % profile.comment)
    out.append("")

    if total <= 0:
        if not profile.threads:
            out.append("NO THREADS WERE SAMPLED. The profiler ran and produced an empty capture.")
            out.append("The usual cause is the default thread dumper resolving to nothing; pass")
            out.append("`--thread *` to `/spark profiler start` and capture again.")
        elif pattern is not None:
            out.append("No sampled thread matched the filter. Threads in this capture:")
            for t in sorted(profile.threads, key=lambda t: -t["total"])[:15]:
                out.append("  %s" % t["name"])
        else:
            out.append("NO SAMPLES. The profiler ran but recorded nothing — a capture that was")
            out.append("stopped too soon, or an --only-ticks-over threshold no tick reached.")
        return "\n".join(out) + "\n"

    # Which threads the numbers below are drawn from. Without this the reader
    # cannot tell a source that is 0.1% of forty mostly-parked threads from one
    # that is 0.1% of the tick — and only the second is good news.
    out.append("%-34s %14s %8s" % ("THREAD", "SAMPLED " + unit, "%"))
    out.append("-" * 60)
    for t in sorted(selected, key=lambda t: -t["total"])[:8]:
        out.append("%-34s %14s %7.1f%%" % (
            t["name"][:34], _fmt_ms(t["total"]), 100.0 * t["total"] / total))
    if len(selected) > 8:
        rest = sum(t["total"] for t in sorted(selected, key=lambda t: -t["total"])[8:])
        out.append("%-34s %14s %7.1f%%" % (
            "(%d more)" % (len(selected) - 8), _fmt_ms(rest), 100.0 * rest / total))
    out.append("")

    selfs, hottest = profile.self_times(pattern)
    subtree = profile.subtree_times(pattern)

    out.append("%-28s %12s %8s %12s  %s" % ("SOURCE", "SELF " + unit, "%SELF", "SUBTREE " + unit, "HOTTEST OWN FRAME"))
    out.append("-" * 110)
    for src, val in sorted(selfs.items(), key=lambda kv: -kv[1]):
        out.append("%-28s %12s %7.1f%% %12s  %s" % (
            src[:28], _fmt_ms(val), 100.0 * val / total,
            _fmt_ms(subtree.get(src, 0.0)), hottest.get(src, "")[:44]))
    out.append("")

    # Sources spark knows are installed but never sampled. Their absence from the
    # table above is a result, not a gap — it is the "didn't show up in this
    # profile" list the viewer renders, and it is how you tell "cheap" from
    # "never loaded".
    seen = set(selfs)
    quiet = sorted(m["name"] for k, m in profile.installed.items()
                   if not m["builtin"] and m["name"] not in seen and k not in seen)
    if quiet:
        out.append("installed but never sampled: %s" % ", ".join(quiet))
        out.append("")

    for src in focus:
        frames = profile.frames_for(src, top, pattern)
        if not frames:
            continue
        out.append("hottest frames in '%s' (self %s):" % (src, unit))
        for frame, val in frames:
            out.append("  %10s %6.2f%%  %s" % (_fmt_ms(val), 100.0 * val / total, frame))
        out.append("")

    return "\n".join(out).rstrip() + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Summarise a spark .sparkprofile by owning mod/plugin, offline.")
    ap.add_argument("profile", help="path to a .sparkprofile file")
    ap.add_argument("--top", type=int, default=15,
                    help="how many hot frames to list per focused source (default 15)")
    ap.add_argument("--source", action="append", default=None, metavar="NAME",
                    help="list the hottest frames owned by this source; repeatable "
                         "(default: nodera and NoderaEndpoint)")
    ap.add_argument("--thread", default=None, metavar="REGEX",
                    help="restrict every number to threads whose name matches this "
                         "regex. `--thread 'Server thread'` is how you read the tick: "
                         "a capture taken with `--thread *` includes dozens of parked "
                         "threads whose idle time swamps the percentages.")
    ap.add_argument("--assert-installed", action="append", default=[], metavar="NAME",
                    help="exit non-zero unless spark enumerated this mod/plugin as installed. "
                         "Weaker than --assert-source and never flaky: it proves the profiler "
                         "loaded and saw the plugin, without requiring the plugin to have been "
                         "busy during the capture.")
    ap.add_argument("--assert-nonempty", action="store_true",
                    help="exit non-zero if the capture contains no sampled threads at all.")
    ap.add_argument("--assert-source", action="append", default=[], metavar="NAME",
                    help="exit non-zero unless this source has non-zero self time. A "
                         "profile in which our own code never appears is a broken "
                         "integration, not a fast mod.")
    args = ap.parse_args(argv)

    try:
        with open(args.profile, "rb") as fh:
            raw = fh.read()
        profile = Profile(raw)
    except (OSError, ValueError, struct.error) as exc:
        print("cannot read %s: %s" % (args.profile, exc), file=sys.stderr)
        return 2

    pattern = None
    if args.thread:
        try:
            pattern = re.compile(args.thread)
        except re.error as exc:
            print("bad --thread regex: %s" % exc, file=sys.stderr)
            return 2

    focus = args.source if args.source is not None else ["nodera", "NoderaEndpoint"]
    sys.stdout.write(render(profile, args.profile, args.top, focus, pattern))

    if args.assert_nonempty and not profile.threads:
        print("\nASSERTION FAILED: the capture contains no sampled threads at all.",
              file=sys.stderr)
        return 1

    if args.assert_installed:
        known = {m["name"] for m in profile.installed.values()} | set(profile.installed)
        missing = [n for n in args.assert_installed if n not in known]
        if missing:
            print("\nASSERTION FAILED: spark did not enumerate as installed: %s"
                  % ", ".join(missing), file=sys.stderr)
            print("Enumerated: %s" % (", ".join(sorted(known)) or "(none)"), file=sys.stderr)
            return 1

    if args.assert_source:
        selfs, _ = profile.self_times(pattern)
        missing = [s for s in args.assert_source if selfs.get(s, 0.0) <= 0.0]
        if missing:
            print("\nASSERTION FAILED: no sampled time attributed to: %s"
                  % ", ".join(missing), file=sys.stderr)
            print("Sources present: %s" % (", ".join(sorted(selfs)) or "(none)"),
                  file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
