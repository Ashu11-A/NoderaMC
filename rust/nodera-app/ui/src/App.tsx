// Task 32 dashboard: the four required panels — maintained chunks/data, GB sent/received, peers
// exchanging data, and the world this node is connected to / hosting.
import { useEffect, useState } from "react";
import { fetchMetrics, onMetrics, formatGb, formatRate, type Metrics } from "./ipc";

const EMPTY: Metrics = {
  maintained_pieces: 0,
  maintained_bytes: 0,
  total_sent_bytes: 0,
  total_received_bytes: 0,
  peers: [],
  connected_worlds: [],
  daemon_up: false,
};

export function App() {
  const [m, setM] = useState<Metrics>(EMPTY);

  useEffect(() => {
    fetchMetrics().then(setM).catch(() => {});
    const un = onMetrics(setM);
    return () => {
      un.then((f) => f()).catch(() => {});
    };
  }, []);

  return (
    <div className="app">
      <header>
        <h1>Nodera</h1>
        <span className={m.daemon_up ? "dot up" : "dot down"}>
          {m.daemon_up ? "Node online" : "Node offline"}
        </span>
      </header>

      <section className="grid">
        <Panel title="Chunks maintained">
          <big>{m.maintained_pieces.toLocaleString()}</big>
          <small>{formatGb(m.maintained_bytes)} held for the network</small>
        </Panel>

        <Panel title="Data transferred">
          <big>↑ {formatGb(m.total_sent_bytes)}</big>
          <big>↓ {formatGb(m.total_received_bytes)}</big>
        </Panel>

        <Panel title="Connected world">
          {m.connected_worlds.length === 0 ? (
            <small>Not hosting or connected to a world</small>
          ) : (
            <ul>
              {m.connected_worlds.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          )}
        </Panel>
      </section>

      <section className="peers">
        <h2>Peers ({m.peers.length})</h2>
        <table>
          <thead>
            <tr>
              <th>Node</th>
              <th>Path</th>
              <th>↑</th>
              <th>↓</th>
            </tr>
          </thead>
          <tbody>
            {m.peers.map((p) => (
              <tr key={p.node_id}>
                <td title={p.route}>{p.node_id.slice(0, 12)}</td>
                <td>{p.path}</td>
                <td>{formatRate(p.up_bytes_per_sec)}</td>
                <td>{formatRate(p.down_bytes_per_sec)}</td>
              </tr>
            ))}
            {m.peers.length === 0 && (
              <tr>
                <td colSpan={4} className="empty">
                  No peers exchanging data yet
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}

function Panel(props: { title: string; children: React.ReactNode }) {
  return (
    <div className="panel">
      <h3>{props.title}</h3>
      <div className="panel-body">{props.children}</div>
    </div>
  );
}
