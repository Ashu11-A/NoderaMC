// Join a world — what other peers are offering, and the door this node opens onto one.
//
// Joining binds a loopback port that leads to somebody's running game; it transfers nothing. The
// screen says that once, on the card that hands you the address, where it is actionable — not as a
// paragraph at the top.
import { useEffect, useMemo, useState } from "react";
import {
  FiArrowDown,
  FiArrowUp,
  FiCheck,
  FiCopy,
  FiLogIn,
  FiGlobe,
  FiLink,
  FiUsers,
} from "react-icons/fi";
import { AVATAR, Card, DataTable, Empty, FilterBar, MONO, PageGrid, PageHeader, Pagination, Pill, Td, Th, Tr, cx } from "./components";
import {
  browseNetwork,
  joinWorld,
  leaveWorld,
  parseShareLink,
  type DirectoryEntry,
} from "./play";
import { shortId } from "./api";

/** A world this app has a local door open to, and where that door is. */
interface Joined {
  sessionId: string;
  address: string;
}

const PAGE_SIZE = 12;

/* ---------------------------------------------------------------------------------- sorting */

/**
 * What the directory can be ordered by.
 *
 * Exactly the columns the table already shows, and no more. A sort control offering a criterion
 * that is not a column asks people to re-order a list by something they cannot see, and then to
 * trust the result.
 */
type SortKey = "name" | "peers" | "pieces" | "health";

interface Sort {
  key: SortKey;
  ascending: boolean;
}

/**
 * Health, ranked worst-to-best so "sort by availability" means something.
 *
 * The wire sends a word, and words have no order — sorting the strings alphabetically would put
 * `dead` before `degraded` before `healthy` and look deliberate while meaning nothing. Ranking here
 * is what makes the column answer "which of these worlds can I count on".
 */
const HEALTH_RANK: Record<string, number> = { dead: 0, degraded: 1, healthy: 2 };

function healthRank(health: string): number {
  // An unrecognised health is ranked below every known one rather than above: a world whose state
  // this app does not understand is not evidence of a healthy world.
  return HEALTH_RANK[health] ?? -1;
}

function comparatorFor(sort: Sort): (a: DirectoryEntry, b: DirectoryEntry) => number {
  const direction = sort.ascending ? 1 : -1;
  return (a, b) => {
    let difference = 0;
    switch (sort.key) {
      case "peers":
        difference = a.peers - b.peers;
        break;
      case "pieces":
        difference = a.pieces - b.pieces;
        break;
      case "health":
        difference = healthRank(a.health) - healthRank(b.health);
        break;
      case "name":
        difference = a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
        break;
    }
    // Ties break on name, always ascending. Without it, two worlds with the same peer count swap
    // places between refreshes — the list appears to shuffle itself while nothing has changed.
    if (difference === 0) {
      return a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
    }
    return difference * direction;
  };
}

/** A column header that sorts. The arrow marks the active column and its direction. */
function SortHeader(props: {
  label: string;
  sortKey: SortKey;
  sort: Sort;
  num?: boolean;
  onSort: (key: SortKey) => void;
}) {
  const active = props.sort.key === props.sortKey;
  return (
    <Th num={props.num}>
      <button
        className={cx(
          "inline-flex items-center gap-1 hover:text-text",
          active ? "text-text" : "text-faint",
        )}
        onClick={() => props.onSort(props.sortKey)}
        // Read out by screen readers as the table's current ordering, which the arrow alone is not.
        aria-sort={active ? (props.sort.ascending ? "ascending" : "descending") : "none"}
      >
        {props.label}
        {active &&
          (props.sort.ascending ? (
            <FiArrowUp aria-hidden size={11} />
          ) : (
            <FiArrowDown aria-hidden size={11} />
          ))}
      </button>
    </Th>
  );
}

export function NetworkScreen(props: {
  onPlay?: (sessionId: string, worldName: string) => Promise<void>;
} = {}) {
  const [worlds, setWorlds] = useState<DirectoryEntry[]>([]);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<Sort>({ key: "name", ascending: true });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [joined, setJoined] = useState<Joined[]>([]);
  const [link, setLink] = useState("");
  const [linkError, setLinkError] = useState("");
  const [page, setPage] = useState(1);

  const refresh = () => {
    setLoading(true);
    browseNetwork(100)
      .then(setWorlds)
      .catch(() => setWorlds([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    refresh();
    // The directory is a query against services that may be slow or down, so it is polled slowly
    // and on demand rather than pushed. A browser that re-fetched a catalogue several times a
    // second would cost every tracker on the network more than it costs this window.
    const timer = window.setInterval(refresh, 15_000);
    return () => window.clearInterval(timer);
  }, []);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    // Name and id both, because a friend sends you one or the other: a world name to look for, or
    // an invitation whose id you pasted.
    const found = needle
      ? worlds.filter(
          (w) =>
            w.name.toLowerCase().includes(needle) || w.session_id.toLowerCase().includes(needle),
        )
      : worlds;
    // Copied before sorting: `found` is the state array itself when nothing is being searched for,
    // and sorting in place would mutate React's own state and make the next render's comparison
    // meaningless.
    return [...found].sort(comparatorFor(sort));
  }, [worlds, query, sort]);
  const visible = matches.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  useEffect(() => {
    const last = Math.max(1, Math.ceil(matches.length / PAGE_SIZE));
    if (page > last) setPage(last);
  }, [matches.length, page]);

  /** Click a column to sort by it; click it again to reverse. */
  const sortBy = (key: SortKey) => {
    setPage(1);
    setSort((current) =>
      current.key === key
        ? { key, ascending: !current.ascending }
        : // A new column starts in the direction that answers the question people ask of it:
          // names alphabetically, and every measure biggest-first, because "which world has the
          // most players" is the question and "which has the fewest" is not.
          { key, ascending: key === "name" },
    );
  };

  const join = (world: DirectoryEntry) => {
    const sessionId = world.session_id;
    setBusy(sessionId);
    setError("");
    if (props.onPlay) {
      props
        .onPlay(sessionId, world.name || sessionId)
        .catch((e: unknown) => setError(String(e)))
        .finally(() => setBusy(""));
      return;
    }
    joinWorld(sessionId)
      .then((outcome) => {
        if (outcome.ok) {
          setJoined((current) => [
            ...current.filter((j) => j.sessionId !== sessionId),
            { sessionId, address: outcome.value },
          ]);
        } else {
          setError(outcome.error);
        }
      })
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setBusy(""));
  };

  const leave = (sessionId: string) => {
    setBusy(sessionId);
    setError("");
    leaveWorld(sessionId)
      .then((outcome) => {
        if (outcome.ok) {
          setJoined((current) => current.filter((j) => j.sessionId !== sessionId));
        } else {
          setError(outcome.error || "The worker did not close that connection.");
        }
      })
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setBusy(""));
  };

  /**
   * Follow a pasted invitation.
   *
   * An invitation names a **world**; `NODERA-CONNECT` takes a **session**. Passing the world id
   * straight to `join()` sent the wrong identifier to the worker — the connect could not succeed,
   * and `setQuery(worldId)` simultaneously emptied the directory list (it filters on session id and
   * name), so the screen said "nothing found" at the moment it broke.
   *
   * The two spaces only coincide when the tracker happens to list the session under that id, so
   * that is tried and anything else is reported as what it is.
   */
  const followLink = () => {
    setLinkError("");
    parseShareLink(link.trim())
      .then((worldId) => {
        const match = worlds.find((w) => w.session_id === worldId);
        if (match) {
          setQuery("");
          join(match);
          return;
        }
        setLinkError(
          `That invitation is for world ${shortId(worldId)}, which no tracker is currently ` +
            "listing a live session for. Ask the host to open it, then find it in the list below.",
        );
      })
      .catch((e: unknown) => setLinkError(String(e)));
  };

  return (
    <PageGrid className="gap-y-5">
      <PageHeader
        eyebrow="Live directory"
        title="Discover worlds"
        description="Browse sessions currently announced by trackers, or open a trusted invitation."
      />
      <div className="col-span-12 flex flex-col gap-4">
      {joined.length > 0 && (
        <Card
          title="Connect Minecraft to"
          hint="Minecraft → Multiplayer → Direct Connection. Nothing is downloaded; this is a door onto their running game."
        >
          <div className="flex flex-col">
            {joined.map((j) => {
              const world = worlds.find((w) => w.session_id === j.sessionId);
              return (
                <div
                  key={j.sessionId}
                  className="flex flex-wrap items-center gap-2.5 border-b border-line-soft py-2.5 last:border-b-0"
                >
                  <span className="min-w-0 flex-1 truncate">
                    {world?.name || shortId(j.sessionId)}
                  </span>
                  <code className={cx(MONO, "rounded-sm bg-surface-2 px-2 py-1 text-brand-3")}>
                    {j.address}
                  </code>
                  <button
                    className={BUTTON}
                    onClick={() => void navigator.clipboard?.writeText(j.address)}
                    title="Copy the address"
                  >
                    <FiCopy aria-hidden className="inline" /> Copy
                  </button>
                  <button
                    className={cx(BUTTON, "text-warn")}
                    disabled={busy === j.sessionId}
                    onClick={() => leave(j.sessionId)}
                  >
                    Leave
                  </button>
                </div>
              );
            })}
          </div>
        </Card>
      )}

      <Card
        title="Have an invitation?"
        hint="Paste a nodera: link."
      >
        <div className="flex flex-wrap items-center gap-2 py-1">
          <span className="text-faint">
            <FiLink aria-hidden />
          </span>
          <input
            className="min-w-0 flex-1 rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] focus:border-brand-2 focus:outline-none"
            placeholder="nodera:?xt=urn:nodera:…"
            value={link}
            onChange={(e) => setLink(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") followLink();
            }}
          />
          <button className={BUTTON} onClick={followLink} disabled={!link.trim()}>
            Open
          </button>
        </div>
        {linkError && <p className="pb-1 text-sm text-danger">{linkError}</p>}
      </Card>

      <FilterBar
        label="Search discoverable worlds"
        value={query}
          onChange={(value) => {
            setQuery(value);
            setPage(1);
          }}
        placeholder="Search worlds by name or id"
        actions={<span className="px-2 text-xs text-faint">{matches.length} found</span>}
      />

      <Card
        title="On the network"
      >
        {error && <p className="pb-2 text-sm text-danger">{error}</p>}
        {matches.length === 0 ? (
          <Empty icon={<FiGlobe />} title={loading ? "Looking…" : "Nothing found"}>
            {query.trim() ? "Try part of the name, or paste an invitation." : ""}
          </Empty>
        ) : (
          <>
            <DataTable label="Discoverable worlds">
              <thead>
                {/* Five headers for five columns. There were four, so "Kind" sat over the piece
                    count and every cell after Players was read under the wrong heading — a table
                    that quietly relabelled its own data. */}
                <Tr>
                  <SortHeader label="World" sortKey="name" sort={sort} onSort={sortBy} />
                  <SortHeader label="Peers" sortKey="peers" sort={sort} onSort={sortBy} num />
                  <SortHeader label="Pieces" sortKey="pieces" sort={sort} onSort={sortBy} num />
                  <SortHeader label="Health" sortKey="health" sort={sort} onSort={sortBy} />
                  <Th />
                </Tr>
              </thead>
              <tbody>
                {visible.map((world) => {
                  const here = joined.find((j) => j.sessionId === world.session_id);
                  return (
                    <Tr key={world.session_id}>
                      <Td>
                        <span className="flex items-center gap-2">
                          <span
                            className={cx(AVATAR, "h-[22px] w-[22px] text-[11px]")}
                            aria-hidden
                          >
                            {(world.name || "?").slice(0, 1).toUpperCase()}
                          </span>
                          <span className="truncate">{world.name || shortId(world.session_id)}</span>
                          {world.mine && <Pill tone="muted">Yours</Pill>}
                        </span>
                      </Td>
                      <Td num>
                        <span className="inline-flex items-center gap-1">
                          <FiUsers aria-hidden /> {world.peers}
                        </span>
                      </Td>
                      {/* The tracker's own numbers. The previous version invented a category
                          ("Live game" / "Shared world") from a piece count and never showed the
                          health the tracker actually reports. */}
                      <Td num>{world.pieces.toLocaleString()}</Td>
                      <Td>
                        <Pill tone={world.health === "healthy" ? "up" : world.health === "dead" ? "down" : "warn"}>
                          {world.health || "unknown"}
                        </Pill>
                      </Td>
                      <Td>
                        {here ? (
                          <span className="inline-flex items-center gap-1.5 text-up">
                            <FiCheck aria-hidden /> {here.address}
                          </span>
                        ) : (
                          <button
                            className={BUTTON}
                            disabled={busy === world.session_id}
                            onClick={() => join(world)}
                          >
                            <FiLogIn aria-hidden className="inline" />{" "}
                            {busy === world.session_id ? "Starting…" : "Play"}
                          </button>
                        )}
                      </Td>
                    </Tr>
                  );
                })}
              </tbody>
            </DataTable>
            <Pagination page={page} pageSize={PAGE_SIZE} total={matches.length} onPage={setPage} />
          </>
        )}
      </Card>
      </div>
    </PageGrid>
  );
}

const BUTTON =
  "rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover disabled:opacity-50";
