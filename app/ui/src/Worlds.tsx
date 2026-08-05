// Worlds — what this machine is doing for the network, in the order a player asks about it.
//
// Three groups, and the order is the argument:
//
//   1. "You are playing in" — the worlds a player on this machine is inside right now. First
//      because it is the only one they can point at on their own screen, and because until it
//      existed a joiner's app was blank: the worker was never told which world its player had
//      joined, so somebody standing in a world was shown "Nothing is on the network until you
//      share it".
//   2. "Worlds you run" — the ones this peer administers.
//   3. "Worlds you help share" — other people's worlds this node keeps alive. Previously a thin
//      row list rendered only when non-empty, which meant the contribution this whole system runs
//      on was the one thing the app never showed.
//
// Every group uses the same card, because the differences between the three are facts about the
// world, not reasons to re-teach the reader a layout.
//
// # What changed, and why it is a composition problem rather than a sizing one
//
// The three groups used to be three `Card`s laid across the twelve-column grid — two at six
// columns, one at eight, with the LAN card in the remaining four — each containing its own nested
// auto-**fill** grid of 280px cards. Three consequences, and all three only appear once a person
// owns more than about three worlds:
//
//   * A group pinned to half the canvas is a two-column grid no matter how wide the window is, so
//     the display got emptier as it got bigger instead of denser. `auto-fill` then reserved the
//     empty tracks, which is the "content does not fill the display" complaint in its literal form.
//   * A card inside a card is inset twice, so the usable width for the cards was the canvas minus
//     two borders, two paddings and a column gap — the grid was narrow *because* of the frame drawn
//     around it, not because of the window.
//   * There was no way to find a world. Thirty of them is six screens of scrolling and no search,
//     which is the point at which a library stops being a library and becomes a list.
//
// So: one full-width band per group, each drawn on the shared `CARD_GRID` — an `auto-fit` wall that
// gains columns with the window rather than stretching four cards across it; the group's frame is a
// rule and a title rather than a card; and a filter bar above them that searches by name or id and
// can narrow to one group. The bar only appears once the worker has actually reported worlds —
// offering a search over a library nobody has described yet is the same class of claim as rendering
// `0` for "we have not heard".
import { useMemo, useState } from "react";
import { FiGlobe, FiKey, FiPlay, FiSearch, FiUploadCloud, FiUsers } from "react-icons/fi";
import {
  CARD_GRID,
  Empty,
  FilterBar,
  PageGrid,
  PageHeader,
  Stat,
  STAT_GRID,
  cx,
  worldArt,
} from "./components";
import { LanCard } from "./Lan";
import {
  UNKNOWN,
  formatBytes,
  heldBytes,
  show,
  shortId,
  worldRole,
  type Dashboard,
  type World,
} from "./api";

type GroupId = "playing" | "mine" | "helping";

/**
 * The three groups, spelled once.
 *
 * Title, prose and empty state travel together because they are one answer to one question, and
 * because the group filter below has to name the same set the sections render — two hand-kept lists
 * is how a chip ends up filtering to a heading that no longer exists.
 */
const GROUPS: {
  id: GroupId;
  title: string;
  chip: string;
  hint: string;
  icon: JSX.Element;
  emptyTitle: string;
  emptyHint: string;
}[] = [
  {
    id: "playing",
    title: "You are playing in",
    chip: "Playing",
    hint: "Worlds a player on this machine is inside right now. Your node supports each of them for as long as you are here.",
    icon: <FiPlay />,
    emptyTitle: "Not in a world",
    emptyHint: "Join a world from Minecraft's Nodera Network screen and it appears here.",
  },
  {
    id: "mine",
    title: "Worlds you run",
    chip: "You run",
    hint: "You hold these worlds' private keys, so you are the only peer that can rename, re-key or delete them.",
    icon: <FiKey />,
    emptyTitle: "None yet",
    emptyHint: "Share a world from Minecraft's pause menu to put one here.",
  },
  {
    id: "helping",
    title: "Worlds you help share",
    chip: "You help share",
    hint: "Other people's worlds this node keeps available. They stay reachable while their owner is offline because peers like this one hold their pieces.",
    icon: <FiGlobe />,
    emptyTitle: "Not sharing for anyone yet",
    emptyHint:
      "Worlds you join, and worlds you accept from an invitation, are supported from here automatically.",
  },
];

/** Alphabetical, and by id when a world has no name — so a card keeps its place between snapshots. */
function byName(a: World, b: World): number {
  const left = a.name || a.world_id;
  const right = b.name || b.world_id;
  return left.localeCompare(right, undefined, { sensitivity: "base" }) || a.world_id.localeCompare(b.world_id);
}

/** Name or id, case-insensitively. The id is searchable because an unnamed world has nothing else. */
function matches(w: World, query: string): boolean {
  if (!query) return true;
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  return w.name.toLowerCase().includes(needle) || w.world_id.toLowerCase().includes(needle);
}

export function WorldsScreen(props: { d: Dashboard; onOpen: (id: string) => void }) {
  const { d } = props;
  const known = d.link.has_data;
  const [query, setQuery] = useState("");
  const [group, setGroup] = useState<GroupId | "all">("all");

  // One pass, three buckets, no world in two of them: a world you are playing in is listed where
  // you are, not twice. The counters in the tiles come from the payload rather than from these
  // arrays, so the headline and the lists are computed once each and cannot drift apart.
  const buckets = useMemo(() => {
    const out: Record<GroupId, World[]> = { playing: [], mine: [], helping: [] };
    for (const w of d.worlds) {
      if (w.connected) out.playing.push(w);
      else if (w.administered) out.mine.push(w);
      else out.helping.push(w);
    }
    for (const id of Object.keys(out) as GroupId[]) out[id].sort(byName);
    return out;
  }, [d.worlds]);

  const filtered = useMemo(() => {
    const out: Record<GroupId, World[]> = { playing: [], mine: [], helping: [] };
    for (const id of Object.keys(out) as GroupId[]) out[id] = buckets[id].filter((w) => matches(w, query));
    return out;
  }, [buckets, query]);

  const narrowed = query.trim().length > 0 || group !== "all";
  const shown = GROUPS.filter((g) => group === "all" || g.id === group);
  const found = shown.reduce((total, g) => total + filtered[g.id].length, 0);
  // The bar is navigation for a library that has something in it. Before the worker has spoken
  // there is no library to navigate, and a search box over an unknown set implies an empty one.
  const searchable = known && d.worlds.length > 0;

  return (
    <PageGrid className="gap-y-6">
      <PageHeader
        eyebrow="Local collection"
        title="World library"
        description="Worlds you play, administer, or keep available for other players."
      />
      <div className={cx(STAT_GRID, "col-span-12")}>
        <Stat
          label="Playing in"
          value={known ? String(d.counts.connected_worlds) : UNKNOWN}
          sub="on this machine"
          icon={<FiPlay />}
        />
        <Stat
          label="You run"
          value={known ? String(d.counts.administered_worlds) : UNKNOWN}
          icon={<FiKey />}
        />
        <Stat
          label="You help share"
          value={known ? String(d.counts.shared_for_others) : UNKNOWN}
          sub="other people's worlds"
          icon={<FiGlobe />}
        />
        <Stat
          label="Data you serve"
          value={known ? formatBytes(d.counts.shared_bytes) : UNKNOWN}
          sub="verified on this node"
          icon={<FiUploadCloud />}
        />
      </div>

      {searchable && (
        <div className="col-span-12">
          <FilterBar
            label="Search your world library"
            value={query}
            onChange={setQuery}
            placeholder="Find a world by name or id"
            actions={
              <div className="flex min-w-0 flex-wrap items-center gap-1.5" role="group" aria-label="Show one group">
                <GroupChip
                  active={group === "all"}
                  count={d.worlds.length}
                  onSelect={() => setGroup("all")}
                >
                  All
                </GroupChip>
                {GROUPS.map((g) => (
                  <GroupChip
                    key={g.id}
                    active={group === g.id}
                    count={buckets[g.id].length}
                    onSelect={() => setGroup(g.id)}
                  >
                    {g.chip}
                  </GroupChip>
                ))}
              </div>
            }
          />
        </div>
      )}

      {shown.map((g) => (
        <WorldSection
          key={g.id}
          group={g}
          worlds={filtered[g.id]}
          onOpen={props.onOpen}
          // A search that matched nothing in this group is not the same fact as an empty group, and
          // the section says which rather than repeating "share a world from the pause menu" at
          // somebody who has thirty of them and mistyped one.
          empty={
            narrowed && buckets[g.id].length > 0 ? (
              <Empty icon={<FiSearch />} title="No match in this group">
                {buckets[g.id].length === 1
                  ? "The one world here does not match what you typed."
                  : `None of the ${buckets[g.id].length} worlds here match what you typed.`}
              </Empty>
            ) : (
              <Empty icon={g.icon} title={known ? g.emptyTitle : "Waiting for your peer"}>
                {known ? g.emptyHint : undefined}
              </Empty>
            )
          }
        />
      ))}

      {searchable && narrowed && (
        <p className="col-span-12 text-xs text-faint">
          {found === d.worlds.length
            ? `Showing all ${d.worlds.length} worlds.`
            : `Showing ${found} of ${d.worlds.length} worlds.`}
        </p>
      )}

      <div className="col-span-12">
        <LanCard lan={d.lan} known={known} onChanged={() => undefined} />
      </div>
    </PageGrid>
  );
}

/** One group filter, with the size of the set it would leave. */
function GroupChip(props: {
  active: boolean;
  count: number;
  onSelect: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={props.active}
      onClick={props.onSelect}
      className={cx(
        "inline-flex flex-none items-center gap-1.5 rounded-full border px-3 py-1 text-xs whitespace-nowrap",
        "transition-colors duration-[var(--motion-fast)]",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus",
        props.active
          ? "border-brand-edge bg-brand-soft font-medium text-brand-tint"
          : "border-line text-dim hover:bg-surface-hover hover:text-text",
      )}
    >
      {props.children}
      <span className="tabular-nums text-faint">{props.count}</span>
    </button>
  );
}

/**
 * One titled band of world cards, or its own empty state. Always rendered — see the file header.
 *
 * A rule and a title rather than a `Card`, because the card around a grid of cards was costing the
 * grid two paddings and two borders of the width it needed, and the frame said nothing the heading
 * did not. The grid is `auto-fit`, so the tracks that have no card in them collapse and a wider
 * window buys columns instead of margin.
 */
function WorldSection(props: {
  group: (typeof GROUPS)[number];
  worlds: World[];
  empty: JSX.Element;
  onOpen: (id: string) => void;
}) {
  return (
    <section className="col-span-12">
      <header className="mb-3.5 flex flex-wrap items-end justify-between gap-x-5 gap-y-1 border-b border-line-soft pb-2.5">
        <div className="min-w-0">
          {/* Panel 06's card title: an 11px uppercase micro-label, not a heading competing with the
              page's own. The prose under it is `--text-faint`, which is why it is only ever an
              elaboration — that tier may never be the sole carrier of meaning. */}
          <h2 className="flex items-center gap-2 text-2xs font-medium tracking-[0.16em] text-dim uppercase">
            <span aria-hidden className="text-brand-1">{props.group.icon}</span>
            {props.group.title}
          </h2>
          <p className="mt-1.5 max-w-[92ch] text-xs text-faint">{props.group.hint}</p>
        </div>
        {props.worlds.length > 0 && (
          <span className="flex-none text-xs text-faint tabular-nums">{props.worlds.length}</span>
        )}
      </header>
      {props.worlds.length === 0 ? (
        <div className="rounded-lg border border-line-soft bg-surface">{props.empty}</div>
      ) : (
        <div className={CARD_GRID}>
          {props.worlds.map((w) => (
            <WorldCard key={w.world_id} world={w} onOpen={() => props.onOpen(w.world_id)} />
          ))}
        </div>
      )}
    </section>
  );
}

/**
 * What this node is to this world, as panel 04's category glyph plus a word.
 *
 * The reference gives each card a filled brand glyph in its top-left and no badge at all; the glyph
 * is the category. Here the category *is* the relationship, so the glyph carries it and the word is
 * the group's own heading directly above — a glyph alone would be four shapes nobody has been
 * taught, and a chip repeating the heading on every card is the same word thirty times.
 */
const ROLE_MARK = {
  playing: { icon: <FiPlay />, label: "You are here" },
  administered: { icon: <FiKey />, label: "You run this" },
  hosting: { icon: <FiUploadCloud />, label: "Hosting for a peer" },
  supporting: { icon: <FiGlobe />, label: "Supporting" },
} as const;

/**
 * One world in the library: a picture, a name, and the one fact that decides what to do next.
 *
 * # What this card stopped saying
 *
 * It used to carry a completeness bar, a percentage, a three-column table of players / other
 * holders / bytes served, and a footer sentence about tracker visibility — eleven figures for a
 * thing the player is trying to *recognise*. All of it still exists, one click away, on the world's
 * own screen; none of it belongs in a grid whose job is "which one is mine".
 *
 * The picture does that job. It is generated from the world id, so it is the same on every machine
 * and in every session — which is what makes it something you learn rather than decoration.
 */
function WorldCard(props: { world: World; onOpen: () => void }) {
  const w = props.world;
  const mark = ROLE_MARK[worldRole(w)];
  const chips = [
    w.game_endpoint ? "A game is running" : "No game running",
    w.piece_count === 0 ? "Nothing stored yet" : `${formatBytes(heldBytes(w))} stored here`,
  ];

  return (
    <button
      onClick={props.onOpen}
      className={cx(
        "group relative flex min-w-0 flex-col overflow-hidden rounded-lg border border-line-soft bg-surface p-4 text-left",
        "transition-[border-color,translate,box-shadow] duration-[var(--motion-base)] ease-[var(--ease-out)]",
        "hover:-translate-y-0.5 hover:border-brand-edge hover:shadow-e2",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus",
      )}
    >
      {/* The reference's card has a flat fill and separates by lightness alone. This one keeps the
          generated art, because the art is how a player recognises their own world in a grid — but
          at 10% it is a tint on the surface rather than a picture competing with the name. */}
      <span
        aria-hidden
        className="world-art absolute inset-0 opacity-10"
        style={worldArt(w.world_id)}
      />

      <span className="relative flex min-w-0 items-center gap-2.5">
        <span aria-hidden title={mark.label} className="flex-none text-[19px] text-brand-1">
          {mark.icon}
        </span>
        <span className="display-type min-w-0 truncate text-[17px] font-bold uppercase">
          {w.name || shortId(w.world_id, 10, 6)}
        </span>
        {/* The group's heading carries the relationship for sighted readers; this carries it for
            everyone else, since the glyph beside the name is decorative. */}
        <span className="sr-only">{mark.label}</span>
      </span>

      {/* Panel 04's metadata chips: outline pills at a low tier, meant to be skimmed past. The
          reference sets them at 1.3:1, which is decorative to the point of being unreadable — these
          are `--text-dim`, which is the same idea at a contrast a person can act on. */}
      <span className="relative mt-2.5 flex min-w-0 flex-wrap gap-1.5">
        {chips.map((chip) => (
          <span
            key={chip}
            className="rounded-full border border-line px-2.5 py-0.5 text-2xs whitespace-nowrap text-dim"
          >
            {chip}
          </span>
        ))}
      </span>

      <span className="relative mt-4 flex items-center justify-between gap-3">
        <span className="flex min-w-0 items-center gap-2 text-dim">
          <FiUsers aria-hidden className="flex-none" />
          {/* `—`, never `0`: only a node with a game in the world can count its players, so null
              is the ordinary answer on a peer that merely stores it. The title says which of the
              two a dash means, and is never attached to a real figure. */}
          <span
            className="text-body font-medium tabular-nums text-text"
            title={w.players === null || w.players === undefined ? "player count unknown" : undefined}
          >
            {show(w.players, String)}
          </span>
        </span>
        {/* Panel 04's enter affordance: a rule that grows into a triangle. It is the only
            hover-suggesting element on the card, and the reference is right that it is enough. */}
        <span aria-hidden className="flex flex-none items-center gap-1 text-faint transition-colors duration-[var(--motion-base)] group-hover:text-brand-1">
          <span className="block h-px w-8 bg-current transition-[width] duration-[var(--motion-base)] ease-[var(--ease-out)] group-hover:w-14" />
          <span className="block border-y-[5px] border-l-[8px] border-y-transparent border-l-current" />
        </span>
      </span>
    </button>
  );
}
