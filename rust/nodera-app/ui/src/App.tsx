// The Nodera companion app shell.
//
// The rail is the whole navigation: one screen per subject, each owning exactly one. The bottom
// group is separated because About and Settings are about the *app*, and everything above them is
// about the *node*.
//
// Data path: `api::link` (Rust) → `nodera://dashboard` → `useDashboard`. There is no polling loop
// here; the backend emits when the node changes.
import { useEffect, useState } from "react";
import {
  FiCompass,
  FiTag,
  FiGlobe,
  FiHome,
  FiInfo,
  FiPackage,
  FiSettings,
  FiTerminal,
  FiUsers,
} from "react-icons/fi";
import { cx, MONO, SCROLLPORT_ID } from "./components";
import { SettingsScreen } from "./Settings";
import { ConsentModal, useTelemetryStatus } from "./Consent";
import { OverviewScreen } from "./Overview";
import { WorldsScreen } from "./Worlds";
import TrackerStoresScreen from "./TrackerStores";
import { NetworkScreen } from "./Network";
import { PeersScreen } from "./Peers";
import { ConsoleScreen } from "./Console";
import { AboutScreen } from "./About";
import { ModInstallScreen } from "./ModInstall";
import { LanOfferModal } from "./Lan";
import { WorldScreen } from "./World";
import { useResolvedTheme } from "./theme";
import { useIsCompact, useIsMobileBuild } from "./useViewport";
import { MobileApp } from "./mobile/MobileApp";
import {
  EMPTY_DASHBOARD,
  formatAge,
  useDashboard,
  formatRate,
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
  | { name: "overview" }
  | { name: "worlds" }
  | { name: "world"; id: string }
  | { name: "network" }
  | { name: "stores" }
  | { name: "peers" }
  | { name: "console" }
  | { name: "mod" }
  | { name: "about" }
  | { name: "settings" };

/** The rail, in two groups. The second is pinned to the bottom and is about the app, not the node. */
const NODE_SCREENS = [
  { name: "overview", label: "Overview", icon: <FiHome /> },
  { name: "worlds", label: "Worlds", icon: <FiGlobe /> },
  { name: "network", label: "Join a world", icon: <FiCompass /> },
  { name: "stores", label: "Tracker stores", icon: <FiTag /> },
  { name: "peers", label: "Peers", icon: <FiUsers /> },
  { name: "console", label: "Peer console", icon: <FiTerminal /> },
  { name: "mod", label: "Minecraft mod", icon: <FiPackage /> },
] as const;

/**
 * The shell, which picks a layout before it picks a screen.
 *
 * Two conditions, deliberately different. A **compact window** gets the Material 3 phone layout —
 * a desktop window dragged narrow is asking for it. The **peer-only build** gets it too, and gets
 * it regardless of width, because an Android tablet is wide and still has no Java worker to
 * supervise. Either one alone is enough; neither is a proxy for the other.
 */
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
  const compact = useIsCompact();
  const mobileBuild = useIsMobileBuild();
  const [settings, setSettings] = useState<SettingsDoc | null>(null);
  const scheme = useResolvedTheme(settings?.appearance.theme ?? "system");

  useEffect(() => {
    fetchSettings().then(setSettings).catch(() => {});
  }, []);

  // `null` means the build question has not come back yet. One frame of nothing beats a frame of
  // the wrong shell, which on a phone is a visible flash of a desktop rail.
  if (mobileBuild === null) return null;
  if (mobileBuild || compact) {
    return <MobileApp dashboard={d} scheme={scheme} onSettings={setSettings} />;
  }
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
  const [screen, setScreen] = useState<Screen>({ name: "overview" });
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

  return (
    <div className="grid h-full grid-cols-[60px_1fr]">
      <ConsentModal status={telemetry} onAnswered={setTelemetry} />
      {/* Raised by the worker's `lan.opened` announcement. Held back while the privacy question is
          unanswered: that one is a gate, and two stacked dialogs is where people answer the wrong. */}
      <LanOfferModal
        lan={d.lan}
        blocked={telemetry.supported && !telemetry.asked}
        onAnswered={() => undefined}
      />

      <aside className="flex flex-col items-center gap-1.5 border-r border-line bg-rail py-3">
        <div className="mb-2.5 grid h-[34px] w-[34px] place-items-center" title="NoderaMC">
          <span
            className="h-[26px] w-[26px] rounded-sm bg-brand shadow-[0_0_18px_rgba(168,85,247,0.45)]"
            aria-hidden
          />
        </div>
        <nav className="flex flex-col gap-1.5">
          {NODE_SCREENS.map((entry) => (
            <RailButton
              key={entry.name}
              icon={entry.icon}
              label={entry.label}
              active={entry.name === "worlds" ? onWorlds : screen.name === entry.name}
              onClick={() => setScreen({ name: entry.name } as Screen)}
            />
          ))}
        </nav>

        {/* Pinned to the bottom, and set apart by a rule: these two are about the application. */}
        <div className="mt-auto flex flex-col items-center gap-1.5 border-t border-line pt-3">
          <RailButton
            icon={<FiInfo />}
            label="About"
            active={screen.name === "about"}
            onClick={() => setScreen({ name: "about" })}
          />
          <RailButton
            icon={<FiSettings />}
            label="Settings"
            active={screen.name === "settings"}
            onClick={() => setScreen({ name: "settings" })}
          />
        </div>
      </aside>

      <div className="flex min-h-0 min-w-0 flex-col">
        <TopBar d={d} title={titleOf(screen, selected?.name)} />
        {/* The `key` is the scroll reset. One scrollport serves every screen, and `scrollTop` is a
            property of the DOM node — without a key React keeps that same node across navigation
            and a screen opened after a long scroll starts halfway down, or looks frozen when its
            content is shorter than the offset it inherited. A new key is a new node at zero. */}
        <div
          id={SCROLLPORT_ID}
          key={screen.name === "world" ? `world:${screen.id}` : screen.name}
          className="min-h-0 flex-1 overflow-y-auto"
        >
          {screen.name === "settings" ? (
            <SettingsScreen settings={settings} onChange={setSettings} />
          ) : screen.name === "about" ? (
            <AboutScreen />
          ) : screen.name === "network" ? (
            <NetworkScreen />
          ) : screen.name === "stores" ? (
            <div className="max-w-[1100px] px-[26px] pt-5 pb-10">
              <TrackerStoresScreen />
            </div>
          ) : screen.name === "peers" ? (
            <PeersScreen d={d} />
          ) : screen.name === "console" ? (
            <ConsoleScreen d={d} sys={sys} />
          ) : screen.name === "mod" ? (
            <ModInstallScreen />
          ) : selected ? (
            <WorldScreen world={selected} onBack={() => setScreen({ name: "worlds" })} />
          ) : screen.name === "worlds" ? (
            <WorldsScreen d={d} onOpen={(id) => setScreen({ name: "world", id })} />
          ) : (
            <OverviewScreen d={d} sys={sys} />
          )}
        </div>
      </div>
    </div>
  );
}

function titleOf(screen: Screen, worldName?: string): string {
  switch (screen.name) {
    case "stores":
      return "Tracker stores";
    case "overview":
      return "Overview";
    case "worlds":
      return "Worlds";
    case "world":
      return worldName || "World";
    case "network":
      return "Join a world";
    case "peers":
      return "Peers";
    case "console":
      return "Peer console";
    case "mod":
      return "Minecraft mod";
    case "about":
      return "About";
    case "settings":
      return "Settings";
  }
}

function RailButton(props: {
  icon: JSX.Element;
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={cx(
        "relative grid h-[38px] w-[38px] place-items-center rounded-sm text-[17px]",
        "transition-colors duration-150 hover:bg-surface-hover hover:text-text",
        props.active ? "bg-surface-hover text-text" : "text-faint",
      )}
      onClick={props.onClick}
      title={props.label}
      aria-label={props.label}
    >
      {props.active && (
        <span
          aria-hidden
          className="absolute top-2 bottom-2 -left-3 w-[3px] rounded-r-[3px] bg-brand"
        />
      )}
      {props.icon}
    </button>
  );
}

/**
 * The top strip: where you are, who this node is, and what it is moving.
 *
 * There is exactly one link readout in the app and it is here. It states facts — node id, when the
 * peer last reported — and only adds words when something is wrong. The previous version carried
 * two different vocabularies for the same link ("Connected"/"Alone" here and "Live"/"Polling" on
 * the page below), which could disagree with each other on screen.
 */
function TopBar(props: { d: Dashboard; title: string }) {
  const { d } = props;
  const fault = linkFault(d.link);
  const now = useNow(d.link.has_data);
  const age = d.link.last_update_ms ? Math.max(0, now - d.link.last_update_ms) : 0;

  return (
    <header className="flex flex-wrap items-center justify-between gap-4 border-b border-line bg-surface px-5 py-2.5">
      <div className="flex min-w-0 items-center gap-3">
        <h1 className="text-[15px] font-semibold">{props.title}</h1>
        {d.link.has_data && (
          <span className={cx(MONO, "text-faint")} title={d.node.node_id}>
            {shortId(d.node.node_id, 8, 4)}
          </span>
        )}
        {d.node.is_gateway && (
          <span className="rounded-full border border-brand-2/55 px-[7px] py-0.5 text-[10px] tracking-[0.06em] text-brand-2">
            GATEWAY
          </span>
        )}
      </div>

      <div className="flex items-center gap-4 text-sm">
        {fault ? (
          <span className="inline-flex items-center gap-1.5 text-danger" title={d.link.last_error}>
            <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />
            {fault}
          </span>
        ) : (
          <span className={cx(MONO, "text-faint")}>
            {d.link.has_data ? `updated ${formatAge(age)}` : ""}
          </span>
        )}
        <span className="flex gap-3 font-mono tabular-nums">
          <span className="text-up" title="Upload">
            ▲ {show(d.traffic.up_bytes_per_sec, formatRate)}
          </span>
          <span className="text-down" title="Download">
            ▼ {show(d.traffic.down_bytes_per_sec, formatRate)}
          </span>
        </span>
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
