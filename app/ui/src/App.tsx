// The Nodera launcher shell.
//
// # Three destinations, not nine
//
// The rail used to carry one screen per subsystem — Overview, Worlds, Join, Tracker stores, Peers,
// Peer console, Minecraft mod, About, Settings — which is an accurate map of the software and a
// useless one for a player. What they came to do is play; everything else is either about a world
// (one click from the library) or about the machinery (Settings).
//
//   Play      the hero, the world picker, and the button
//   Worlds    the library, as art rather than as a table
//   Discover  what is joinable on the network right now
//   ⚙         everything that used to have its own rail entry
//
// # Data path
//
// `api::link` (Rust) → `nodera://dashboard` → `useDashboard`. There is no polling loop here; the
// backend emits when the node changes.
import { useEffect, useState } from "react";
import { FiCompass, FiGlobe, FiPlay, FiSettings } from "react-icons/fi";
import { cx, MONO, SCROLLPORT_ID, StaleDataNotice } from "./components";
import { SettingsScreen, type Section as SettingsSection } from "./Settings";
import { ConsentModal, useTelemetryStatus } from "./Consent";
import { PlayScreen } from "./PlayScreen";
import { WorldsScreen } from "./Worlds";
import { NetworkScreen } from "./Network";
import { LanOfferModal } from "./Lan";
import { WorldScreen } from "./World";
import { launchPlay } from "./play";
import { useResolvedTheme } from "./theme";
import {
  EMPTY_DASHBOARD,
  formatAge,
  useDashboard,
  formatRate,
  isStale,
  linkFault,
  shortId,
  show,
  type Dashboard,
  type World,
} from "./api";
import {
  fetchSystemStats,
  onSystemStats,
  fetchSettings,
  EMPTY_SYSTEM,
  type SystemStats,
  type Settings as SettingsDoc,
} from "./ipc";

type Screen =
  | { name: "play"; launchWorld?: { id: string; name: string } }
  | { name: "worlds" }
  | { name: "world"; id: string }
  | { name: "discover" }
  | { name: "settings"; section?: SettingsSection };

/**
 * The top navigation.
 *
 * `showsWorkerFigures` is not decoration: it is the list A-UX-1 is enforced against. A screen that
 * renders numbers derived from the worker must mark them as last-known when the link is down, and
 * declaring it here — beside the destination — is what lets the exit test check the set against the
 * screens that actually read the dashboard, rather than against a hard-coded list of four names
 * that drifted every time a screen moved.
 */
const DESTINATIONS = [
  { name: "play", label: "Play", icon: <FiPlay />, showsWorkerFigures: true },
  { name: "worlds", label: "Library", icon: <FiGlobe />, showsWorkerFigures: true },
  { name: "discover", label: "Discover", icon: <FiCompass />, showsWorkerFigures: false },
] as const;

/** The screens whose figures come from the worker. Read by `tests/ux-honesty.test.mjs`. */
export const WORKER_FIGURE_SCREENS: readonly string[] = [
  ...DESTINATIONS.filter((d) => d.showsWorkerFigures).map((d) => d.name),
  // Reached from the library rather than from the navigation, and every figure on it is the
  // worker's.
  "world",
];

/**
 * The shell owns the settings document, and therefore the theme.
 *
 * It used to be read twice — once here to pick the layout, once inside `DesktopApp` — and each copy
 * drove its own `useResolvedTheme`, i.e. **two independent writers of `document.dataset.theme`**.
 * The outer copy was never refreshed when the Settings screen saved, so choosing Light and then
 * changing the OS scheme let the stale `"system"` preference overwrite the explicit choice. One
 * owner, passed down, is the whole fix.
 */
export function App() {
  const d = useDashboard(EMPTY_DASHBOARD);
  const [settings, setSettings] = useState<SettingsDoc | null>(null);
  useResolvedTheme(settings?.appearance.theme ?? "system");

  useEffect(() => {
    fetchSettings().then(setSettings).catch(() => {});
  }, []);

  return <DesktopApp dashboard={d} settings={settings} onSettings={setSettings} />;
}

function DesktopApp(props: {
  dashboard: Dashboard;
  settings: SettingsDoc | null;
  onSettings: (next: SettingsDoc) => void;
}) {
  // Passed in, never re-subscribed. A second `useDashboard` here meant two `nodera://dashboard`
  // listeners and two initial fetches, and every worker push — four a second — re-rendered both
  // this component and its parent.
  const d = props.dashboard;
  const [sys, setSys] = useState<SystemStats>(EMPTY_SYSTEM);
  const settings = props.settings;
  const setSettings = props.onSettings;
  const [screen, setScreen] = useState<Screen>({ name: "play" });
  const [telemetry, setTelemetry] = useTelemetryStatus();

  useEffect(() => {
    fetchSystemStats().then(setSys).catch(() => {});
    const unSys = onSystemStats(setSys);
    return () => {
      unSys.then((f) => f()).catch(() => {});
    };
  }, []);

  const selected =
    screen.name === "world" ? d.worlds.find((w: World) => w.world_id === screen.id) : undefined;
  const onWorlds = screen.name === "worlds" || screen.name === "world";
  // A screen that renders worker-derived figures must mark them as last-known when the link is down
  // but a previous snapshot exists (A-UX-1). The set is declared beside the navigation rather than
  // re-listed here, so a new screen joins it by being added once.
  const staleWorkerFigures = WORKER_FIGURE_SCREENS.includes(screen.name) && isStale(d.link);

  const openSettings = (section?: SettingsSection) => setScreen({ name: "settings", section });

  return (
    <div className="flex h-full flex-col">
      <ConsentModal status={telemetry} onAnswered={setTelemetry} />
      {/* Raised by the worker's `lan.opened` announcement. Held back while the privacy question is
          unanswered: that one is a gate, and two stacked dialogs is where people answer the wrong. */}
      <LanOfferModal
        lan={d.lan}
        blocked={telemetry.supported && !telemetry.asked}
        onAnswered={() => undefined}
      />

      <TopNav
        d={d}
        active={onWorlds ? "worlds" : screen.name}
        onSelect={(name) => setScreen({ name } as Screen)}
        onSettings={() => openSettings()}
      />

      {/* The `key` is the scroll reset. One scrollport serves every screen, and `scrollTop` is a
          property of the DOM node — without a key React keeps that same node across navigation and a
          screen opened after a long scroll starts halfway down. A new key is a new node at zero. */}
      <main
        id={SCROLLPORT_ID}
        key={screen.name === "world" ? `world:${screen.id}` : screen.name}
        className="min-h-0 flex-1 overflow-y-auto"
      >
        {staleWorkerFigures && (
          <div className="page-canvas pt-4">
            <StaleDataNotice />
          </div>
        )}
        {screen.name === "settings" ? (
          <SettingsScreen
            settings={settings}
            onChange={setSettings}
            initial={screen.section}
            d={d}
            sys={sys}
          />
        ) : screen.name === "discover" ? (
          <NetworkScreen
            onPlay={async (sessionId, worldName) => {
              await launchPlay(sessionId, worldName);
              setScreen({ name: "play", launchWorld: { id: sessionId, name: worldName } });
            }}
          />
        ) : selected ? (
          <WorldScreen world={selected} onBack={() => setScreen({ name: "worlds" })} />
        ) : screen.name === "worlds" ? (
          <WorldsScreen d={d} onOpen={(id) => setScreen({ name: "world", id })} />
        ) : (
          <PlayScreen
            d={d}
            initialWorld={screen.name === "play" ? screen.launchWorld : undefined}
            onOpenWorld={(id) => setScreen({ name: "world", id })}
            onSettings={(section) => openSettings(section as SettingsSection)}
          />
        )}
      </main>
    </div>
  );
}

/**
 * The strip across the top: where you are, and the one control that is not a destination.
 *
 * It floats over the Play screen's hero rather than pushing it down — `--surface-3` is a translucent
 * fill for exactly this — so the art starts at the top of the window and the launcher reads as one
 * surface rather than as a page inside a frame.
 */
function TopNav(props: {
  d: Dashboard;
  active: string;
  onSelect: (name: string) => void;
  onSettings: () => void;
}) {
  const { d } = props;
  const fault = linkFault(d.link);
  const now = useNow(d.link.has_data);
  const age = d.link.last_update_ms ? Math.max(0, now - d.link.last_update_ms) : 0;

  return (
    <header
      className="relative z-20 flex flex-none border-b border-line/70 bg-surface-3 backdrop-blur-xl"
      style={{ height: "var(--top-nav-height)" }}
    >
      <div className="page-canvas flex items-center gap-5">
        <div className="mr-2 flex items-center gap-2.5" title="NoderaMC">
          <span aria-hidden className="grid size-7 rotate-45 place-items-center border border-brand-2/70 bg-brand-2/10 shadow-glow">
            <span className="size-2 bg-brand-1" />
          </span>
          <span className="display-type text-sm font-semibold tracking-tight">NODERA</span>
        </div>

      <nav className="flex h-full items-center gap-1">
        {DESTINATIONS.map((entry) => (
          <button
            key={entry.name}
            aria-current={props.active === entry.name ? "page" : undefined}
            onClick={() => props.onSelect(entry.name)}
            className={cx(
              "relative flex h-full items-center gap-2 px-3 text-sm transition-colors",
              "duration-[var(--motion-fast)] focus-visible:outline-2 focus-visible:outline-focus",
              props.active === entry.name
                ? "font-medium text-text after:absolute after:inset-x-3 after:bottom-0 after:h-0.5 after:bg-brand-1"
                : "text-dim hover:text-text",
            )}
          >
            {entry.icon}
            {entry.label}
          </button>
        ))}
      </nav>

      <div className="ml-auto flex items-center gap-2 text-xs">
        <div className="hidden items-center gap-4 narrow:flex">
        {fault ? (
          <span className="inline-flex items-center gap-1.5 text-danger" title={d.link.last_error}>
            <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />
            {fault}
          </span>
        ) : (
          d.link.has_data && (
            <span className={cx(MONO, "text-faint")} title={d.node.node_id}>
              {shortId(d.node.node_id, 6, 4)} · updated {formatAge(age)}
            </span>
          )
        )}
        {d.node.is_gateway && (
          <span className="rounded-full border border-brand-2/55 bg-brand-2/8 px-[7px] py-0.5 text-[10px] tracking-[0.08em] text-brand-tint">
            GATEWAY
          </span>
        )}
        <span className="flex gap-2.5 font-mono tabular-nums">
          <span className="text-up" title="Upload">
            ▲ {show(d.traffic.up_bytes_per_sec, formatRate)}
          </span>
          <span className="text-down" title="Download">
            ▼ {show(d.traffic.down_bytes_per_sec, formatRate)}
          </span>
        </span>
        </div>
        <button
          onClick={props.onSettings}
          aria-label="Settings"
          title="Settings"
          className="grid h-[30px] w-[30px] place-items-center rounded-sm text-[16px] text-dim transition-colors duration-[var(--motion-fast)] hover:bg-surface-hover hover:text-text focus-visible:outline-2 focus-visible:outline-focus"
        >
          <FiSettings />
        </button>
      </div>
      </div>
    </header>
  );
}

/** A one-second clock, used only where the passage of time is itself the information. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [active]);
  return now;
}
