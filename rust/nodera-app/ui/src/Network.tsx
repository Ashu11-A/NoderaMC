// Join a world — what other peers are offering, and the door this node opens onto one.
//
// Joining binds a loopback port that leads to somebody's running game; it transfers nothing. The
// screen says that once, on the card that hands you the address, where it is actionable — not as a
// paragraph at the top.
import { useEffect, useMemo, useState } from "react";
import {
  FiCheck,
  FiCopy,
  FiLogIn,
  FiGlobe,
  FiLink,
  FiSearch,
  FiUsers,
} from "react-icons/fi";
import { AVATAR, Card, Empty, MONO, Pill, Td, Th, Tr, cx } from "./components";
import {
  browseNetwork,
  joinWorld,
  leaveWorld,
  parseShareLink,
  type DirectoryEntry,
} from "./network";
import { shortId } from "./api";

/** A world this app has a local door open to, and where that door is. */
interface Joined {
  sessionId: string;
  address: string;
}

export function NetworkScreen() {
  const [worlds, setWorlds] = useState<DirectoryEntry[]>([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [joined, setJoined] = useState<Joined[]>([]);
  const [link, setLink] = useState("");
  const [linkError, setLinkError] = useState("");

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
    if (!needle) return worlds;
    // Name and id both, because a friend sends you one or the other: a world name to look for, or
    // an invitation whose id you pasted.
    return worlds.filter(
      (w) =>
        w.name.toLowerCase().includes(needle) || w.session_id.toLowerCase().includes(needle),
    );
  }, [worlds, query]);

  const join = (sessionId: string) => {
    setBusy(sessionId);
    setError("");
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
    leaveWorld(sessionId)
      .then(() => setJoined((current) => current.filter((j) => j.sessionId !== sessionId)))
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
          join(match.session_id);
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
    <div className="flex max-w-[1100px] flex-col gap-4 px-[26px] pt-5 pb-10">
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

      <Card
        title="On the network"
        right={
          <div className="flex items-center gap-2">
            <span className="text-faint">
              <FiSearch aria-hidden />
            </span>
            <input
              className="w-[200px] rounded-sm border border-line bg-surface-2 px-2.5 py-[6px] text-sm focus:border-brand-2 focus:outline-none"
              placeholder="Search by name or id"
              value={query}
              onChange={(e) => setQuery(e.currentTarget.value)}
            />
          </div>
        }
      >
        {error && <p className="pb-2 text-sm text-danger">{error}</p>}
        {matches.length === 0 ? (
          <Empty icon={<FiGlobe />} title={loading ? "Looking…" : "Nothing found"}>
            {query.trim() ? "Try part of the name, or paste an invitation." : ""}
          </Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-sm">
              <thead>
                {/* Five headers for five columns. There were four, so "Kind" sat over the piece
                    count and every cell after Players was read under the wrong heading — a table
                    that quietly relabelled its own data. */}
                <Tr>
                  <Th>World</Th>
                  <Th num>Players</Th>
                  <Th num>Pieces</Th>
                  <Th>Health</Th>
                  <Th />
                </Tr>
              </thead>
              <tbody>
                {matches.map((world) => {
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
                          <FiUsers aria-hidden /> {world.players}
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
                            onClick={() => join(world.session_id)}
                          >
                            <FiLogIn aria-hidden className="inline" />{" "}
                            {busy === world.session_id ? "Connecting…" : "Join"}
                          </button>
                        )}
                      </Td>
                    </Tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

const BUTTON =
  "rounded-sm border border-line bg-surface-2 px-2.5 py-1 text-xs hover:bg-surface-hover disabled:opacity-50";
