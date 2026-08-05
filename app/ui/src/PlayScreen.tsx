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
import { useEffect, useMemo, useState } from "react";
import { FiAlertCircle, FiCopy, FiPlay, FiSettings, FiWifiOff } from "react-icons/fi";
import { Banner, Button, Select, cx, worldArt } from "./components";
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
  const playable = useMemo(
    () => d.worlds.filter((w) => w.connected || w.game_endpoint || w.administered),
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
        "https://github.com/Ashu11-A/NoderaMC/blob/main/docs/app/LIMITATIONS.md#b--staged-capabilities",
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
      <Hero world={world} fallbackName={externalWorld?.name} d={d} />

      <div className="page-canvas relative z-10 flex w-full flex-col gap-4 pb-12">
        <div className="-mt-16 flex flex-wrap items-end gap-3">
          {playable.length > 1 && world && (
            <Select
              ariaLabel="World to play"
              value={world.world_id}
              onChange={setSelected}
              className="bg-surface-3 backdrop-blur-sm"
              options={playable.map((w) => ({
                value: w.world_id,
                label: w.name || w.world_id.slice(0, 10),
              }))}
            />
          )}

          {targetChoices.length > 1 && targetChoice && (
            <Select
              ariaLabel="Minecraft installation"
              value={targetChoice.value}
              onChange={setSelectedTarget}
              className="bg-surface-3 backdrop-blur-sm"
              options={targetChoices.map((candidate) => ({
                value: candidate.value,
                label: candidate.target.name,
              }))}
            />
          )}

          <Button
            variant="primary"
            size="hero"
            disabled={!launchSelection || busy}
            onClick={startLaunch}
          >
            <FiPlay aria-hidden />
            {PHASE_LABEL[launch.phase]}
          </Button>

          {world && (
            <Button variant="ghost" size="md" onClick={() => props.onOpenWorld(world.world_id)}>
              About this world
            </Button>
          )}
        </div>

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

        {!launchSelection && <NothingToPlay d={d} onSettings={props.onSettings} />}
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------------------------------- hero */

/**
 * The picture, the name, and the two facts that decide whether to press the button.
 *
 * The art is generated from the world id — deterministic, CSS-only, no network. See `worldArt`.
 */
function Hero(props: { world?: World; fallbackName?: string; d: Dashboard }) {
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
      className="relative flex flex-none items-end"
      style={{ height: "var(--hero-height)" }}
    >
      <div
        aria-hidden
        className={cx("absolute inset-0", world && "world-art")}
        style={world ? worldArt(world.world_id) : undefined}
      />
      <div aria-hidden className="hero-scrim absolute inset-0" />

      <div className="page-canvas relative flex w-full flex-col gap-2 pb-24">
        <p className="text-[10px] font-semibold tracking-[0.2em] text-brand-1 uppercase">Ready to play</p>
        <h1 className="display-type max-w-[12ch] text-[clamp(42px,5vw,72px)] leading-[0.95] font-semibold">
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
        {!fault && !paused && d.link.has_data && (
          <p className="font-mono text-xs text-faint tabular-nums">
            ▲ {show(d.traffic.up_bytes_per_sec, formatRate)} ▼{" "}
            {show(d.traffic.down_bytes_per_sec, formatRate)} · {formatBytes(d.counts.shared_bytes)}{" "}
            served
          </p>
        )}
      </div>
    </header>
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
    <div className="flex flex-col items-center gap-3 rounded-lg border border-line bg-surface px-6 py-12 text-center">
      <span className="text-[28px] text-faint">
        <FiAlertCircle aria-hidden />
      </span>
      <h2 className="text-base font-semibold">
        {known ? "No world to play in yet" : "Waiting for this node to report"}
      </h2>
      <p className="max-w-[52ch] text-sm text-dim">
        {known
          ? "Open a world to LAN from inside Minecraft and share it, or find one on Discover."
          : "The worker has not sent a picture of this node yet. Nothing here is zero — it is unknown."}
      </p>
      {known && (
        <Button variant="secondary" size="md" onClick={() => props.onSettings("minecraft")}>
          <FiSettings aria-hidden /> Set up Minecraft
        </Button>
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
