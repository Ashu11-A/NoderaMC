// The launcher's front page: pick a world, press Play, watch it start.
//
// # What this screen replaced
//
// An "Overview" of six stat tiles and two cards of key–value rows. It was an accurate report about
// a node and it answered no question a player has. The one thing they came here to do — get into a
// world — was three screens away and ended in a loopback address to copy by hand.
//
// # The rules this screen keeps
//
// * **One decision.** A world picker and a button. Everything technical about the selected world is
//   a click away in Worlds, and everything about the node is in Settings.
// * **The button always says what it is doing.** `LaunchPhase` is a closed set and every member has
//   a word here; a spinner with no text is the same failure as a tile reporting `0` for "nobody
//   said". The exit test in `tests/ux-honesty.test.mjs` asserts exactly that.
// * **A failure offers one action.** The `remedy` enum decides which — not the prose, which can be
//   reworded without anybody noticing a button stopped appearing.
//
// # The picker is a wall of cards, not a dropdown
//
// It was a `<select>`, and below the hero the screen held that dropdown, one sentence of status,
// and nothing else — so on a 1080p display the bottom half of the window was empty and the one
// decision the screen exists for was folded shut inside a control that shows one option at a time.
// A launcher is the wrong place to hide the list of things you can launch.
//
// The wall is the same `CARD_GRID` the library uses, which is why it answers a wider window with
// more columns rather than more margin, and each card is the world's own generated art — the same
// picture it has in the library and on its own screen, so it is something a player recognises
// rather than a row of names. The set is still exactly the set the dropdown offered: worlds you are
// in, worlds with a game running, worlds you administer. Choosing one selects it; it does not
// navigate, because the whole point of this screen is that the next thing you press is Play.
import { useEffect, useMemo, useState } from "react";
import {
  FiAlertCircle,
  FiCheck,
  FiCopy,
  FiFileText,
  FiPlay,
  FiSettings,
  FiUsers,
  FiWifiOff,
} from "react-icons/fi";
import { Banner, Button, CARD_GRID, Empty, Select, cx, worldArt } from "./components";
import {
  IDLE_LAUNCH,
  autoConnects,
  fetchLaunchState,
  fetchLaunchTargets,
  leaveWorld,
  launchPlay,
  mergeLaunchState,
  onLaunch,
  type LaunchPhase,
  type LaunchState,
  type LaunchTarget,
  type Remedy,
} from "./play";
import { fetchPauseReason, togglePause } from "./ipc";
import { useExternalLink } from "./links";
import {
  formatBytes,
  formatRate,
  isStale,
  linkFault,
  show,
  worldRole,
  type Dashboard,
  type World,
} from "./api";

/**
 * What the button says at each phase.
 *
 * Every `LaunchPhase` appears here. A phase with no word would render an unlabelled spinner, and the
 * honesty test reads this table to prove none is missing.
 */
const PHASE_LABEL: Record<LaunchPhase, string> = {
  idle: "Play",
  resolving: "Finding Minecraft…",
  joining: "Opening a connection…",
  preparing: "Getting things ready…",
  spawning: "Starting Minecraft…",
  running: "Playing",
  closing: "Closing connection…",
  exited: "Play again",
  failed: "Try again",
};

/** What the one button under a failure does. */
const REMEDY_LABEL: Record<Remedy, string> = {
  none: "",
  "install-mod": "Install the mod",
  "pick-install": "Choose an installation",
  "install-java": "How to install Java",
  "sign-in": "Why sign-in is unavailable",
  "copy-address": "Copy the address",
  retry: "Try again",
};

export function PlayScreen(props: {
  d: Dashboard;
  initialWorld?: { id: string; name: string };
  onOpenWorld: (id: string) => void;
  onSettings: (section?: string) => void;
}) {
  const { d } = props;
  const [launch, setLaunch] = useState<LaunchState>(IDLE_LAUNCH);
  const [targets, setTargets] = useState<LaunchTarget[] | null>(null);
  const [selected, setSelected] = useState<string>("");
  const [selectedTarget, setSelectedTarget] = useState<string>("");
  const [closeError, setCloseError] = useState("");
  const [closing, setClosing] = useState(false);
  const external = useExternalLink();

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | undefined;
    let eventRevision = 0;

    onLaunch((incoming) => {
      if (!alive) return;
      eventRevision += 1;
      setLaunch((current) => mergeLaunchState(current, incoming));
    })
      .then(async (stop) => {
        if (!alive) {
          stop();
          return;
        }
        unlisten = stop;
        // Listener is active before restore starts. Any event during fetch makes snapshot stale.
        const revisionBeforeFetch = eventRevision;
        const restored = await fetchLaunchState();
        if (alive && eventRevision === revisionBeforeFetch) {
          setLaunch((current) => mergeLaunchState(current, restored));
        }
      })
      .catch(() => {
        // If subscription itself is unavailable, one snapshot is still more honest than idle.
        if (alive) {
          fetchLaunchState()
            .then((restored) => alive && setLaunch((current) => mergeLaunchState(current, restored)))
            .catch(() => {});
        }
      });
    return () => {
      alive = false;
      unlisten?.();
    };
  }, []);

  useEffect(() => {
    fetchLaunchTargets()
      .then(setTargets)
      // `null` stays "not asked"; an empty array is "asked, and there is nothing". The screen says
      // different things about those two and must not confuse them.
      .catch(() => setTargets([]));
  }, []);

  // Worlds worth playing: somewhere you already are, or somewhere with a game running. A world this
  // node merely stores has nothing to join.
  //
  // Sorted, because these are now cards a person points at rather than rows in a dropdown they read
  // top to bottom. The payload's order is the worker's, and a wall whose tiles trade places between
  // snapshots is one nobody can learn. Alphabetical by name, and by id for a world that has none.
  const playable = useMemo(
    () =>
      d.worlds
        .filter((w) => w.connected || w.game_endpoint || w.administered)
        .sort((a, b) => {
          const left = a.name || a.world_id;
          const right = b.name || b.world_id;
          return (
            left.localeCompare(right, undefined, { sensitivity: "base" }) ||
            a.world_id.localeCompare(b.world_id)
          );
        }),
    [d.worlds],
  );

  const requestedWorldId = selected || props.initialWorld?.id || launch.world_id;
  const world = requestedWorldId
    ? playable.find((candidate) => candidate.world_id === requestedWorldId)
    : playable[0];
  const externalWorld = world
    ? undefined
    : props.initialWorld ??
      (launch.world_id ? { id: launch.world_id, name: "Network world" } : undefined);
  const launchSelection = world
    ? { id: world.world_id, name: world.name || world.world_id }
    : externalWorld;
  const targetChoices = useMemo(() => {
    if (!targets) return [];
    return targets.map((target) => ({ target, value: target.id }));
  }, [targets]);
  const targetChoice =
    targetChoices.find((candidate) => candidate.value === selectedTarget) ?? targetChoices[0];
  const target = targetChoice?.target;
  const busy =
    Boolean(launch.session_id) ||
    (launch.phase !== "idle" && launch.phase !== "exited" && launch.phase !== "failed");

  const startLaunch = () => {
    if (!launchSelection) return;
    setCloseError("");
    setLaunch({ ...IDLE_LAUNCH, phase: "resolving", world_id: launchSelection.id });
    launchPlay(launchSelection.id, launchSelection.name, targetChoice?.target.id).catch(
      async (error: unknown) => {
        try {
          const restored = await fetchLaunchState();
          if (restored.phase !== "idle" || restored.session_id) {
            setLaunch((current) => mergeLaunchState(current, restored));
            return;
          }
        } catch {
          // The command error below remains the actionable answer when state restoration also fails.
        }
        setLaunch((held) => ({
          ...held,
          phase: "failed",
          reason: String(error),
          remedy: "retry",
        }));
      },
    );
  };

  const handleRemedy = (remedy: Remedy) => {
    if (remedy === "retry") {
      if (launch.session_id) closeConnection();
      else startLaunch();
    } else if (remedy === "copy-address" && launch.address) {
      navigator.clipboard?.writeText(launch.address).catch(() => {});
    } else if (remedy === "install-java") {
      void external.open("https://adoptium.net/temurin/releases/?version=21");
    } else if (remedy === "sign-in") {
      void external.open(
        "https://github.com/Ashu11-A/NoderaMC/blob/main/docs/frontend/LIMITATIONS.md#b--staged-capabilities",
      );
    } else if (remedy === "install-mod" || remedy === "pick-install") {
      props.onSettings("minecraft");
    }
  };

  const closeConnection = () => {
    if (!launch.session_id) return;
    setClosing(true);
    setCloseError("");
    setLaunch((current) => ({ ...current, phase: "closing", cancelled: true }));
    leaveWorld(launch.session_id)
      .then((outcome) => {
        if (!outcome.ok) throw new Error(outcome.error || "the worker did not close the connection");
        return fetchLaunchState();
      })
      .then((restored) => setLaunch((current) => mergeLaunchState(current, restored)))
      .catch(async (error: unknown) => {
        setCloseError(String(error));
        try {
          const restored = await fetchLaunchState();
          setLaunch((current) => mergeLaunchState(current, restored));
        } catch {
          // Keep the explicit closing state when even backend restoration is unavailable.
        }
      })
      .finally(() => setClosing(false));
  };

  return (
    <div className="flex min-h-full flex-col">
      <Hero
        world={world}
        fallbackName={externalWorld?.name}
        d={d}
        launch={launch}
        onSettings={props.onSettings}
        actions={
          <>
            <Button
              variant="primary"
              size="hero"
              shape="pill"
              disabled={!launchSelection || busy}
              onClick={startLaunch}
            >
              <FiPlay aria-hidden />
              {PHASE_LABEL[launch.phase]}
            </Button>

            {world && (
              <Button
                variant="secondary"
                size="md"
                shape="pill"
                className="border-transparent bg-surface-3 backdrop-blur-sm"
                onClick={() => props.onOpenWorld(world.world_id)}
              >
                <FiSettings aria-hidden /> About this world
              </Button>
            )}
          </>
        }
      />

      <div className="page-canvas page-grid relative z-10 gap-y-5 pt-6 pb-12">
        <div className="col-span-12 flex flex-col gap-3">
          {/* One dropdown is left, and it is a genuinely different decision from the one the wall
              below makes: which copy of Minecraft starts, not which world it opens. It carries a
              visible label because a bare `<select>` under a hero says nothing about what it
              chooses. */}
          {targetChoices.length > 1 && targetChoice && (
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
              <span className="text-2xs font-medium tracking-[0.16em] text-faint uppercase">
                Minecraft installation
              </span>
              <Select
                ariaLabel="Minecraft installation"
                value={targetChoice.value}
                onChange={setSelectedTarget}
                options={targetChoices.map((candidate) => ({
                  value: candidate.value,
                  label: candidate.target.name,
                }))}
              />
            </div>
          )}

          <LaunchLine
            launch={launch}
            target={target}
            targetsLoaded={targets !== null}
            onSettings={props.onSettings}
            onRemedy={handleRemedy}
            onCloseConnection={closeConnection}
            closing={closing}
          />

          {closeError && (
            <Banner tone="danger">
              Connection is still open: {closeError.replace(/^Error:\s*/, "")}
            </Banner>
          )}
        </div>

        {/* Only when there is a choice to make. One playable world is already the hero, at the size
            of the window, and repeating it underneath as a card would be the same world twice. */}
        {playable.length > 1 && (
          <section className="col-span-12" aria-label="World to play">
            <header className="mb-3.5 flex flex-wrap items-end justify-between gap-x-5 gap-y-1 border-b border-line-soft pb-2.5">
              <div className="min-w-0">
                <h2 className="text-2xs font-medium tracking-[0.16em] text-dim uppercase">
                  Worlds ready to play
                </h2>
                <p className="mt-1.5 max-w-[80ch] text-xs text-faint">
                  Worlds you are in, worlds with a game running, and worlds you run yourself. Pick
                  one and the hero above changes with it.
                </p>
              </div>
              <span className="flex-none text-xs text-faint tabular-nums">{playable.length}</span>
            </header>
            <div className={CARD_GRID}>
              {playable.map((candidate) => (
                <WorldChoice
                  key={candidate.world_id}
                  world={candidate}
                  active={candidate.world_id === world?.world_id}
                  // The same condition that disables Play. A launch in flight is a launch of one
                  // named world, and letting the picker move under it would put a different name
                  // in the hero's progress pill than the one actually starting.
                  disabled={busy}
                  onSelect={() => setSelected(candidate.world_id)}
                />
              ))}
            </div>
          </section>
        )}

        {!launchSelection && (
          <div className="col-span-12">
            <NothingToPlay d={d} onSettings={props.onSettings} />
          </div>
        )}
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------------------- picker */

/**
 * One world you could press Play on.
 *
 * A button rather than a link, and `aria-pressed` rather than a radio: pressing it changes what the
 * hero is about, and nothing about the app has moved. The art is the same hash of the same world id
 * the library and the world screen draw, so the three surfaces agree about what a world looks like.
 *
 * Every fact on the card is one that bears on "can I play here now" — whether a game is open, and
 * how many people are in it. Size, completeness and peer counts belong to the world's own screen;
 * a picker that reports eleven figures is a table with pictures.
 */
function WorldChoice(props: {
  world: World;
  active: boolean;
  disabled?: boolean;
  onSelect: () => void;
}) {
  const w = props.world;
  return (
    <button
      type="button"
      aria-pressed={props.active}
      disabled={props.disabled}
      onClick={props.onSelect}
      className={cx(
        "group relative flex min-w-0 flex-col overflow-hidden rounded-lg border p-4 text-left",
        "transition-[border-color,box-shadow,translate] duration-[var(--motion-base)] ease-[var(--ease-out)]",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus",
        props.active
          ? "border-brand-edge bg-surface shadow-glow"
          : "border-line-soft bg-surface",
        props.disabled
          ? !props.active && "opacity-50"
          : !props.active && "hover:-translate-y-0.5 hover:border-brand-edge hover:shadow-e2",
      )}
    >
      <span
        aria-hidden
        className={cx("world-art absolute inset-0", props.active ? "opacity-20" : "opacity-10")}
        style={worldArt(w.world_id)}
      />

      <span className="relative flex min-w-0 items-center gap-2.5">
        <span className="display-type min-w-0 flex-1 truncate text-[17px] font-bold uppercase">
          {w.name || w.world_id.slice(0, 10)}
        </span>
        {props.active && (
          <span aria-hidden className="flex-none text-brand-1">
            <FiCheck />
          </span>
        )}
      </span>

      <span className="relative mt-1.5 truncate text-xs text-dim">{describe(w)}</span>

      <span className="relative mt-4 flex items-center justify-between gap-3 text-xs">
        <span className="flex min-w-0 items-center gap-2 text-dim">
          <FiUsers aria-hidden className="flex-none" />
          {/* `—`, never `0`. Only a node with a game *in* the world can count players, so null is
              the ordinary answer on a peer that merely stores it, and the title — which is attached
              here and never to a real figure — says which of the two a dash means. */}
          <span
            className="font-medium tabular-nums text-text"
            title={w.players === null || w.players === undefined ? "player count unknown" : undefined}
          >
            {show(w.players, String)}
          </span>
        </span>
        <span className={cx("flex-none whitespace-nowrap", w.game_endpoint ? "text-up" : "text-faint")}>
          {w.game_endpoint ? "A game is running" : "No game running"}
        </span>
      </span>
    </button>
  );
}

/* ---------------------------------------------------------------------------------------- hero */

/**
 * The picture, the name, and the two facts that decide whether to press the button.
 *
 * The art is generated from the world id — deterministic, CSS-only, no network. See `worldArt`.
 */
function Hero(props: {
  world?: World;
  fallbackName?: string;
  d: Dashboard;
  launch: LaunchState;
  actions: React.ReactNode;
  onSettings: (section?: string) => void;
}) {
  const { world, d } = props;
  const fault = linkFault(d.link);
  const paused = d.node.transfers_paused;
  const [why, setWhy] = useState("");
  const [pausing, setPausing] = useState(false);

  // Only asked for while paused, and asked again whenever the flag changes: the reason is a
  // sentence the worker composes (low battery, metered connection, a manual pause), and it is the
  // difference between a node that stopped and a node that looks like it crashed. This is the one
  // thing the dissolved Overview screen said that nothing else did.
  useEffect(() => {
    if (!paused) {
      setWhy("");
      return;
    }
    fetchPauseReason().then(setWhy).catch(() => setWhy(""));
  }, [paused]);

  return (
    <header
      className="relative flex flex-none flex-col justify-end"
      style={{ minHeight: "var(--hero-height)" }}
    >
      <div
        aria-hidden
        className={cx("absolute inset-0", world && "world-art")}
        style={world ? worldArt(world.world_id) : undefined}
      />
      <div aria-hidden className="hero-scrim absolute inset-0" />

      {/* Panel 03's floating strip: a glass pill for the fact you glance at, and one that only
          exists while work is happening. Neither pushes the art down. */}
      <div className="page-canvas relative flex w-full flex-wrap items-start gap-3 pt-5">
        <GlassPill>
          <FiUsers aria-hidden className="text-dim" />
          <span className="flex flex-col leading-tight">
            <span className="text-[10px] text-dim">Playing here</span>
            {/* `—`, never `0`. Only a node with a game *in* the world can count players, so null is
                the ordinary answer on a peer that merely stores it, and the title says which. */}
            <span className="font-medium tabular-nums" title={world ? players(world) : undefined}>
              {world ? show(world.players, String) : "—"}
            </span>
          </span>
        </GlassPill>

        {props.launch.phase !== "idle" &&
          props.launch.phase !== "running" &&
          props.launch.phase !== "exited" &&
          props.launch.phase !== "failed" && (
            <GlassPill>
              <span
                aria-hidden
                className="size-4 flex-none animate-spin rounded-full border-2 border-brand-edge border-t-brand-1"
              />
              <span className="flex flex-col leading-tight">
                <span className="font-medium text-text">{PHASE_LABEL[props.launch.phase]}</span>
                <span className="text-[10px] text-dim">
                  {world?.name || props.launch.world_id.slice(0, 10) || "Nodera"}
                </span>
              </span>
            </GlassPill>
          )}
      </div>

      <div className="page-canvas relative flex w-full flex-1 flex-col justify-end gap-2 pt-10 pb-6">
        <p className="text-2xs font-medium tracking-[0.16em] text-brand-tint uppercase">Ready to play</p>
        <h1 className="display-type max-w-[12ch] text-hero leading-[0.95] font-bold">
          {world ? world.name || "Unnamed world" : props.fallbackName || "Nodera"}
        </h1>
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-dim">
          {world ? (
            <>
              <span>{describe(world)}</span>
              <span aria-hidden>·</span>
              <span>{players(world)}</span>
              <span aria-hidden>·</span>
              <span>
                {world.seeders === 1
                  ? "1 peer holds it"
                  : `${world.seeders} peers hold it`}
              </span>
            </>
          ) : props.fallbackName ? (
            <span>Live session selected from Discover</span>
          ) : (
            <span>Worlds you play in, run, or help share</span>
          )}
        </p>
        {/* The link readout that used to be a whole screen. One line, and only when it is not fine. */}
        {fault && (
          <p className="flex items-center gap-1.5 text-xs text-danger">
            <FiWifiOff aria-hidden /> {fault}
          </p>
        )}
        {paused && (
          <p className="flex flex-wrap items-center gap-2 text-xs text-warn">
            <span>Sharing is paused{why ? ` — ${why}` : ""}.</span>
            <Button
              variant="ghost"
              disabled={pausing}
              onClick={() => {
                setPausing(true);
                togglePause().finally(() => setPausing(false));
              }}
            >
              Resume sharing
            </Button>
          </p>
        )}
        <div className="mt-4 flex flex-wrap items-center gap-3">{props.actions}</div>
      </div>

      {/* Panel 03's footer strip. The reference puts a social cluster on the right; there is no
          social plane here, so the space takes the fact the hero copy used to carry. */}
      <div className="page-canvas relative flex w-full flex-wrap items-center justify-between gap-3 pb-6">
        <button
          onClick={() => props.onSettings("minecraft")}
          className="inline-flex items-center gap-2 rounded-full bg-surface-3 px-3.5 py-2 text-xs text-dim backdrop-blur-sm transition-colors duration-[var(--motion-fast)] hover:text-text focus-visible:outline-2 focus-visible:outline-focus"
        >
          <FiFileText aria-hidden /> Minecraft and mod
        </button>
        {!fault && !paused && d.link.has_data && (
          <p className="rounded-full bg-surface-3 px-3.5 py-2 font-mono text-xs text-faint backdrop-blur-sm tabular-nums">
            ▲ {show(d.traffic.up_bytes_per_sec, formatRate)} ▼{" "}
            {show(d.traffic.down_bytes_per_sec, formatRate)} · {formatBytes(d.counts.shared_bytes)}{" "}
            served
          </p>
        )}
      </div>
    </header>
  );
}

/** The reference's one floating surface: translucent, blurred, and only ever over art. */
function GlassPill(props: { children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2.5 rounded-full border border-line-soft bg-surface-3 px-3.5 py-2 text-xs backdrop-blur-md">
      {props.children}
    </span>
  );
}

function describe(w: World): string {
  switch (worldRole(w)) {
    case "playing":
      return "You are playing here";
    case "administered":
      return "You administer this";
    case "hosting":
      return "You are hosting it";
    default:
      return "You help share it";
  }
}

/**
 * Player counts, and never a `0` that means "the worker did not say".
 *
 * Only a node with a game *in* the world can count them, so `null` is the ordinary answer on a peer
 * that merely stores it. Rendering that as `0` is the bug this field's own comment in `api.ts` was
 * written about.
 */
function players(w: World): string {
  if (w.players === null || w.players === undefined) return "player count unknown";
  return w.players === 1 ? "1 player online" : `${w.players} players online`;
}

/* -------------------------------------------------------------------------------- launch state */

/**
 * The sentence under the button.
 *
 * Three jobs: say which route Play will take before it is pressed, say what happened when it
 * finished, and — on a failure — offer exactly one thing to do about it.
 */
function LaunchLine(props: {
  launch: LaunchState;
  target?: LaunchTarget;
  targetsLoaded: boolean;
  onSettings: (section?: string) => void;
  onRemedy: (remedy: Remedy) => void;
  onCloseConnection: () => void;
  closing: boolean;
}) {
  const { launch, target, targetsLoaded } = props;

  if (launch.phase === "failed") {
    return (
      <Banner
        tone="danger"
        action={
          <span className="flex items-center gap-2">
            {launch.session_id && (
              <Button variant="ghost" disabled={props.closing} onClick={props.onCloseConnection}>
                {props.closing ? "Closing…" : "Close connection"}
              </Button>
            )}
            {launch.remedy !== "none" && !(launch.remedy === "retry" && launch.session_id) && (
            <Button
              variant="secondary"
              onClick={() => props.onRemedy(launch.remedy)}
            >
              {REMEDY_LABEL[launch.remedy]}
            </Button>
            )}
          </span>
        }
      >
        {launch.reason}
      </Banner>
    );
  }

  if (launch.phase === "running") {
    const delegated = launch.handoff;
    return (
      <Banner
        tone="info"
        action={
          delegated && launch.session_id ? (
            <Button variant="ghost" disabled={props.closing} onClick={props.onCloseConnection}>
              {props.closing ? "Closing…" : "Close connection"}
            </Button>
          ) : undefined
        }
      >
        {delegated ? "Minecraft launcher handed off" : "Minecraft started"}
        {launch.profile ? ` from ${launch.profile}` : ""}
        {launch.address ? ` · connected to ${launch.address}` : ""}.
        {delegated
          ? " Keep this connection open while playing, then close it here."
          : " Nodera will close the connection when Minecraft quits."}
      </Banner>
    );
  }

  if (launch.phase === "exited") {
    return (
      <p className="text-xs text-faint">
        {launch.cancelled ? "Launch cancelled." : "Minecraft closed."} The connection is closed.
      </p>
    );
  }

  if (launch.phase !== "idle") {
    return <p className="text-xs text-dim">{PHASE_LABEL[launch.phase]}</p>;
  }

  // Idle: say what pressing the button will actually do. A launcher whose choice is only revealed
  // by making it is one nobody can predict.
  if (!targetsLoaded) return <p className="text-xs text-faint">Looking for Minecraft…</p>;

  if (!target) {
    return (
      <Banner
        tone="warn"
        action={
          <Button variant="secondary" onClick={() => props.onSettings("minecraft")}>
            Set up Minecraft
          </Button>
        }
      >
        No Minecraft installation was found on this machine.
      </Banner>
    );
  }

  if (!target.has_mod) {
    return (
      <Banner
        tone="warn"
        action={
          <Button variant="secondary" onClick={() => props.onSettings("minecraft")}>
            {REMEDY_LABEL["install-mod"]}
          </Button>
        }
      >
        {target.name} does not have the Nodera mod, so the game would start without it.
      </Banner>
    );
  }

  return (
    <p className="text-xs text-faint">
      Will start <span className="text-dim">{target.name}</span>
      {autoConnects(target.tier)
        ? " and take you straight into the world."
        : " and add the world to your Multiplayer list — this build cannot sign in to join for you."}
    </p>
  );
}

/* ------------------------------------------------------------------------------- nothing to do */

function NothingToPlay(props: { d: Dashboard; onSettings: (section?: string) => void }) {
  const known = props.d.link.has_data;
  return (
    <div className="rounded-lg border border-line-soft bg-surface shadow-e1">
      <Empty
        icon={<FiAlertCircle aria-hidden />}
        title={known ? "No world to play in yet" : "Waiting for this node to report"}
      >
        {known
          ? "Open a world to LAN from inside Minecraft and share it, or find one on Discover."
          : "The worker has not sent a picture of this node yet. Nothing here is zero — it is unknown."}
      </Empty>
      {known && (
        <div className="flex justify-center pb-8">
          <Button variant="secondary" size="md" shape="pill" onClick={() => props.onSettings("minecraft")}>
            <FiSettings aria-hidden /> Set up Minecraft
          </Button>
        </div>
      )}
    </div>
  );
}

/** Copy-to-clipboard for the address, used by the floor tier's remedy. */
export function CopyAddress(props: { address: string }) {
  const [done, setDone] = useState(false);
  return (
    <Button
      variant="secondary"
      onClick={() => {
        navigator.clipboard
          ?.writeText(props.address)
          .then(() => setDone(true))
          .catch(() => {});
      }}
    >
      <FiCopy aria-hidden /> {done ? "Copied" : props.address}
    </Button>
  );
}

/** Whether this screen is showing worker-derived figures that may be stale. */
export function playShowsWorkerFigures(d: Dashboard): boolean {
  return isStale(d.link);
}
