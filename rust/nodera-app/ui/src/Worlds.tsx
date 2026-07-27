// Worlds — what this machine puts on the network.
//
// Three lists, in the order a player cares about them: worlds open to LAN right now (the only one
// with a decision attached), worlds you administer, worlds you support for other people. Node
// diagnostics live on Overview; other people's worlds live on Network.
import { useMemo } from "react";
import { FiChevronRight, FiGlobe, FiKey, FiUsers } from "react-icons/fi";
import { AVATAR, Card, Empty, MONO, Pill, Stat, STAT_GRID, cx } from "./components";
import { LanCard } from "./Lan";
import {
  UNKNOWN,
  formatBytes,
  formatPercent,
  shortId,
  show,
  type Dashboard,
  type World,
} from "./api";

export function WorldsScreen(props: { d: Dashboard; onOpen: (id: string) => void }) {
  const { d } = props;
  const mine = useMemo(() => d.worlds.filter((w) => w.administered), [d.worlds]);
  const supported = useMemo(() => d.worlds.filter((w) => !w.administered), [d.worlds]);
  const known = d.link.has_data;

  return (
    <div className="flex max-w-[1150px] flex-col gap-4 px-[26px] pt-5 pb-10">
      <div className={STAT_GRID}>
        <Stat
          label="You administer"
          value={known ? String(d.counts.administered_worlds) : UNKNOWN}
          icon={<FiKey />}
        />
        <Stat
          label="You support"
          value={known ? String(d.counts.supported_worlds) : UNKNOWN}
          icon={<FiGlobe />}
        />
        <Stat
          label="Players"
          value={known ? String(d.counts.players) : UNKNOWN}
          sub="in your worlds"
          icon={<FiUsers />}
        />
        <Stat
          label="Open to LAN"
          value={known && d.lan.supported ? String(d.lan.sessions.length) : UNKNOWN}
          sub={
            !known
              ? "waiting for your peer"
              : d.lan.supported
                ? "on this machine"
                : "detection unavailable"
          }
        />
      </div>

      <LanCard lan={d.lan} known={known} onChanged={() => undefined} />

      <Card title="Worlds you administer">
        {mine.length === 0 ? (
          <Empty icon={<FiKey />} title={known ? "None yet" : "Waiting for your peer"} />
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3 py-1">
            {mine.map((w) => (
              <WorldCard key={w.world_id} world={w} onOpen={() => props.onOpen(w.world_id)} />
            ))}
          </div>
        )}
      </Card>

      {supported.length > 0 && (
        <Card title="Worlds you support">
          <div className="flex flex-col">
            {supported.map((w) => (
              <button
                key={w.world_id}
                className="flex items-center gap-2.5 border-b border-line-soft px-1 py-[9px] text-left text-dim last:border-b-0 hover:text-text"
                onClick={() => props.onOpen(w.world_id)}
              >
                <span className={cx(AVATAR, "h-[26px] w-[26px] text-[12px]")} aria-hidden>
                  {(w.name || "?").slice(0, 1).toUpperCase()}
                </span>
                <span className="min-w-0 flex-1 truncate">{w.name || shortId(w.world_id)}</span>
                {w.hosting && <Pill tone="muted">Hosting</Pill>}
                <span className={cx(MONO, "text-faint")}>
                  {show(w.completeness_permille, formatPercent)} · {formatBytes(w.total_bytes)}
                </span>
                <FiChevronRight aria-hidden />
              </button>
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}

function WorldCard(props: { world: World; onOpen: () => void }) {
  const w = props.world;
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
          <span className={cx(MONO, "text-faint")}>v{w.version}</span>
        </div>
        <Pill tone={w.game_endpoint ? "up" : "muted"}>
          {w.game_endpoint ? "Game open" : "Game closed"}
        </Pill>
      </div>

      <dl className="grid grid-cols-4 gap-2 border-y border-line-soft py-2">
        <Cell label="Players" value={String(w.players)} />
        <Cell label="Holders" value={String(w.seeders)} />
        <Cell label="Size" value={formatBytes(w.total_bytes)} />
        <Cell
          label="Complete"
          value={show(w.completeness_permille, formatPercent)}
          tone={w.completeness_permille === null ? undefined : complete ? "up" : "warn"}
        />
      </dl>

      <div className="flex items-center justify-between gap-2 text-faint">
        <span className={MONO} title={w.checksum}>
          {w.checksum ? shortId(w.checksum, 10, 6) : UNKNOWN}
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
