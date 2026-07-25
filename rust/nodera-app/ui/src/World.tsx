// One shared world, in the five panes a torrent client puts under a selected item:
// Info · State · Peers · Trackers · Pieces.
import { useEffect, useState } from "react";
import { FiInfo, FiActivity, FiUsers, FiRadio, FiGrid, FiArrowLeft } from "react-icons/fi";
import {
  Card,
  KeyValue,
  Stat,
  Empty,
  Pill,
  Th,
  Td,
  Tr,
  cx,
  AVATAR,
  STAT_GRID,
} from "./components";
import {
  fetchPieceMap,
  formatBytes,
  formatRate,
  formatUptime,
  formatPermille,
  formatPercent,
  formatDate,
  formatSource,
  formatLatency,
  EMPTY_PIECE_MAP,
  type Metrics,
  type WorldRow,
  type PieceMapView,
} from "./ipc";

type Tab = "info" | "state" | "peers" | "trackers" | "pieces";

const TABS: { id: Tab; label: string; icon: JSX.Element }[] = [
  { id: "info", label: "Info", icon: <FiInfo /> },
  { id: "state", label: "State", icon: <FiActivity /> },
  { id: "peers", label: "Peers", icon: <FiUsers /> },
  { id: "trackers", label: "Trackers", icon: <FiRadio /> },
  { id: "pieces", label: "Pieces", icon: <FiGrid /> },
];

const COUNT = "font-mono text-xs text-faint";
const TABLE_SCROLL = "overflow-x-auto";
const TABLE = "w-full border-collapse text-sm";

export function WorldScreen(props: { world: WorldRow; m: Metrics; onBack: () => void }) {
  const { world, m } = props;
  const [tab, setTab] = useState<Tab>("info");

  return (
    <div className="flex min-h-full flex-col">
      <header className="flex items-center gap-3 px-[26px] pt-[18px] pb-3.5">
        <button
          className="grid h-8 w-8 place-items-center rounded-sm border border-line bg-surface hover:bg-surface-hover"
          onClick={props.onBack}
          aria-label="Back to worlds"
        >
          <FiArrowLeft />
        </button>
        <span className={cx(AVATAR, "h-[38px] w-[38px] text-[16px]")} aria-hidden>
          {(world.name || "?").slice(0, 1).toUpperCase()}
        </span>
        <div className="min-w-0 flex-1">
          <h1 className="text-[18px]">{world.name || "Unnamed world"}</h1>
          <p className="mt-px text-[12px] text-dim">
            {world.seeding ? "Seeding for the network" : "Shared by you"} · v{world.version} ·{" "}
            {formatBytes(world.total_bytes)}
          </p>
        </div>
        <Pill tone={world.mc_route ? "up" : "muted"}>
          {world.mc_route ? "Joinable now" : "Game closed"}
        </Pill>
      </header>

      <nav className="flex gap-1 border-b border-line px-[22px]" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            className={cx(
              "flex items-center gap-1.5 border-b-2 px-3.5 py-[9px] text-sm",
              tab === t.id
                ? "border-b-brand-2 text-text"
                : "border-b-transparent text-dim hover:text-text",
            )}
            onClick={() => setTab(t.id)}
          >
            {t.icon}
            {t.label}
          </button>
        ))}
      </nav>

      <div className="flex max-w-[1100px] flex-1 flex-col gap-3.5 px-[26px] pt-[18px] pb-10" role="tabpanel">
        {tab === "info" && <InfoTab world={world} />}
        {tab === "state" && <StateTab world={world} m={m} />}
        {tab === "peers" && <PeersTab m={m} />}
        {tab === "trackers" && <TrackersTab m={m} />}
        {tab === "pieces" && <PiecesTab world={world} />}
      </div>
    </div>
  );
}

function InfoTab(props: { world: WorldRow }) {
  const w = props.world;
  return (
    <Card title="World">
      <dl>
        <KeyValue label="World name" value={w.name || "—"} />
        <KeyValue label="Total size" value={formatBytes(w.total_bytes)} />
        <KeyValue
          label="Checksum"
          value={w.checksum || "—"}
          mono
          title="Manifest root — the content hash identifying these exact bytes, and the key every peer verifies a downloaded piece against."
        />
        <KeyValue label="World ID" value={w.world_id || "—"} mono />
        <KeyValue label="Date added" value={formatDate(w.added_at)} />
        <KeyValue label="Last updated" value={formatDate(w.updated_at)} />
        <KeyValue label="Content version" value={w.version ? `v${w.version}` : "—"} />
        <KeyValue label="Role on this node" value={w.seeding ? "Seeding" : "Hosting"} />
        <KeyValue
          label="Game endpoint"
          value={w.mc_route ? formatSource(w.mc_route) : "offline (host's game closed)"}
          mono={Boolean(w.mc_route)}
        />
        <KeyValue label="Players online" value={String(w.players)} />
      </dl>
    </Card>
  );
}

function StateTab(props: { world: WorldRow; m: Metrics }) {
  const { world, m } = props;
  return (
    <>
      <div className={STAT_GRID}>
        <Stat label="Peers" value={String(m.peers.length)} sub="exchanging data now" />
        <Stat
          label="Share ratio"
          value={formatPermille(m.share_ratio_permille)}
          sub="uploaded ÷ downloaded"
        />
        <Stat
          label="Availability"
          value={formatPercent(m.availability_permille)}
          sub="pieces verified present"
        />
        <Stat label="Total chunks" value={m.total_chunks.toLocaleString()} sub="pieces tracked" />
        <Stat
          label="Total download"
          value={formatBytes(m.total_received_bytes)}
          sub="lifetime, this identity"
          tone="down"
        />
        <Stat
          label="Total upload"
          value={formatBytes(m.total_sent_bytes)}
          sub="lifetime, this identity"
          tone="up"
        />
        <Stat label="Total uptime" value={formatUptime(m.uptime_seconds)} sub="this run" />
        <Stat
          label="Chunks maintained"
          value={m.maintained_pieces.toLocaleString()}
          sub={`${formatBytes(m.maintained_bytes)} held for the network`}
        />
      </div>
      <Card title="This world">
        <dl>
          <KeyValue label="Pieces held" value={`${world.pieces_held} / ${world.piece_count}`} />
          <KeyValue label="Peers sharing it" value={String(world.seeders)} />
          <KeyValue label="Session role" value={m.is_gateway ? "Gateway" : "Member"} />
          <KeyValue label="Advertised route" value={formatSource(m.self_route)} mono />
          <KeyValue label="Worker version" value={m.worker_version || "—"} />
        </dl>
      </Card>
    </>
  );
}

// The connection path comes off the wire, so it is looked up rather than interpolated into a class
// name: a value this table has never heard of renders neutral instead of unstyled.
const PATH_TONE: Record<string, string> = {
  direct: "border-up/45 text-up",
  relayed: "border-warn/45 text-warn",
};

function PeersTab(props: { m: Metrics }) {
  const peers = props.m.peers;
  if (peers.length === 0) {
    return (
      <Empty icon={<FiUsers />} title="No peers exchanging data">
        This node has not meshed with anyone yet. Peers appear here once a session forms.
      </Empty>
    );
  }
  return (
    <Card title="Connected peers" right={<span className={COUNT}>{peers.length}</span>}>
      <div className={TABLE_SCROLL}>
        <table className={TABLE}>
          <thead>
            <tr>
              <Th>Source</Th>
              <Th>Connection</Th>
              <Th>Client</Th>
              <Th num>↑ now</Th>
              <Th num>↓ now</Th>
              <Th num>↑ total</Th>
              <Th num>↓ total</Th>
            </tr>
          </thead>
          <tbody>
            {peers.map((p) => (
              <Tr key={p.node_id}>
                <Td mono title={p.node_id}>
                  {formatSource(p.route)}
                </Td>
                <Td>
                  <span
                    className={cx(
                      "rounded-full border px-[7px] py-px text-2xs uppercase",
                      PATH_TONE[p.path] ?? "border-line text-dim",
                    )}
                  >
                    {p.path || "unknown"}
                  </span>
                </Td>
                <Td>{p.client || "—"}</Td>
                <Td num>{formatRate(p.up_bytes_per_sec)}</Td>
                <Td num>{formatRate(p.down_bytes_per_sec)}</Td>
                <Td num>{formatBytes(p.total_up_bytes)}</Td>
                <Td num>{formatBytes(p.total_down_bytes)}</Td>
              </Tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function TrackersTab(props: { m: Metrics }) {
  const { m } = props;
  return (
    <>
      <EndpointTable
        title="Trackers"
        hint="World directory — who is in a world, and where to reach them."
        rows={m.trackers}
      />
      <EndpointTable
        title="Rendezvous"
        hint="NAT traversal — registers this node so peers behind routers can reach it, and relays when no direct path exists."
        rows={m.rendezvous}
      />
    </>
  );
}

function EndpointTable(props: { title: string; hint: string; rows: Metrics["trackers"] }) {
  const up = props.rows.filter((r) => r.reachable).length;
  return (
    <Card
      title={props.title}
      hint={props.hint}
      right={
        <span className={COUNT}>
          {up}/{props.rows.length}
        </span>
      }
    >
      {props.rows.length === 0 ? (
        <Empty title="None configured">Add one under Settings → Network.</Empty>
      ) : (
        <div className={TABLE_SCROLL}>
          <table className={TABLE}>
            <thead>
              <tr>
                <Th>Source</Th>
                <Th>Status</Th>
                <Th num>Latency</Th>
              </tr>
            </thead>
            <tbody>
              {props.rows.map((r) => (
                <Tr key={`${r.scheme}-${r.host}:${r.port}`}>
                  <Td mono>{`${r.scheme || "tcp"}://${r.host}:${r.port}`}</Td>
                  <Td>
                    <Pill tone={r.reachable ? "up" : "down"}>
                      {r.reachable ? "working" : "not working"}
                    </Pill>
                  </Td>
                  <Td num>{formatLatency(r.latency_ms)}</Td>
                </Tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

const SWATCH = "inline-block h-[9px] w-[9px] rounded-[2px]";

function PiecesTab(props: { world: WorldRow }) {
  const worldId = props.world.world_id;
  const [map, setMap] = useState<PieceMapView>(EMPTY_PIECE_MAP);

  useEffect(() => {
    if (!worldId) return;
    let alive = true;
    const pull = () => {
      fetchPieceMap(worldId)
        .then((m) => alive && setMap(m))
        .catch(() => {});
    };
    pull();
    const timer = window.setInterval(pull, 2000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [worldId]);

  if (map.piece_count === 0) {
    return (
      <Empty icon={<FiGrid />} title="No piece data yet">
        Pieces appear once this world's content has been packed and seeded to the network.
      </Empty>
    );
  }

  const healthy = map.held_count;
  const missing = map.piece_count - healthy;
  return (
    <>
      <Card
        title="Pieces"
        right={
          <span className="flex items-center gap-1.5 text-[11px] whitespace-nowrap text-faint">
            <span className={cx(SWATCH, "ml-2 bg-up")} /> healthy
            <span className={cx(SWATCH, "ml-2 bg-line")} /> missing
          </span>
        }
        hint={`${healthy.toLocaleString()} healthy · ${missing.toLocaleString()} missing · ${formatPercent(
          Math.round((healthy * 1000) / map.piece_count),
        )} complete`}
      >
        {/* Bounded height so a 10 000-piece world scrolls its own grid instead of pushing
            everything below it off the page. */}
        <div
          className="flex max-h-[280px] flex-wrap gap-0.5 overflow-y-auto py-1.5"
          role="img"
          aria-label={`${healthy} of ${map.piece_count} pieces held`}
        >
          {map.held.map((held, i) => (
            <span
              key={i}
              className={cx(
                "h-[9px] w-[9px] flex-none rounded-[2px]",
                held ? "bg-up" : "bg-line",
              )}
              title={`Piece ${i}: ${held ? "healthy" : "missing"}`}
            />
          ))}
        </div>
      </Card>
      <Card title="Manifest">
        <dl>
          <KeyValue label="Pieces" value={`${healthy} / ${map.piece_count}`} />
          <KeyValue label="Total size" value={formatBytes(map.total_bytes)} />
          <KeyValue label="Manifest root" value={map.manifest_root || "—"} mono />
          <KeyValue label="Version" value={map.version ? `v${map.version}` : "—"} />
          <KeyValue
            label="Peers sharing this world"
            value={String(map.holders.length)}
            title="Peers known to hold some of this world. A world with holders survives its host going offline."
          />
        </dl>
      </Card>
    </>
  );
}
