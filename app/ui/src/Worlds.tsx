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
import { FiGlobe, FiKey, FiPlay, FiUploadCloud } from "react-icons/fi";
import { Card, Empty, PageGrid, PageHeader, Pill, Stat, STAT_GRID, cx, worldArt } from "./components";
import { LanCard } from "./Lan";
import {
  UNKNOWN,
  formatBytes,
  heldBytes,
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

/** The badge in a card's top-right: what this node is to this world, said in two or three words. */
const ROLE_PILL = {
  playing: { tone: "up", label: "You are here" },
  administered: { tone: "up", label: "You run this" },
  hosting: { tone: "warn", label: "Hosting for a peer" },
  supporting: { tone: "muted", label: "Supporting" },
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
  const pill = ROLE_PILL[worldRole(w)];

  return (
    <button
      onClick={props.onOpen}
      className={cx(
        "group flex flex-col overflow-hidden rounded-lg border border-line bg-surface text-left",
        "transition-[border-color,translate,box-shadow] duration-[var(--motion-base)]",
        "hover:-translate-y-0.5 hover:border-brand-2/50 hover:shadow-e2",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus",
      )}
    >
      <span
        aria-hidden
        className="world-art relative h-[104px] w-full"
        style={worldArt(w.world_id)}
      >
        <span className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-surface to-transparent" />
      </span>

      <span className="flex min-w-0 flex-col gap-1 p-3.5">
        <span className="flex items-center justify-between gap-2">
          <span className="truncate text-sm font-semibold">
            {w.name || shortId(w.world_id, 10, 6)}
          </span>
          <Pill tone={pill.tone}>{pill.label}</Pill>
        </span>
        <span className="text-xs text-faint">
          {/* Two facts, both about this node's relationship to the world — which is the only thing
              that differs between the cards in front of you. Everything a peer could say about the
              world itself is the same on every peer and therefore tells you nothing here. */}
          {w.game_endpoint ? "A game is running" : "No game running"}
          {" · "}
          {w.piece_count === 0
            ? "nothing stored yet"
            : `${formatBytes(heldBytes(w))} stored here`}
        </span>
      </span>
    </button>
  );
}
