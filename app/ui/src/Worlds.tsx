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
import { FiChevronRight, FiGlobe, FiKey, FiPlay, FiUploadCloud } from "react-icons/fi";
import { AVATAR, Card, Empty, MONO, Pill, Stat, STAT_GRID, cx } from "./components";
import { LanCard } from "./Lan";
import {
  UNKNOWN,
  formatBytes,
  formatPercent,
  heldBytes,
  shortId,
  show,
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
    <div className="flex max-w-[1150px] flex-col gap-4 px-[26px] pt-5 pb-10">
      <div className={STAT_GRID}>
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

      <LanCard lan={d.lan} known={known} onChanged={() => undefined} />
    </div>
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
        <div className="grid grid-cols-[repeat(auto-fill,minmax(310px,1fr))] gap-3 py-1">
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

function WorldCard(props: { world: World; onOpen: () => void }) {
  const w = props.world;
  const role = worldRole(w);
  const pill = ROLE_PILL[role];
  const complete = w.completeness_permille !== null && w.completeness_permille >= 1000;

  return (
    <button
      className={cx(
        "flex flex-col gap-3 rounded-lg border border-line bg-surface p-3.5 text-left",
        "transition-[border-color,translate,box-shadow] duration-150",
        "hover:-translate-y-0.5 hover:border-[color-mix(in_srgb,var(--brand-2)_55%,var(--line))] hover:shadow-card",
      )}
      onClick={props.onOpen}
    >
      <div className="flex items-center gap-2.5">
        <span className={cx(AVATAR, "h-[34px] w-[34px] text-[15px]")} aria-hidden>
          {(w.name || "?").slice(0, 1).toUpperCase()}
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="truncate font-semibold">{w.name || shortId(w.world_id)}</span>
          <span className={cx(MONO, "text-faint")}>
            v{w.version} · {formatBytes(w.total_bytes)}
          </span>
        </div>
        <Pill tone={pill.tone}>{pill.label}</Pill>
      </div>

      {/* The contribution bar. A number alone ("74.0%") says how much of the world is here; the bar
          says it at a glance and, next to it, how much of that is bytes this node can actually
          serve to somebody else. Both are the same fact — one is readable while scrolling. */}
      <div className="flex flex-col gap-1">
        <div className="flex items-baseline justify-between gap-2 text-[11px]">
          <span className="text-faint">
            {w.completeness_permille === null
              ? "No manifest yet"
              : `${w.pieces_held} of ${w.piece_count} pieces held`}
          </span>
          <span className={cx("tabular-nums", complete ? "text-up" : "text-warn")}>
            {show(w.completeness_permille, formatPercent)}
          </span>
        </div>
        <div className="h-1.5 overflow-hidden rounded-full bg-surface-2" aria-hidden>
          <div
            className={cx("h-full rounded-full", complete ? "bg-up" : "bg-brand-2")}
            style={{ width: `${(w.completeness_permille ?? 0) / 10}%` }}
          />
        </div>
      </div>

      <dl className="grid grid-cols-3 gap-2 border-t border-line-soft pt-2">
        <Cell label="Players" value={show(w.players, String)} />
        <Cell label="Other holders" value={String(w.seeders)} />
        <Cell label="You serve" value={formatBytes(heldBytes(w))} />
      </dl>

      <div className="flex items-center justify-between gap-2 text-faint">
        {/* Unlisted outranks everything else this line could say: a world no tracker carries is
            one no peer can reach, however open its game is. */}
        <span
          className={cx(
            "text-[11px]",
            w.discoverable === false ? "text-down" : w.game_endpoint ? "text-up" : undefined,
          )}
        >
          {w.discoverable === false
            ? "On no tracker — nobody can find it"
            : w.game_endpoint
              ? "Joinable now"
              : "Nobody is hosting a game"}
        </span>
        <FiChevronRight aria-hidden />
      </div>
    </button>
  );
}

function Cell(props: { label: string; value: string; tone?: "up" | "warn" }) {
  return (
    <div>
      <dt className="text-[10px] tracking-[0.05em] text-faint uppercase">{props.label}</dt>
      <dd
        className={cx(
          "mt-0.5 text-[15px] tabular-nums",
          props.tone === "up" && "text-up",
          props.tone === "warn" && "text-warn",
        )}
      >
        {props.value}
      </dd>
    </div>
  );
}
