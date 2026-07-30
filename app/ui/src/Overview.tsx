// Overview — what this node is doing right now.
//
// One subject: the peer. Identity, reachability, throughput. No world lists (those are Worlds), no
// peer table (that is Peers), no log (that is Console). Every value here comes from the worker's
// own state snapshot; where it has not reported one, the tile shows "—" rather than a zero.
import { FiActivity, FiDownloadCloud, FiPause, FiPlay, FiRadio, FiUploadCloud } from "react-icons/fi";
import { Card, KeyValue, Pill, Stat, STAT_GRID } from "./components";
import {
  UNKNOWN,
  formatBytes,
  formatPercent,
  formatPermille,
  formatRate,
  formatUptime,
  show,
  type Dashboard,
} from "./api";
import { useEffect, useState } from "react";
import { togglePause, fetchPauseReason, type SystemStats } from "./ipc";

export function OverviewScreen(props: { d: Dashboard; sys: SystemStats }) {
  const { d } = props;
  const known = d.link.has_data;
  const [pausing, setPausing] = useState(false);
  // Why transfers are paused, in the worker's own words. A node that quietly stops seeding on
  // mobile data or under the battery rules looks exactly like one that crashed; the reason is the
  // difference, and it is fetched only while paused so an idle node pays nothing for it.
  const [pauseReason, setPauseReason] = useState("");
  useEffect(() => {
    if (!d.node.transfers_paused) {
      setPauseReason("");
      return;
    }
    let alive = true;
    fetchPauseReason()
      .then((r) => alive && setPauseReason(r))
      .catch(() => alive && setPauseReason(""));
    return () => {
      alive = false;
    };
  }, [d.node.transfers_paused]);

  return (
    <div className="flex max-w-[1100px] flex-col gap-4 px-[26px] pt-5 pb-10">
      <div className={STAT_GRID}>
        <Stat
          label="Peers"
          value={known ? String(d.counts.peers) : UNKNOWN}
          sub={!known ? "waiting for your peer" : d.counts.peers === 0 ? "not meshed" : "exchanging data"}
          icon={<FiActivity />}
          tone={known && d.counts.peers === 0 ? "warn" : undefined}
        />
        <Stat
          label="Relay latency"
          value={show(d.discovery.relay_latency_ms, (ms) => `${ms} ms`)}
          sub={
            // "none configured" is a claim about the user's settings, and before the first snapshot
            // the empty list is simply the absence of an answer. Asserting it in warn tone on every
            // launch taught people to ignore the tile.
            !known
              ? "waiting for your peer"
              : d.discovery.relay_latency_ms === null
                ? d.discovery.rendezvous.length === 0
                  ? "none configured"
                  : "none reachable"
                : "rendezvous round trip"
          }
          icon={<FiRadio />}
          tone={known && d.discovery.relay_latency_ms === null ? "warn" : undefined}
        />
        <Stat
          label="Uploaded"
          value={known ? formatBytes(d.traffic.total_sent_bytes) : UNKNOWN}
          sub={show(d.traffic.up_bytes_per_sec, (r) => formatRate(r))}
          icon={<FiUploadCloud />}
          tone="up"
        />
        <Stat
          label="Downloaded"
          value={known ? formatBytes(d.traffic.total_received_bytes) : UNKNOWN}
          sub={show(d.traffic.down_bytes_per_sec, (r) => formatRate(r))}
          icon={<FiDownloadCloud />}
          tone="down"
        />
        <Stat
          label="Share ratio"
          value={show(d.traffic.share_ratio_permille, formatPermille)}
          sub={d.traffic.share_ratio_permille === null ? "nothing downloaded" : "up ÷ down"}
        />
        <Stat
          label="Stored for the network"
          value={known ? formatBytes(d.content.maintained_bytes) : UNKNOWN}
          sub={known ? `${d.content.maintained_pieces} pieces` : UNKNOWN}
        />
      </div>

      <Card
        title="This peer"
        right={
          <div className="flex items-center gap-2">
            {d.node.transfers_paused && <Pill tone="warn">Transfers paused</Pill>}
            <button
              className={BUTTON}
              disabled={pausing || !known}
              onClick={() => {
                setPausing(true);
                togglePause().finally(() => setPausing(false));
              }}
            >
              {d.node.transfers_paused ? (
                <>
                  <FiPlay aria-hidden className="inline" /> Resume
                </>
              ) : (
                <>
                  <FiPause aria-hidden className="inline" /> Pause transfers
                </>
              )}
            </button>
          </div>
        }
      >
        {d.node.transfers_paused && pauseReason && (
          <p className="pb-1.5 text-xs text-warn">{pauseReason}</p>
        )}
        <dl>
          <KeyValue label="Node" value={known ? d.node.node_id : UNKNOWN} mono />
          <KeyValue label="Address" value={known && d.node.self_route ? d.node.self_route : UNKNOWN} mono />
          <KeyValue label="Uptime" value={known ? formatUptime(d.node.uptime_seconds) : UNKNOWN} />
          <KeyValue label="Session role" value={known ? (d.node.is_gateway ? "Gateway" : "Member") : UNKNOWN} />
          <KeyValue label="Roles" value={d.node.roles.length ? d.node.roles.join(" · ") : UNKNOWN} />
          <KeyValue label="Client" value={d.node.client || UNKNOWN} />
          <KeyValue
            label="Pieces tracked"
            value={known ? d.content.tracked_pieces.toLocaleString() : UNKNOWN}
          />
          <KeyValue
            label="Availability"
            value={show(d.content.availability_permille, formatPercent)}
          />
        </dl>
      </Card>

      <Card title="Worker process">
        <dl>
          <KeyValue
            label="Version"
            value={d.link.worker_version || UNKNOWN}
            mono={Boolean(d.link.worker_version)}
          />
          <KeyValue label="Transport" value={d.link.transport === "none" ? UNKNOWN : d.link.transport} />
          <KeyValue label="Snapshots received" value={String(d.link.revision)} />
          <KeyValue label="Reconnects" value={String(d.link.reconnects)} />
          <KeyValue
            label="Memory"
            value={props.sys.worker_found ? formatBytes(props.sys.worker_rss_bytes) : UNKNOWN}
          />
          <KeyValue
            label="Process"
            value={props.sys.worker_found ? `pid ${props.sys.worker_pid}` : "not running"}
            mono={props.sys.worker_found}
          />
        </dl>
      </Card>
    </div>
  );
}

const BUTTON =
  "rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover disabled:opacity-50";
