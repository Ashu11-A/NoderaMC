// The Nodera companion app.
//
// Three screens behind an icon rail: Home (the worlds you share), a per-world detail view
// (Info · State · Peers · Trackers · Pieces), and Settings. Every number comes from the real peer
// worker over the control endpoint — see ipc.ts → metrics.rs → WorkerControlHandler.stateJson.
// A field the worker cannot answer renders as "—", never as a plausible zero.
import { useEffect, useMemo, useRef, useState } from "react";
import {
  FiHome,
  FiSettings,
  FiTerminal,
  FiUsers,
  FiUploadCloud,
  FiDownloadCloud,
  FiActivity,
  FiGlobe,
  FiChevronRight,
} from "react-icons/fi";
import { Card, Stat, Empty, Pill, cx, MONO, AVATAR, STAT_GRID } from "./components";
import { SettingsScreen } from "./Settings";
import { WorldScreen } from "./World";
import { useResolvedTheme } from "./theme";
import {
  fetchMetrics,
  onMetrics,
  fetchSystemStats,
  onSystemStats,
  fetchWorkerLogs,
  fetchSettings,
  formatBytes,
  formatRate,
  formatLatency,
  shortId,
  EMPTY_METRICS,
  EMPTY_SYSTEM,
  type Metrics,
  type SystemStats,
  type Settings as SettingsDoc,
  type WorldRow,
} from "./ipc";

type Screen = { name: "home" } | { name: "world"; id: string } | { name: "settings" };

export function App() {
  const [m, setM] = useState<Metrics>(EMPTY_METRICS);
  const [sys, setSys] = useState<SystemStats>(EMPTY_SYSTEM);
  const [settings, setSettings] = useState<SettingsDoc | null>(null);
  const [screen, setScreen] = useState<Screen>({ name: "home" });
  const [logsOpen, setLogsOpen] = useState(false);
  const rate = useNodeRate(m);

  useResolvedTheme(settings?.appearance.theme ?? "system");

  useEffect(() => {
    fetchMetrics().then(setM).catch(() => {});
    fetchSystemStats().then(setSys).catch(() => {});
    fetchSettings().then(setSettings).catch(() => {});
    const un = onMetrics(setM);
    const unSys = onSystemStats(setSys);
    return () => {
      un.then((f) => f()).catch(() => {});
      unSys.then((f) => f()).catch(() => {});
    };
  }, []);

  // "Only their own worlds": a node also holds copies of other people's worlds to keep them alive,
  // and listing those on Home would answer a question nobody asked ("what am I storing?") in place
  // of the one they did ("what have I shared?"). They stay reachable from the world detail view.
  const myWorlds = useMemo(
    () => m.connected_worlds.filter((w) => !w.seeding),
    [m.connected_worlds],
  );
  const selected =
    screen.name === "world" ? m.connected_worlds.find((w) => w.world_id === screen.id) : undefined;

  // The relay is the rendezvous service: the thing that makes this node reachable from behind a
  // router. Its latency is the number worth surfacing on Home.
  const relay = m.rendezvous.find((r) => r.reachable) ?? m.rendezvous[0];

  return (
    <div className="grid h-full grid-cols-[60px_1fr]">
      <aside className="flex flex-col items-center gap-1.5 border-r border-line bg-rail py-3">
        <div className="mb-2.5 grid h-[34px] w-[34px] place-items-center" title="NoderaMC">
          <span
            className="h-[26px] w-[26px] rounded-sm bg-brand shadow-[0_0_18px_rgba(168,85,247,0.45)]"
            aria-hidden
          />
        </div>
        <nav className="flex flex-1 flex-col gap-1.5">
          <RailButton
            icon={<FiHome />}
            label="Home"
            active={screen.name === "home" || screen.name === "world"}
            onClick={() => setScreen({ name: "home" })}
          />
          <RailButton
            icon={<FiSettings />}
            label="Settings"
            active={screen.name === "settings"}
            onClick={() => setScreen({ name: "settings" })}
          />
        </nav>
        <div className="mt-auto">
          <RailButton
            icon={<FiTerminal />}
            label="Worker log"
            active={logsOpen}
            onClick={() => setLogsOpen((v) => !v)}
          />
        </div>
      </aside>

      <div className="flex min-h-0 min-w-0 flex-col">
        <TopBar m={m} rate={rate} />

        <div className="min-h-0 flex-1 overflow-y-auto">
          {screen.name === "settings" ? (
            <SettingsScreen settings={settings} onChange={setSettings} />
          ) : selected ? (
            <WorldScreen world={selected} m={m} onBack={() => setScreen({ name: "home" })} />
          ) : (
            <Home
              worlds={myWorlds}
              m={m}
              sys={sys}
              rate={rate}
              relayLatency={relay ? relay.latency_ms : -1}
              onOpen={(id) => setScreen({ name: "world", id })}
            />
          )}
        </div>

        {logsOpen && <WorkerLogs onClose={() => setLogsOpen(false)} />}
      </div>
    </div>
  );
}

function RailButton(props: {
  icon: JSX.Element;
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={cx(
        "relative grid h-[38px] w-[38px] place-items-center rounded-sm text-[17px]",
        "transition-colors duration-150 hover:bg-surface-hover hover:text-text",
        props.active ? "bg-surface-hover text-text" : "text-faint",
      )}
      onClick={props.onClick}
      title={props.label}
      aria-label={props.label}
    >
      {/* The selection bar bleeds into the rail's padding, so it hangs outside the button box. */}
      {props.active && (
        <span
          aria-hidden
          className="absolute top-2 bottom-2 -left-3 w-[3px] rounded-r-[3px] bg-brand"
        />
      )}
      {props.icon}
    </button>
  );
}

/* ---------------------------------------------------------------------------------------- chrome */

function TopBar(props: { m: Metrics; rate: NodeRate }) {
  const { m, rate } = props;
  const online = m.daemon_up;
  // A worker that is up but has met nobody is not "connected to the network" in any useful sense —
  // it is a session of one, which is what the game reports as DEGRADED. Printing CONNECTED over
  // that is how a dashboard hides the single most important thing it could tell you.
  const meshed = m.peers.length > 0;
  return (
    <header className="flex flex-wrap items-center justify-between gap-4 border-b border-line bg-surface px-5 py-2.5">
      <div className="flex min-w-0 items-center gap-2.5">
        <Pill tone={!online ? "down" : meshed ? "up" : "warn"}>
          {!online ? "Worker offline" : meshed ? "Connected" : "Alone"}
        </Pill>
        <span className={cx(MONO, "text-faint")} title={m.node_id}>
          {shortId(m.node_id, 8, 4)}
        </span>
        {m.is_gateway && (
          <span className="rounded-full border border-brand-2/55 px-[7px] py-0.5 text-[10px] tracking-[0.06em] text-brand-2">
            GATEWAY
          </span>
        )}
      </div>
      <div className="flex gap-4 font-mono text-sm tabular-nums">
        <span className="inline-flex items-center gap-1.5 text-up">
          <FiUploadCloud aria-hidden /> {formatRate(rate.up)}
        </span>
        <span className="inline-flex items-center gap-1.5 text-down">
          <FiDownloadCloud aria-hidden /> {formatRate(rate.down)}
        </span>
      </div>
    </header>
  );
}

/* ------------------------------------------------------------------------------------------ home */

function Home(props: {
  worlds: WorldRow[];
  m: Metrics;
  sys: SystemStats;
  rate: NodeRate;
  relayLatency: number;
  onOpen: (id: string) => void;
}) {
  const { worlds, m, rate, relayLatency } = props;
  const players = worlds.reduce((total, w) => total + w.players, 0);

  return (
    <div className="flex max-w-[1200px] flex-col gap-5 px-[26px] pt-6 pb-10">
      <section>
        <h1 className="text-[22px] tracking-[-0.01em]">Your worlds</h1>
        <p className="mt-1 max-w-[62ch] text-dim">
          Worlds you have shared with the NoderaMC network. They stay online here even after you
          close the game.
        </p>
      </section>

      <div className={STAT_GRID}>
        <Stat
          label="Shared worlds"
          value={String(worlds.length)}
          sub="hosted by you"
          icon={<FiGlobe />}
        />
        <Stat
          label="Active players"
          value={String(players)}
          sub="across your worlds"
          icon={<FiUsers />}
        />
        <Stat
          label="Peers"
          value={String(m.peers.length)}
          sub="exchanging data now"
          icon={<FiActivity />}
          tone={m.peers.length === 0 ? "warn" : undefined}
        />
        <Stat
          label="Relay latency"
          value={formatLatency(relayLatency)}
          sub={relayLatency < 0 ? "no rendezvous reachable" : "rendezvous handshake"}
          icon={<FiActivity />}
          tone={relayLatency < 0 ? "warn" : undefined}
        />
        <Stat
          label="Uploaded"
          value={formatBytes(m.total_sent_bytes)}
          sub={`${formatRate(rate.up)} now`}
          icon={<FiUploadCloud />}
          tone="up"
        />
        <Stat
          label="Downloaded"
          value={formatBytes(m.total_received_bytes)}
          sub={`${formatRate(rate.down)} now`}
          icon={<FiDownloadCloud />}
          tone="down"
        />
      </div>

      {worlds.length === 0 ? (
        <Empty icon={<FiGlobe />} title="No worlds shared yet">
          Open a world in Minecraft and press <b>Share to Nodera</b> in the pause menu. It appears
          here and stays on the network even after you close the game.
        </Empty>
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(310px,1fr))] gap-3.5">
          {worlds.map((w) => (
            <WorldCard key={w.world_id} world={w} onOpen={() => props.onOpen(w.world_id)} />
          ))}
        </div>
      )}

      {props.m.connected_worlds.some((w) => w.seeding) && (
        <Card
          title="Held for the network"
          hint="Copies of other people's worlds this node keeps alive. They are not yours and are not listed above."
        >
          <div className="flex flex-col">
            {props.m.connected_worlds
              .filter((w) => w.seeding)
              .map((w) => (
                <button
                  key={w.world_id}
                  className="flex items-center gap-2.5 border-b border-line-soft px-1 py-[9px] text-left text-dim hover:text-text"
                  onClick={() => props.onOpen(w.world_id)}
                >
                  <span className={cx(AVATAR, "h-[26px] w-[26px] text-[12px]")} aria-hidden>
                    {(w.name || "?").slice(0, 1).toUpperCase()}
                  </span>
                  <span className="min-w-0 flex-1 truncate">{w.name || shortId(w.world_id)}</span>
                  <span className={cx(MONO, "text-faint")}>
                    {w.pieces_held}/{w.piece_count} pieces · {formatBytes(w.total_bytes)}
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

function WorldCard(props: { world: WorldRow; onOpen: () => void }) {
  const w = props.world;
  const complete = w.piece_count > 0 && w.pieces_held >= w.piece_count;
  return (
    <button
      className={cx(
        "flex flex-col gap-3 rounded-lg border border-line bg-surface p-4 text-left",
        // `translate`, not `transform`: the hover lift is a translate utility in v4, and naming
        // the wrong property is how a transition silently becomes a jump.
        "transition-[border-color,translate,box-shadow] duration-150",
        "hover:-translate-y-0.5 hover:border-[color-mix(in_srgb,var(--brand-2)_55%,var(--line))] hover:shadow-card",
      )}
      onClick={props.onOpen}
    >
      <div className="flex items-center gap-2.5">
        <span className={cx(AVATAR, "h-[38px] w-[38px] text-[16px]")} aria-hidden>
          {(w.name || "?").slice(0, 1).toUpperCase()}
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="truncate font-semibold">{w.name || shortId(w.world_id)}</span>
          <span className="font-mono text-[11px] text-faint">v{w.version}</span>
        </div>
        <Pill tone={w.mc_route ? "up" : "muted"}>{w.mc_route ? "Joinable" : "Game closed"}</Pill>
      </div>

      <dl className="grid grid-cols-4 gap-2 border-y border-line-soft py-2.5">
        <div>
          <dt className="flex items-center gap-1 text-[10px] tracking-[0.05em] text-faint uppercase">
            <FiUsers aria-hidden /> Players
          </dt>
          <dd className="mt-0.5 text-[15px] tabular-nums">{w.players}</dd>
        </div>
        <div>
          <dt className="flex items-center gap-1 text-[10px] tracking-[0.05em] text-faint uppercase">
            <FiActivity aria-hidden /> Peers
          </dt>
          <dd className="mt-0.5 text-[15px] tabular-nums">{w.seeders}</dd>
        </div>
        <div>
          <dt className="flex items-center gap-1 text-[10px] tracking-[0.05em] text-faint uppercase">
            Size
          </dt>
          <dd className="mt-0.5 text-[15px] tabular-nums">{formatBytes(w.total_bytes)}</dd>
        </div>
        <div>
          <dt className="flex items-center gap-1 text-[10px] tracking-[0.05em] text-faint uppercase">
            Pieces
          </dt>
          <dd className={cx("mt-0.5 text-[15px] tabular-nums", complete ? "text-up" : "text-warn")}>
            {w.pieces_held}/{w.piece_count}
          </dd>
        </div>
      </dl>

      <div className="flex items-center justify-between text-faint">
        <span className={MONO} title={w.checksum}>
          {w.checksum ? shortId(w.checksum, 10, 6) : "—"}
        </span>
        <FiChevronRight aria-hidden />
      </div>
    </button>
  );
}

/* ------------------------------------------------------------------------------------------ rate */

export interface NodeRate {
  up: number;
  down: number;
}

/**
 * The node's live throughput, differenced from its own lifetime byte counters.
 *
 * Summing the per-peer rates looks equivalent and is not: per-peer rows exist only for peers this
 * node has *meshed* with, so a worker seeding a world archive into the swarm — genuinely moving
 * megabytes — displayed a flat 0 B/s whenever the membership view was empty. That is the state a
 * dashboard most needs to render truthfully, because it is the state you go looking at it for.
 *
 * Snapshots arrive on a ~1 s cadence, but the elapsed time is measured rather than assumed, so a
 * late or dropped event scales its delta correctly instead of showing a phantom spike.
 */
function useNodeRate(m: Metrics): NodeRate {
  const previous = useRef<{ sent: number; received: number; at: number } | null>(null);
  const [rate, setRate] = useState<NodeRate>({ up: 0, down: 0 });

  useEffect(() => {
    // The UI mounts on EMPTY_METRICS — all zeros — before the first reply arrives. Treating that
    // placeholder as a measurement makes a worker's whole lifetime counter look like one second of
    // traffic: a node that had received 3.4 GB since boot rendered "3393.9 MB/s" on its first
    // frame. A snapshot without a node_id is the absence of data, not data.
    if (!m.node_id) return;

    const now = Date.now();
    const last = previous.current;
    previous.current = { sent: m.total_sent_bytes, received: m.total_received_bytes, at: now };
    if (!last) return; // the first real snapshot is a baseline; a rate needs two

    const seconds = (now - last.at) / 1000;
    if (seconds <= 0) return;
    // Counters only ever climb; a decrease means the worker restarted and reset them, which would
    // otherwise read as a huge negative delta.
    const up = Math.max(0, m.total_sent_bytes - last.sent);
    const down = Math.max(0, m.total_received_bytes - last.received);
    setRate({ up: Math.round(up / seconds), down: Math.round(down / seconds) });
  }, [m.node_id, m.total_sent_bytes, m.total_received_bytes]);

  return rate;
}

/* ------------------------------------------------------------------------------------------ logs */

function WorkerLogs(props: { onClose: () => void }) {
  const [lines, setLines] = useState<string[]>([]);
  const boxRef = useRef<HTMLPreElement | null>(null);
  const followRef = useRef(true);

  useEffect(() => {
    let alive = true;
    const pull = () => {
      fetchWorkerLogs()
        .then((l) => alive && setLines(l))
        .catch(() => {});
    };
    pull();
    const timer = window.setInterval(pull, 2000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    const box = boxRef.current;
    if (box && followRef.current) box.scrollTop = box.scrollHeight;
  }, [lines]);

  return (
    <section className="flex-none border-t border-line bg-surface">
      <header className="flex items-center gap-2.5 px-4 py-2 text-[11px] tracking-[0.07em] text-dim uppercase">
        <span>Worker log</span>
        <span className="flex-1 tracking-normal normal-case">
          {lines.length > 0 ? `last ${lines.length} lines` : "waiting for output"}
        </span>
        <button className="text-[11px] text-brand-3" onClick={props.onClose}>
          Hide
        </button>
      </header>
      <pre
        className="max-h-[200px] overflow-auto px-4 pt-2 pb-3.5 font-mono text-[11px] leading-[1.5] break-words whitespace-pre-wrap text-dim"
        ref={boxRef}
        onScroll={() => {
          const box = boxRef.current;
          if (!box) return;
          followRef.current = box.scrollHeight - box.scrollTop - box.clientHeight < 24;
        }}
      >
        {lines.length > 0 ? lines.join("\n") : "No worker output yet."}
      </pre>
    </section>
  );
}
