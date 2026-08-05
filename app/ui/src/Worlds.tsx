// Worlds — what this machine is doing for the network, in the order a player asks about it.
//
// Three lists, and the order is the argument:
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
// Every list uses the same card, because the differences between the three are facts about the
// world, not reasons to re-teach the reader a layout. Worlds open to LAN keep their own card: that
// one has a decision attached, and the rest are a report.
import { useMemo } from "react";
import { FiGlobe, FiKey, FiPlay, FiUploadCloud, FiUsers } from "react-icons/fi";
import { Card, Empty, PageGrid, PageHeader, Stat, STAT_GRID, cx, worldArt } from "./components";
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

export function WorldsScreen(props: { d: Dashboard; onOpen: (id: string) => void }) {
  const { d } = props;
  const known = d.link.has_data;

  // One pass, three buckets, no world in two of them: a world you are playing in is listed where
  // you are, not twice. The counters below come from the payload rather than from these arrays, so
  // the headline and the lists are computed once each and cannot drift apart.
  const { playing, mine, helping } = useMemo(() => {
    const playing: World[] = [];
    const mine: World[] = [];
    const helping: World[] = [];
    for (const w of d.worlds) {
      if (w.connected) playing.push(w);
      else if (w.administered) mine.push(w);
      else helping.push(w);
    }
    return { playing, mine, helping };
  }, [d.worlds]);

  return (
    <PageGrid className="gap-y-5">
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

      <div className="col-span-12 wide:col-span-6">
      <WorldSection
        title="You are playing in"
        hint="Worlds a player on this machine is inside right now. Your node supports each of them for as long as you are here."
        worlds={playing}
        onOpen={props.onOpen}
        empty={
          <Empty icon={<FiPlay />} title={known ? "Not in a world" : "Waiting for your peer"}>
            {known
              ? "Join a world from Minecraft's Nodera Network screen and it appears here."
              : undefined}
          </Empty>
        }
      />
      </div>

      <div className="col-span-12 wide:col-span-6">
      <WorldSection
        title="Worlds you run"
        hint="You hold these worlds' private keys, so you are the only peer that can rename, re-key or delete them."
        worlds={mine}
        onOpen={props.onOpen}
        empty={
          <Empty icon={<FiKey />} title={known ? "None yet" : "Waiting for your peer"}>
            {known ? "Share a world from Minecraft's pause menu to put one here." : undefined}
          </Empty>
        }
      />
      </div>

      <div className="col-span-12 wide:col-span-8">
      <WorldSection
        title="Worlds you help share"
        hint="Other people's worlds this node keeps available. They stay reachable while their owner is offline because peers like this one hold their pieces."
        worlds={helping}
        onOpen={props.onOpen}
        empty={
          <Empty icon={<FiGlobe />} title={known ? "Not sharing for anyone yet" : "Waiting for your peer"}>
            {known
              ? "Worlds you join, and worlds you accept from an invitation, are supported from here automatically."
              : undefined}
          </Empty>
        }
      />
      </div>

      <div className="col-span-12 wide:col-span-4">
        <LanCard lan={d.lan} known={known} onChanged={() => undefined} />
      </div>
    </PageGrid>
  );
}

/** One titled group of world cards, or its own empty state. Always rendered — see the file header. */
function WorldSection(props: {
  title: string;
  hint: string;
  worlds: World[];
  empty: JSX.Element;
  onOpen: (id: string) => void;
}) {
  return (
    <Card
      title={props.title}
      hint={props.hint}
      right={
        props.worlds.length > 0 ? (
          <span className="text-xs text-faint tabular-nums">{props.worlds.length}</span>
        ) : undefined
      }
    >
      {props.worlds.length === 0 ? (
        props.empty
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(min(280px,100%),1fr))] gap-3 py-1">
          {props.worlds.map((w) => (
            <WorldCard key={w.world_id} world={w} onOpen={() => props.onOpen(w.world_id)} />
          ))}
        </div>
      )}
    </Card>
  );
}

/**
 * What this node is to this world, as panel 04's category glyph plus a word.
 *
 * The reference gives each card a filled brand glyph in its top-left and no badge at all; the glyph
 * is the category. Here the category *is* the relationship, so the glyph carries it and the word
 * beside it says which — a glyph alone would be four shapes nobody has been taught.
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
    mark.label,
    w.game_endpoint ? "A game is running" : "No game running",
    w.piece_count === 0 ? "Nothing stored yet" : `${formatBytes(heldBytes(w))} stored here`,
  ];

  return (
    <button
      onClick={props.onOpen}
      className={cx(
        "group relative flex flex-col overflow-hidden rounded-lg border border-line-soft bg-surface p-5 text-left",
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
        <span aria-hidden className="text-[22px] text-brand-1">{mark.icon}</span>
        <span className="display-type truncate text-[19px] font-bold uppercase">
          {w.name || shortId(w.world_id, 10, 6)}
        </span>
      </span>

      {/* Panel 04's metadata chips: outline pills at a low tier, meant to be skimmed past. The
          reference sets them at 1.3:1, which is decorative to the point of being unreadable — these
          are `--text-dim`, which is the same idea at a contrast a person can act on. */}
      <span className="relative mt-3 flex flex-wrap gap-2">
        {chips.map((chip) => (
          <span
            key={chip}
            className="rounded-full border border-line px-2.5 py-0.5 text-xs whitespace-nowrap text-dim"
          >
            {chip}
          </span>
        ))}
      </span>

      <span className="relative mt-5 flex items-center justify-between gap-3">
        <span className="flex items-center gap-2 text-dim">
          <FiUsers aria-hidden />
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
        <span aria-hidden className="flex items-center gap-1 text-faint transition-colors duration-[var(--motion-base)] group-hover:text-brand-1">
          <span className="block h-px w-8 bg-current transition-[width] duration-[var(--motion-base)] ease-[var(--ease-out)] group-hover:w-14" />
          <span className="block border-y-[5px] border-l-[8px] border-y-transparent border-l-current" />
        </span>
      </span>
    </button>
  );
}
