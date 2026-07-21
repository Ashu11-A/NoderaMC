// Task 32/33 dashboard — a Minecraft-launcher × VPN-client hybrid.
//
// The layout deliberately reads like a VPN app: a big central "connection" shield that flips between
// CONNECTED / OFFLINE, your node identity + advertised route (the "your IP" line), throughput meters,
// and a list of discovery relays (trackers + rendezvous) styled like a VPN server list with live
// green/red health dots. The Minecraft half is the hosted-world cards and the blocky, emerald-accented
// dark theme. All numbers come from the real worker over the control endpoint (see ipc.ts / metrics.rs).
import { useEffect, useState } from "react";
import {
  fetchMetrics,
  onMetrics,
  formatBytes,
  formatRate,
  formatUptime,
  shortId,
  EMPTY_METRICS,
  type Metrics,
  type EndpointHealth,
} from "./ipc";

export function App() {
  const [m, setM] = useState<Metrics>(EMPTY_METRICS);

  useEffect(() => {
    fetchMetrics().then(setM).catch(() => {});
    const un = onMetrics(setM);
    return () => {
      un.then((f) => f()).catch(() => {});
    };
  }, []);

  const online = m.daemon_up;

  return (
    <div className={`app ${online ? "is-online" : "is-offline"}`}>
      <TitleBar version={m.worker_version} online={online} />

      <main>
        <ConnectionHero m={m} />

        <section className="stat-row">
          <StatTile
            label="Chunks maintained"
            value={m.maintained_pieces.toLocaleString()}
            sub={`${formatBytes(m.maintained_bytes)} held for the network`}
            glyph="▦"
          />
          <StatTile
            label="Uploaded"
            value={formatBytes(m.total_sent_bytes)}
            sub="lifetime, this identity"
            glyph="▲"
            tone="up"
          />
          <StatTile
            label="Downloaded"
            value={formatBytes(m.total_received_bytes)}
            sub="lifetime, this identity"
            glyph="▼"
            tone="down"
          />
          <StatTile
            label="Peers"
            value={m.peers.length.toLocaleString()}
            sub="exchanging data now"
            glyph="◆"
          />
        </section>

        <div className="columns">
          <div className="col">
            <Worlds worlds={m.connected_worlds} />
            <Peers m={m} />
          </div>
          <div className="col side">
            <Relays title="Trackers" hint="world directory" rows={m.trackers} />
            <Relays title="Rendezvous" hint="NAT reach / relay" rows={m.rendezvous} />
          </div>
        </div>
      </main>
    </div>
  );
}

function TitleBar(props: { version: string; online: boolean }) {
  return (
    <header className="titlebar">
      <div className="brand">
        <span className="cube" aria-hidden />
        <span className="wordmark">NODERA</span>
      </div>
      <div className="titlebar-right">
        <span className={`status-pill ${props.online ? "up" : "down"}`}>
          <span className="dot" />
          {props.online ? "Node online" : "Node offline"}
        </span>
        <span className="version">{props.version ? `worker ${props.version}` : "worker —"}</span>
      </div>
    </header>
  );
}

function ConnectionHero(props: { m: Metrics }) {
  const { m } = props;
  const online = m.daemon_up;
  return (
    <section className={`hero ${online ? "up" : "down"}`}>
      <div className="shield-wrap">
        <div className="shield">
          <div className="shield-glow" />
          <span className="shield-glyph">{online ? "⛨" : "⛉"}</span>
        </div>
      </div>

      <div className="hero-body">
        <div className="hero-state">{online ? "CONNECTED" : "DISCONNECTED"}</div>
        <div className="hero-sub">
          {online
            ? "This machine is a live peer on the Nodera network."
            : "The peer worker is not answering. Nothing is being served."}
        </div>

        <dl className="identity">
          <div>
            <dt>Node ID</dt>
            <dd className="mono" title={m.node_id}>
              {shortId(m.node_id, 10, 6)}
            </dd>
          </div>
          <div>
            <dt>Advertised route</dt>
            <dd className="mono">{m.self_route || "—"}</dd>
          </div>
          <div>
            <dt>Uptime</dt>
            <dd>{formatUptime(m.uptime_seconds)}</dd>
          </div>
        </dl>

        <div className="roles">
          {m.is_gateway && <span className="chip gateway">GATEWAY</span>}
          {m.roles.map((r) => (
            <span className="chip" key={r}>
              {r.replace(/_/g, " ")}
            </span>
          ))}
          {m.roles.length === 0 && !m.is_gateway && <span className="chip muted">no roles</span>}
        </div>
      </div>
    </section>
  );
}

function StatTile(props: {
  label: string;
  value: string;
  sub: string;
  glyph: string;
  tone?: "up" | "down";
}) {
  return (
    <div className={`tile ${props.tone ?? ""}`}>
      <span className="tile-glyph" aria-hidden>
        {props.glyph}
      </span>
      <div className="tile-body">
        <div className="tile-label">{props.label}</div>
        <div className="tile-value">{props.value}</div>
        <div className="tile-sub">{props.sub}</div>
      </div>
    </div>
  );
}

function Worlds(props: { worlds: Metrics["connected_worlds"] }) {
  return (
    <section className="card">
      <div className="card-head">
        <h2>Hosted worlds</h2>
        <span className="count">{props.worlds.length}</span>
      </div>
      {props.worlds.length === 0 ? (
        <div className="empty">
          No worlds shared right now. Press <b>Share to Nodera</b> in a Minecraft world and it will
          stay online here — even after you close the game.
        </div>
      ) : (
        <div className="world-grid">
          {props.worlds.map((w) => (
            <div className="world-card" key={w.world_id}>
              <div className="world-thumb" aria-hidden>
                {w.name.slice(0, 1).toUpperCase()}
              </div>
              <div className="world-meta">
                <div className="world-name">{w.name}</div>
                <div className="world-id mono">{shortId(w.world_id, 8, 6)}</div>
              </div>
              <div className="world-players">
                <span className="pnum">{w.players}</span>
                <span className="plabel">online</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function Peers(props: { m: Metrics }) {
  const peers = props.m.peers;
  return (
    <section className="card">
      <div className="card-head">
        <h2>Connected peers</h2>
        <span className="count">{peers.length}</span>
      </div>
      {peers.length === 0 ? (
        <div className="empty">No peers exchanging data yet.</div>
      ) : (
        <table className="peers">
          <thead>
            <tr>
              <th>Node</th>
              <th>Path</th>
              <th className="num">↑</th>
              <th className="num">↓</th>
            </tr>
          </thead>
          <tbody>
            {peers.map((p) => (
              <tr key={p.node_id}>
                <td className="mono" title={p.route || p.node_id}>
                  {shortId(p.node_id, 8, 4)}
                </td>
                <td>
                  <span className={`path ${p.path}`}>{p.path}</span>
                </td>
                <td className="num">{formatRate(p.up_bytes_per_sec)}</td>
                <td className="num">{formatRate(p.down_bytes_per_sec)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function Relays(props: { title: string; hint: string; rows: EndpointHealth[] }) {
  const up = props.rows.filter((r) => r.reachable).length;
  return (
    <section className="card">
      <div className="card-head">
        <h2>{props.title}</h2>
        <span className="count">
          {up}/{props.rows.length}
        </span>
      </div>
      <div className="card-hint">{props.hint}</div>
      {props.rows.length === 0 ? (
        <div className="empty small">None configured.</div>
      ) : (
        <ul className="relay-list">
          {props.rows.map((r) => (
            <li key={`${r.host}:${r.port}`} className={r.reachable ? "up" : "down"}>
              <span className="relay-dot" />
              <span className="relay-addr mono">
                {r.host}:{r.port}
              </span>
              <span className="relay-state">{r.reachable ? "reachable" : "unreachable"}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
