// Console — the Java peer worker's own output, and what the process is costing.
//
// Its own screen because a log drawer at the bottom of another page is where output goes to be
// ignored: it is too short to read, it covers the thing you were looking at, and it has nowhere to
// put the process facts that make the log make sense.
import { useEffect, useMemo, useRef, useState } from "react";
import { FiCopy, FiCpu, FiHardDrive, FiPause, FiPlay, FiRefreshCw, FiSave, FiSearch } from "react-icons/fi";
import { Card, Empty, MONO, Pill, Stat, STAT_GRID, cx } from "./components";
import { UNKNOWN, formatBytes, type Dashboard } from "./api";
import {
  fetchWorkerLogs,
  fetchWorkerOwnership,
  restartWorker,
  saveWorkerLogs,
  type SystemStats,
  type WorkerOwnership,
} from "./ipc";

/** How often the log ring is re-read. It is a buffer the supervisor appends to; it has no signal. */
const POLL_MILLIS = 1500;

/**
 * How many lines the console shows.
 *
 * A worker log scrolls faster than anyone reads, and a wall of it is what people scroll past rather
 * than look at. The tail is the part that explains what the process is doing right now, so that is
 * what is on screen — and Copy and Save deliberately take the WHOLE buffer, because the moment you
 * want a log is the moment you want more of it than fits.
 */
const VISIBLE_LINES = 30;

export function ConsoleScreen(props: { d: Dashboard; sys: SystemStats }) {
  const { sys } = props;
  const [lines, setLines] = useState<string[]>([]);
  const [filter, setFilter] = useState("");
  const [following, setFollowing] = useState(true);
  const [ownership, setOwnership] = useState<WorkerOwnership | null>(null);
  const [restarting, setRestarting] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const box = useRef<HTMLPreElement | null>(null);

  useEffect(() => {
    let alive = true;
    const pull = () =>
      fetchWorkerLogs()
        .then((l) => alive && setLines(l))
        .catch(() => {});
    pull();
    fetchWorkerOwnership().then(setOwnership).catch(() => {});
    const timer = window.setInterval(pull, POLL_MILLIS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  // Two different things, deliberately: `matching` is everything the filter admits and is what
  // Copy hands over; `shown` is the tail that is rendered.
  const matching = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    return needle ? lines.filter((line) => line.toLowerCase().includes(needle)) : lines;
  }, [lines, filter]);
  const shown = useMemo(() => matching.slice(-VISIBLE_LINES), [matching]);
  const hidden = matching.length - shown.length;

  useEffect(() => {
    if (following && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [shown, following]);

  const memoryShare =
    sys.worker_found && sys.mem_total_bytes > 0
      ? (sys.worker_rss_bytes / sys.mem_total_bytes) * 100
      : null;

  return (
    <div className="flex min-h-[560px] flex-col gap-4">
      <div className={STAT_GRID}>
        <Stat
          label="Worker memory"
          value={sys.worker_found ? formatBytes(sys.worker_rss_bytes) : UNKNOWN}
          sub={memoryShare === null ? "worker not running" : `${memoryShare.toFixed(1)}% of this machine`}
          icon={<FiHardDrive />}
          tone={sys.worker_found ? undefined : "warn"}
        />
        <Stat
          label="Worker CPU"
          value={sys.worker_found ? `${sys.worker_cpu_percent.toFixed(0)}%` : UNKNOWN}
          sub="100% = one core"
          icon={<FiCpu />}
        />
        <Stat
          label="Machine memory"
          value={
            sys.mem_total_bytes > 0
              ? `${formatBytes(sys.mem_used_bytes)} / ${formatBytes(sys.mem_total_bytes)}`
              : UNKNOWN
          }
        />
        <Stat
          label="Machine CPU"
          value={sys.mem_total_bytes > 0 ? `${sys.machine_cpu_percent.toFixed(0)}%` : UNKNOWN}
        />
      </div>

      <Card
        title="Peer worker"
        right={
          <div className="flex items-center gap-2">
            <Pill tone={sys.worker_found ? "up" : "down"}>
              {sys.worker_found ? `pid ${sys.worker_pid}` : "not running"}
            </Pill>
            {ownership?.can_restart && (
              <button
                className={BUTTON}
                disabled={restarting}
                onClick={() => {
                  setRestarting(true);
                  setError("");
                  restartWorker()
                    .catch((e: unknown) => setError(String(e)))
                    .finally(() => window.setTimeout(() => setRestarting(false), 1500));
                }}
              >
                <FiRefreshCw aria-hidden className="inline" />{" "}
                {restarting ? "Restarting…" : "Restart"}
              </button>
            )}
          </div>
        }
      >
        <div className="flex flex-wrap items-center gap-2 py-1">
          <span className="text-faint">
            <FiSearch aria-hidden />
          </span>
          <input
            className="min-w-0 flex-1 rounded-sm border border-line bg-surface-2 px-2.5 py-[6px] text-sm focus:border-brand-2 focus:outline-none"
            placeholder="Filter lines"
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
          />
          <button className={BUTTON} onClick={() => setFollowing((v) => !v)}>
            {following ? (
              <>
                <FiPause aria-hidden className="inline" /> Pause scroll
              </>
            ) : (
              <>
                <FiPlay aria-hidden className="inline" /> Follow
              </>
            )}
          </button>
          <button
            className={BUTTON}
            disabled={lines.length === 0}
            title="Copy every line the worker has produced, not just the ones on screen"
            onClick={() => {
              // The full history, not `shown`. A copied log that stops at the visible tail is the
              // one thing nobody wants when they are about to paste it into a bug report.
              void navigator.clipboard
                ?.writeText(lines.join("\n"))
                .then(() => setNotice(`Copied ${lines.length} line(s)`))
                .catch((e: unknown) => setError(String(e)));
            }}
          >
            <FiCopy aria-hidden className="inline" /> Copy
          </button>
          <button
            className={BUTTON}
            disabled={lines.length === 0}
            onClick={() => {
              setError("");
              setNotice("");
              saveWorkerLogs()
                // `null` is the user cancelling the OS dialog — an ordinary outcome, so it says
                // nothing rather than reporting a failure that did not happen.
                .then((path) => setNotice(path ? `Saved to ${path}` : ""))
                .catch((e: unknown) => setError(String(e)));
            }}
          >
            <FiSave aria-hidden className="inline" /> Save logs
          </button>
          <span className={cx(MONO, "text-faint")}>
            {hidden > 0
              ? `last ${shown.length} of ${matching.length}`
              : `${shown.length} line${shown.length === 1 ? "" : "s"}`}
            {filter.trim() ? ` matching, of ${lines.length}` : ""}
          </span>
        </div>
        {error && <p className="pb-1 text-sm text-danger">{error}</p>}
        {!error && notice && <p className="pb-1 text-sm text-dim">{notice}</p>}
      </Card>

      {shown.length === 0 ? (
        <Empty title={lines.length === 0 ? "No output yet" : "Nothing matches that filter"} />
      ) : (
        <pre
          className={cx(
            "min-h-[280px] flex-1 overflow-auto rounded-md border border-line bg-surface-2 p-3",
            "font-mono text-[11px] leading-[1.55] break-words whitespace-pre-wrap text-dim",
          )}
          ref={box}
          onScroll={(event) => {
            const el = event.currentTarget;
            setFollowing(el.scrollHeight - el.scrollTop - el.clientHeight < 24);
          }}
        >
          {hidden > 0 && (
            <div className="pb-1 text-faint">
              — {hidden} earlier line(s) not shown; Copy and Save take all of them —
            </div>
          )}
          {shown.map((line, index) => (
            <div key={index} className={lineTone(line)}>
              {line}
            </div>
          ))}
        </pre>
      )}
    </div>
  );
}

/**
 * Colour a log line by its own severity token.
 *
 * Matched against the worker's SLF4J output rather than guessed at: `slf4j-simple` writes the level
 * in square brackets after the thread name, so this reads what is there and leaves anything else
 * alone.
 */
function lineTone(line: string): string {
  if (line.includes(" ERROR ") || line.includes("Exception")) return "text-danger";
  if (line.includes(" WARN ")) return "text-warn";
  return "";
}

const BUTTON =
  "rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover disabled:opacity-50";
