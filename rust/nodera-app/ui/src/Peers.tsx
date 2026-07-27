// Peers — who this node is connected to, and how it is findable.
//
// Both are node-wide facts, which is why they are here and not on a world's page: a peer is a
// connection this node holds, and a tracker is where this node announces. They used to be tabs
// under a selected world, where they claimed a per-world scope they never had.
import { FiRadio, FiUsers } from "react-icons/fi";
import { Card, Empty, MONO, Pill, Stat, STAT_GRID, Td, Th, Tr, cx } from "./components";
import {
  UNKNOWN,
  formatBytes,
  formatRate,
  formatSource,
  shortId,
  show,
  type Dashboard,
  type Endpoint,
} from "./api";

export function PeersScreen(props: { d: Dashboard }) {
  const { d } = props;
  const known = d.link.has_data;
  const reachableTrackers = d.discovery.trackers.filter((t) => t.reachable).length;
  const reachableRendezvous = d.discovery.rendezvous.filter((r) => r.reachable).length;

  return (
    <div className="flex max-w-[1100px] flex-col gap-4 px-[26px] pt-5 pb-10">
      <div className={STAT_GRID}>
        {/* `counts.peers` rather than `peers.length`: Overview reads the first and this read the
            second, so one screen could disagree with the other about the same fact. `Counts` exists
            to be the single answer. */}
        <Stat label="Connected peers" value={known ? String(d.counts.peers) : UNKNOWN} icon={<FiUsers />} />
        <Stat
          label="Trackers"
          value={
            // "0/0 reachable" in neutral tone reads as healthy. Nothing configured is a different
            // state from everything down, and only one of them is fixed by starting a tracker.
            !known ? UNKNOWN
              : d.discovery.trackers.length === 0 ? UNKNOWN
              : `${reachableTrackers}/${d.discovery.trackers.length}`
          }
          sub={known && d.discovery.trackers.length === 0 ? "none configured" : "reachable"}
          icon={<FiRadio />}
          tone={known && reachableTrackers === 0 && d.discovery.trackers.length > 0 ? "warn" : undefined}
        />
        <Stat
          label="Rendezvous"
          value={
            !known ? UNKNOWN
              : d.discovery.rendezvous.length === 0 ? UNKNOWN
              : `${reachableRendezvous}/${d.discovery.rendezvous.length}`
          }
          sub={known && d.discovery.rendezvous.length === 0 ? "none configured" : "reachable"}
          icon={<FiRadio />}
          tone={known && reachableRendezvous === 0 && d.discovery.rendezvous.length > 0 ? "warn" : undefined}
        />
        <Stat
          label="Best relay"
          value={known ? show(d.discovery.relay_latency_ms, (ms) => `${ms} ms`) : UNKNOWN}
        />
      </div>

      <Card title="Peers">
        {d.peers.length === 0 ? (
          <Empty icon={<FiUsers />} title={known ? "No peers" : "Waiting for your peer"} />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-sm">
              <thead>
                <Tr>
                  <Th>Peer</Th>
                  <Th>Address</Th>
                  <Th>Path</Th>
                  <Th>Client</Th>
                  <Th num>Up</Th>
                  <Th num>Down</Th>
                  <Th num>Total up</Th>
                  <Th num>Total down</Th>
                </Tr>
              </thead>
              <tbody>
                {d.peers.map((p) => (
                  <Tr key={`${p.node_id}:${p.route}`}>
                    <Td mono title={p.node_id}>
                      {shortId(p.node_id)}
                    </Td>
                    <Td mono>{formatSource(p.route)}</Td>
                    <Td>{p.path}</Td>
                    <Td>{p.client || UNKNOWN}</Td>
                    <Td num>{formatRate(p.up_bytes_per_sec)}</Td>
                    <Td num>{formatRate(p.down_bytes_per_sec)}</Td>
                    <Td num>{formatBytes(p.total_up_bytes)}</Td>
                    <Td num>{formatBytes(p.total_down_bytes)}</Td>
                  </Tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <EndpointCard title="Trackers" rows={d.discovery.trackers} known={known} />
      <EndpointCard title="Rendezvous" rows={d.discovery.rendezvous} known={known} />
    </div>
  );
}

function EndpointCard(props: {
  title: string;
  rows: Endpoint[];
  /** Whether a snapshot has arrived — "none configured" is a claim about the user's settings. */
  known: boolean;
}) {
  const known = props.known;
  return (
    <Card title={props.title}>
      {props.rows.length === 0 ? (
        <Empty icon={<FiRadio />} title={known ? "None configured" : "Waiting for your peer"} />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-sm">
            <thead>
              <Tr>
                <Th>Endpoint</Th>
                <Th>Status</Th>
                <Th num>Latency</Th>
              </Tr>
            </thead>
            <tbody>
              {props.rows.map((e) => (
                <Tr key={`${e.scheme}://${e.host}:${e.port}`}>
                  <Td mono>{`${e.scheme || "tcp"}://${e.host}:${e.port}`}</Td>
                  <Td>
                    <Pill tone={e.reachable ? "up" : "down"}>
                      {e.reachable ? "Reachable" : "Unreachable"}
                    </Pill>
                  </Td>
                  {/* An unprobed endpoint has no latency; `0 ms` would claim a handshake that never
                      happened. */}
                  <Td num>
                    <span className={cx(MONO)}>{show(e.latency_ms, (ms) => `${ms} ms`)}</span>
                  </Td>
                </Tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
