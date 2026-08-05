// The Nodera launcher shell.
//
// # Three destinations, not nine
//
// The navigation used to carry one screen per subsystem — Overview, Worlds, Join, Tracker stores,
// Peers, Peer console, Minecraft mod, About, Settings — which is an accurate map of the software
// and a useless one for a player. What they came to do is play; everything else is either about a
// world (one click from the library) or about the machinery (Settings).
//
//   Play      the hero, the world picker, and the button
//   Worlds    the library, as art rather than as a table
//   Discover  what is joinable on the network right now
//   ⚙         everything that used to have its own entry
//
// # Where they live
//
// In a left rail, not a top bar. Three destinations do not need 64px of window height across the
// top of every screen, and the node facts that shared that strip were hidden below 900px — so the
// app said nothing about its own identity on a small window. `Rail.tsx` has the reasoning.
//
// Two tables, deliberately. `DESTINATIONS` is the places you go to do the thing the app is for,
// and it is the list A-UX-1 is enforced against. `UTILITIES` is the preferences drawer, pinned to
// the bottom of the rail — a preference is not a destination, it shows no worker figures, and
// keeping it out of `DESTINATIONS` keeps that table's shape exactly as the exit test parses it.
//
// # The window is the app's too
//
// The desktop window is undecorated (`tauri.conf.json`), so the minimise / maximise / close buttons
// are drawn here rather than by the window manager — they were the last part of the interface still
// wearing the operating system's design instead of this one. `TitleBar.tsx` has the reasoning and
// the eight resize grips an undecorated window has to grow for itself.
//
// # Data path
//
// `api::link` (Rust) → `nodera://dashboard` → `useDashboard`. There is no polling loop here; the
// backend emits when the node changes.
import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { FiCompass, FiDroplet, FiGlobe, FiPlay, FiSettings } from "react-icons/fi";
import { SCROLLPORT_ID, StaleDataNotice } from "./components";
import { Rail } from "./Rail";
import { TitleBar } from "./TitleBar";
import { ThemeScreen } from "./ThemeScreen";
import { ESCAPE_ID, applyCustomTheme, useCustomTheme } from "./customtheme";
import { SettingsScreen, type Section as SettingsSection } from "./Settings";
import { ConsentModal, useTelemetryStatus } from "./Consent";
import { PlayScreen } from "./PlayScreen";
import { WorldsScreen } from "./Worlds";
import { NetworkScreen } from "./Network";
import { LanOfferModal } from "./Lan";
import { WorldScreen } from "./World";
import { launchPlay } from "./play";
import { useResolvedTheme } from "./theme";
import { EMPTY_DASHBOARD, useDashboard, isStale, type Dashboard, type World } from "./api";
import {
  fetchSystemStats,
  onSystemStats,
  fetchSettings,
  saveSettings,
  EMPTY_SYSTEM,
  type SystemStats,
  type Settings as SettingsDoc,
} from "./ipc";

type Screen =
  | { name: "play"; launchWorld?: { id: string; name: string } }
  | { name: "worlds" }
  | { name: "world"; id: string }
  | { name: "discover" }
  | { name: "theme" }
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

/**
 * The bottom of the rail: preferences, not places.
 *
 * A separate table rather than a fourth destination. Settings is the drawer the other six screens
 * were folded into, it renders no worker figure, and `DESTINATIONS` above is parsed by the A-UX-1
 * exit test — so the set that gates the stale banner stays exactly the set of screens that show the
 * worker's numbers, with nothing in it that is not one.
 */
const UTILITIES = [
  { name: "theme", label: "Theme", icon: <FiDroplet /> },
  { name: "settings", label: "Settings", icon: <FiSettings /> },
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
  // A custom appearance is a *patch on a base scheme*, so it decides which scheme is underneath it
  // and `theme.ts` is still the only thing that writes `dataset.theme`. A missing or stale
  // `selected` id resolves to undefined and the app behaves exactly as it did before this existed.
  const themes = settings?.appearance.themes;
  const custom = themes?.custom.find((t) => t.id === themes.selected);
  const resolved = useResolvedTheme(custom ? custom.base : (settings?.appearance.theme ?? "system"));
  useCustomTheme(custom);

  useEffect(() => {
    fetchSettings().then(setSettings).catch(() => {});
  }, []);

  return (
    <>
      <DesktopApp
        dashboard={d}
        settings={settings}
        onSettings={setSettings}
        resolved={resolved}
      />
      <EscapeHatch active={Boolean(custom)} settings={settings} onSettings={setSettings} />
    </>
  );
}

/**
 * The way out of an appearance that made the window unusable.
 *
 * Rendered on every path, into `document.body` rather than into `#root` — a sibling of the app, so
 * no selector rooted at `#root` can reach it, and `position: fixed` escapes any ancestor overflow
 * (its only possible containing blocks are `html` and `body`, whose `transform` the guard pins to
 * `none`).
 *
 * It carries `data-nodera-escape` only while a custom appearance is in force, which is the only
 * time there is anything to escape from — and that decision is React state, not a stylesheet, so
 * nothing a theme can write reaches it. `customtheme.ts` then re-asserts every one of its styles
 * inline at `!important`, which outranks every important author rule in every layer and is what
 * survives an engine that does not implement `@layer` at all.
 *
 * Pressing it also *persists* the reset, because a kept theme that locked the window out has to
 * stay gone across a restart.
 */
function EscapeHatch(props: {
  active: boolean;
  settings: SettingsDoc | null;
  onSettings: (next: SettingsDoc) => void;
}) {
  return createPortal(
    <button
      id={ESCAPE_ID}
      type="button"
      data-nodera-escape={props.active ? "" : undefined}
      hidden={!props.active}
      onClick={() => {
        applyCustomTheme(undefined);
        const settings = props.settings;
        if (!settings) return;
        const next: SettingsDoc = JSON.parse(JSON.stringify(settings));
        next.appearance.themes.selected = "";
        saveSettings(next).then(() => props.onSettings(next)).catch(() => {});
      }}
    >
      Reset appearance
    </button>,
    document.body,
  );
}

function DesktopApp(props: {
  dashboard: Dashboard;
  settings: SettingsDoc | null;
  onSettings: (next: SettingsDoc) => void;
  resolved: "dark" | "light";
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
    // `w-full min-w-0` is load-bearing, not tidiness. The guard layer makes `#root` a flex
    // container (`display: flex !important` — a theme must never be able to un-display the app),
    // which makes this div a **flex item**, and a flex item with `width: auto` is shrink-to-fit:
    // it sized itself to its own content and ignored the window. That is one bug wearing two
    // faces. On a wide window the shell stopped at the width of the widest card and left the rest
    // of the display empty; on a narrow or square one the shell's min-content — a settings tab
    // strip that does not wrap — was *wider* than the window, and `body { overflow: hidden }`
    // silently cut the right-hand edge off every card, badge and tab.
    //
    // `w-full` pins the shell to the window. `min-w-0` lets it shrink below its own min-content, so
    // anything irreducibly wide overflows *inside* a container that can scroll it (the tab strip,
    // `DataTable`) instead of pushing the whole app sideways under a clip.
    //
    // Deliberately no `overflow-hidden` here: the rail's identity popover is absolutely positioned
    // and wider than the collapsed rail, and clipping at this level would eat it.
    <div className="flex h-full w-full min-w-0 flex-row">
      <ConsentModal status={telemetry} onAnswered={setTelemetry} />
      {/* Raised by the worker's `lan.opened` announcement. Held back while the privacy question is
          unanswered: that one is a gate, and two stacked dialogs is where people answer the wrong. */}
      <LanOfferModal
        lan={d.lan}
        blocked={telemetry.supported && !telemetry.asked}
        onAnswered={() => undefined}
      />

      <Rail
        d={d}
        active={onWorlds ? "worlds" : screen.name}
        destinations={DESTINATIONS}
        utilities={UTILITIES}
        onSelect={(name) => (name === "settings" ? openSettings() : setScreen({ name } as Screen))}
      />

      {/* The content column, and the reason it exists is the bar at the top of it.
          The window has no decorations — `tauri.conf.json` says so — which means the minimise,
          maximise and close buttons are the app's to draw. They belong *beside* the rail rather
          than above it: the rail runs to the top edge of the window carrying the wordmark, exactly
          as the reference panels do, and the caption strip sits over the page in the same corner
          the reference puts its own window controls. A bar spanning the whole window would push
          the rail down and leave a band of page background above it, which is the arrangement that
          made the buttons read as somebody else's frame in the first place. */}
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <TitleBar />

        {/* The `key` is the scroll reset. One scrollport serves every screen, and `scrollTop` is a
          property of the DOM node — without a key React keeps that same node across navigation and a
          screen opened after a long scroll starts halfway down. A new key is a new node at zero.

          It scrolls in **one** axis. `overflow-y: auto` alone is not that: the spec computes an
          `overflow-x: visible` to `auto` the moment the other axis is not visible, so the scrollport
          would answer a too-wide child by growing a horizontal scrollbar under the whole app — the
          page sliding sideways past the rail. Naming `overflow-x: hidden` puts the horizontal
          boundary here and forces the decision downwards: content that genuinely cannot get
          narrower carries its own `overflow-x-auto` (the settings tab strip, `DataTable`, `Tabs`)
          and scrolls inside itself. */}
        <main
          id={SCROLLPORT_ID}
          key={screen.name === "world" ? `world:${screen.id}` : screen.name}
          className="min-h-0 min-w-0 flex-1 overflow-x-hidden overflow-y-auto"
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
              onOpenTheme={() => setScreen({ name: "theme" })}
            />
          ) : screen.name === "theme" ? (
            <ThemeScreen settings={settings} onChange={setSettings} resolved={props.resolved} />
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
    </div>
  );
}
