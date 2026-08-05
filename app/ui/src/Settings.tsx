// The settings screen: Appearance · Behavior · Network · Storage · Privacy.
//
// Settings save on change rather than behind an Apply button — every control here is independent,
// so there is no half-valid intermediate state an explicit commit would be protecting. A rejected
// document (the backend validates) surfaces inline and the previous value stays on screen.
import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  FiMonitor,
  FiMoon,
  FiSun,
  FiPower,
  FiWifi,
  FiHardDrive,
  FiEye,
  FiAlertCircle,
  FiRefreshCw,
  FiShield,
  FiTag,
  FiUsers,
  FiPackage,
  FiTerminal,
  FiInfo,
} from "react-icons/fi";
import TrackerStoresScreen from "./TrackerStores";
import { PeersScreen } from "./Peers";
import { ModInstallScreen } from "./ModInstall";
import { ConsoleScreen } from "./Console";
import { AboutScreen } from "./About";
import { PrivacyCard, useTelemetryStatus } from "./Consent";
import {
  Card,
  Toggle,
  Segmented,
  NumberField,
  TextField,
  Slider,
  Disclosure,
  StatusBadge,
  PageHeader,
  cx,
  resetScrollport,
} from "./components";
import {
  saveSettings,
  fetchSettingStatus,
  fetchConfigStatus,
  fetchWorkerOwnership,
  fetchSettingsFault,
  restartWorker,
  resolvedServices,
  EMPTY_CONFIG_STATUS,
  type ResolvedEndpoint,
  type Settings as SettingsDoc,
  type SettingStatus,
  type SettingState,
  type ConfigStatus,
  type WorkerOwnership,
  type Theme,
  type NetworkPolicy,
} from "./ipc";
import { formatBytes, type Dashboard } from "./api";
import { fetchStorageInfo, type StorageInfo, type SystemStats } from "./ipc";

/**
 * Settings is where everything that is not playing lives now.
 *
 * The rail used to carry nine destinations, six of which were about the machinery: tracker stores,
 * peers, the worker console, the mod installer, an overview of counters, and About. A launcher has
 * one front page and a settings drawer, so those became sections here — the Worlds screen in
 * particular was carrying trackers, piece grids and store links, which is why it read as a report
 * rather than a library.
 */
export type Section =
  | "appearance"
  | "behavior"
  | "network"
  | "stores"
  | "peers"
  | "storage"
  | "minecraft"
  | "diagnostics"
  | "privacy"
  | "about";

/**
 * Ten sections, three groups, in the order the questions get asked.
 *
 * A flat list of ten is a list you read from the top every time. Panel 06's nav card groups its
 * entries under quiet uppercase headings, and this screen has wanted that since it absorbed six
 * former destinations — the three groups answer *what is this about*: your node on the network,
 * this computer, or who you trust.
 *
 * The `group` is only read by the wide sidebar. The narrow tab strip stays flat, because a
 * horizontal strip has nowhere to put a heading.
 */
type Group = "YOUR NODE" | "THIS COMPUTER" | "TRUST";

const SECTIONS: { id: Section; label: string; icon: JSX.Element; group: Group }[] = [
  { id: "appearance", label: "Appearance", icon: <FiEye />, group: "THIS COMPUTER" },
  { id: "behavior", label: "Behavior", icon: <FiPower />, group: "YOUR NODE" },
  { id: "network", label: "Network", icon: <FiWifi />, group: "YOUR NODE" },
  { id: "stores", label: "Tracker stores", icon: <FiTag />, group: "TRUST" },
  { id: "peers", label: "Peers", icon: <FiUsers />, group: "YOUR NODE" },
  { id: "storage", label: "Storage", icon: <FiHardDrive />, group: "YOUR NODE" },
  { id: "minecraft", label: "Minecraft", icon: <FiPackage />, group: "THIS COMPUTER" },
  { id: "diagnostics", label: "Diagnostics", icon: <FiTerminal />, group: "THIS COMPUTER" },
  { id: "privacy", label: "Privacy", icon: <FiShield />, group: "TRUST" },
  { id: "about", label: "About", icon: <FiInfo />, group: "TRUST" },
];

const GROUPS: readonly Group[] = ["YOUR NODE", "THIS COMPUTER", "TRUST"];

const SECTION_COPY: Record<Section, string> = {
  appearance: "Theme and desktop presentation.",
  behavior: "Startup, battery, and background operation.",
  network: "Discovery, reachability, and transfer limits.",
  stores: "Published tracker and relay directories you trust.",
  peers: "Live connections and service reachability.",
  storage: "Archive locations and replication budgets.",
  minecraft: "Detected installations and bundled mod management.",
  diagnostics: "Worker health, resource use, and bounded logs.",
  privacy: "Telemetry consent and exact collection schema.",
  about: "Build identity and third-party packages.",
};

/** Bytes/sec presets for the speed sliders; index 0 is "unlimited". */
const SPEEDS = [0, 64_000, 128_000, 256_000, 512_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000,
  25_000_000, 50_000_000];

/**
 * Turn one key's backend status into the badge beside its control — or nothing at all.
 *
 * Four outcomes, deliberately not collapsed into two. `live` shows nothing: a working control
 * needs no decoration, and badging everything trains people to ignore badges. The other three read
 * differently on purpose — "the worker is too old" is fixed by updating, "applies after restart"
 * is fixed by restarting, and a permanent structural limitation is fixed by nothing, so it must
 * not wear the same amber "yet" badge that implies it is coming.
 */
function badgeFor(status: SettingStatus | undefined) {
  if (!status || status.state === "live") return undefined;
  const BADGE: Record<Exclude<SettingState, "live">, { tone: "warn" | "muted" | "info"; label: string }> = {
    restart_required: { tone: "info", label: "after restart" },
    unsupported_by_worker: { tone: "warn", label: "worker too old" },
    unenforced: { tone: "warn", label: "not enforced yet" },
  };
  const spec = BADGE[status.state];
  // A structural limitation is permanent; a worker being offline or refusing one value is not.
  //
  // This used to be "unenforced with any reason", which caught both — so with the worker down,
  // every network and battery control was badged muted "not supported", i.e. *permanently
  // impossible*, when the truth was "saved, and it will apply when your node is back". The backend
  // distinguishes the cases in its wording (`settings.rs::resolve`); matching on the transient
  // phrasings is what keeps this side honest without a second wire field to drift.
  const transient =
    status.reason.includes("offline") ||
    status.reason.includes("did not confirm") ||
    status.reason.includes("older than the app");
  const permanent =
    status.state === "unenforced" && status.reason.length > 0 && !transient;
  return (
    <StatusBadge
      tone={permanent ? "muted" : spec.tone}
      label={permanent ? "not supported" : spec.label}
      title={status.reason || "Saved to your settings file, but nothing applies it yet."}
    />
  );
}

/**
 * The locations this machine can actually write to, each with its free space.
 *
 * Probed by the backend, not listed here: "can this process write there" is a question only the
 * filesystem can answer, and answering it in the UI would be guessing.
 */
function StorageChoices(props: { current: string; onPick: (path: string) => void }) {
  const [info, setInfo] = useState<StorageInfo | null>(null);

  useEffect(() => {
    fetchStorageInfo().then(setInfo).catch(() => {});
  }, []);

  if (!info || info.options.length === 0) return null;
  // An empty setting means "the worker's own default", and the backend already resolves that to a
  // real path in `info.current` — which is always one of the options. Comparing against the raw
  // setting left **no** chip highlighted on a default install, so the location actually in force
  // looked like one nobody had chosen.
  const inUse = props.current.trim() === "" ? info.current : props.current;
  return (
    <div className="flex flex-wrap gap-2 pt-1">
      {info.options.map((option) => (
        <button
          key={option.path}
          type="button"
          disabled={!option.writable}
          title={option.writable ? option.path : "not writable"}
          onClick={() => props.onPick(option.path)}
          className={cx(
            "rounded-sm border px-2.5 py-1 text-xs",
            inUse === option.path
              ? "border-brand-2 text-text"
              : "border-line text-dim hover:bg-surface-hover",
            !option.writable && "opacity-40",
          )}
        >
          {option.label}
          {option.free_bytes !== null && (
            <span className="ml-1.5 text-faint">{formatBytes(option.free_bytes)} free</span>
          )}
        </button>
      ))}
    </div>
  );
}

const TAB_BTN =
  "flex shrink-0 items-center gap-2 border-b-2 px-3.5 py-2.5 text-sm transition-colors";
const NESTED = "my-0.5 ml-3.5 border-l-2 border-line pl-3.5";

export function SettingsScreen(props: {
  settings: SettingsDoc | null;
  onChange: (next: SettingsDoc) => void;
  /** Which section to open on, when something sent the user here to fix one thing. */
  initial?: Section;
  /** Live node figures, for the sections that report on the node rather than configure it. */
  d: Dashboard;
  sys: SystemStats;
}) {
  const [section, setSection] = useState<Section>(props.initial ?? "appearance");
  const [statuses, setStatuses] = useState<SettingStatus[]>([]);
  const [config, setConfig] = useState<ConfigStatus>(EMPTY_CONFIG_STATUS);
  const [ownership, setOwnership] = useState<WorkerOwnership | null>(null);
  const [error, setError] = useState<string>("");
  const [restarting, setRestarting] = useState(false);
  // Why the stored settings could not be read, or "". Non-empty means every control below is
  // showing a default while the user's real document sits on disk untouched — which has to be said
  // on the screen holding the defaults, or the user re-enters their whole configuration on top of a
  // file that was never the problem. The phone screen has said this all along; the desktop one did
  // not, which is the half of A-UX-5 this closes.
  const [fault, setFault] = useState<string>("");
  // Read from the worker, never from this app's own idea of it: the consent record lives on the
  // node, and the in-game `/nodera telemetry` command can change it while this screen is open.
  const [telemetry, setTelemetry] = useTelemetryStatus();

  // Statuses are re-read after every save, because a push can change them: a key moves to `live`
  // only once the worker has confirmed it, so the badges settle a beat after the toggle does.
  const refreshStatus = useCallback(() => {
    fetchSettingStatus().then(setStatuses).catch(() => {});
    fetchConfigStatus().then(setConfig).catch(() => {});
  }, []);

  useEffect(() => {
    refreshStatus();
    fetchWorkerOwnership().then(setOwnership).catch(() => {});
    fetchSettingsFault().then(setFault).catch(() => {});
    const timer = window.setInterval(refreshStatus, 3000);
    return () => window.clearInterval(timer);
  }, [refreshStatus]);

  const s = props.settings;
  if (!s) return <div className="p-6 text-dim">Loading settings…</div>;

  const note = (key: string) => badgeFor(statuses.find((st) => st.key === key));

  const update = (mutate: (draft: SettingsDoc) => void) => {
    const next: SettingsDoc = JSON.parse(JSON.stringify(s));
    mutate(next);
    props.onChange(next);
    // Persisting is what must not fail; the worker push is asynchronous and reports separately, so
    // an offline worker never costs the user their setting.
    saveSettings(next)
      .then(() => {
        setError("");
        window.setTimeout(refreshStatus, 400);
      })
      .catch((e) => setError(String(e)));
  };

  const restartNeeded = config.restart_required.length > 0;

  return (
    <div className="flex min-h-full flex-col">
      <nav
        className="sticky top-0 z-10 flex gap-1 overflow-x-auto border-b border-line-soft bg-bg/95 px-[var(--canvas-gutter)] backdrop-blur wide:hidden"
        role="tablist"
      >
        {SECTIONS.map((sec) => (
          <button
            key={sec.id}
            role="tab"
            aria-selected={section === sec.id}
            className={cx(
              TAB_BTN,
              section === sec.id
                ? "border-b-brand-1 font-medium text-text"
                : "border-b-transparent text-dim hover:text-text",
            )}
            onClick={() => {
              setSection(sec.id);
              resetScrollport();
            }}
          >
            {sec.icon}
            {sec.label}
          </button>
        ))}
      </nav>

      <div className="page-canvas page-grid py-8">
        <aside className="col-span-3 hidden pr-6 wide:block">
          <div className="sticky top-6 rounded-md border border-line-soft bg-surface p-3 shadow-e1">
            <nav className="flex flex-col gap-1" aria-label="Settings sections">
              {GROUPS.map((group) => (
                <div key={group} className="mb-2 flex flex-col gap-1 last:mb-0">
                  <p className="mt-2 mb-1 px-3 text-2xs font-medium tracking-[0.16em] text-faint uppercase">
                    {group}
                  </p>
                  {SECTIONS.filter((sec) => sec.group === group).map((sec) => (
                    <button
                      key={sec.id}
                      aria-current={section === sec.id ? "page" : undefined}
                      className={cx(
                        "flex items-center gap-3 rounded-sm border px-3 py-2 text-left text-sm transition-colors duration-[var(--motion-fast)]",
                        "focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus",
                        section === sec.id
                          ? "border-brand-edge bg-brand-soft font-medium text-text"
                          : "border-transparent text-dim hover:bg-surface-hover hover:text-text",
                      )}
                      onClick={() => {
                        setSection(sec.id);
                        resetScrollport();
                      }}
                    >
                      <span className={section === sec.id ? "text-brand-tint" : "text-faint"}>{sec.icon}</span>
                      {sec.label}
                    </button>
                  ))}
                </div>
              ))}
            </nav>
          </div>
        </aside>

      <section aria-label="Settings content" className="col-span-12 flex min-w-0 flex-col gap-4 wide:col-span-9">
        <PageHeader
          eyebrow="Launcher configuration"
          title={SECTIONS.find((entry) => entry.id === section)?.label ?? "Settings"}
          description={SECTION_COPY[section]}
          className="mb-2"
        />
        {error && (
          <div className="flex items-center gap-2 rounded-sm border border-danger/45 bg-danger/12 px-3.5 py-2.5 text-sm text-danger">
            <FiAlertCircle aria-hidden /> {error}
          </div>
        )}

        {fault && (
          <div className="flex items-start gap-2 rounded-sm border border-warn/45 bg-warn/12 px-3.5 py-2.5 text-sm text-warn">
            <FiAlertCircle aria-hidden className="mt-0.5 flex-none" />
            <span>
              Your saved settings could not be read, so these are defaults. The file has been left
              alone — {fault}
            </span>
          </div>
        )}

        {/* Soft status, never an error: the setting is already saved either way. */}
        {config.pushed && !config.supported && (
          <div className="flex items-center gap-2 rounded-sm border border-warn/45 bg-warn/12 px-3.5 py-2.5 text-sm text-warn">
            <FiAlertCircle aria-hidden /> This peer worker is older than the app and does not accept
            configuration. Limits below are saved but not applied.
          </div>
        )}
        {!config.pushed && config.error && (
          <div className="flex items-center gap-2 rounded-sm border border-line bg-surface-2 px-3.5 py-2.5 text-sm text-dim">
            <FiAlertCircle aria-hidden /> Saved. The worker has not confirmed yet — {config.error}
          </div>
        )}

        {restartNeeded && (
          <div className="flex flex-wrap items-center gap-3 rounded-sm border border-down/40 bg-down/12 px-3.5 py-2.5 text-sm text-down">
            <FiRefreshCw aria-hidden />
            <span className="flex-1">
              Some changes apply when the peer worker restarts.
            </span>
            {ownership?.can_restart ? (
              <button
                className="rounded-sm border border-down/50 px-2.5 py-1 text-xs hover:bg-down/12 disabled:opacity-50"
                disabled={restarting}
                onClick={() => {
                  setRestarting(true);
                  restartWorker()
                    .catch((e) => setError(String(e)))
                    .finally(() => window.setTimeout(() => setRestarting(false), 1500));
                }}
              >
                {restarting ? "Restarting…" : "Restart worker"}
              </button>
            ) : (
              // Two reasons the button is absent, and they need different sentences:
              //   * attached — the worker belongs to whoever started it (scripts/dev.sh, an
              //     operator), and offering to kill someone else's process would be wrong;
              //   * mobile — the worker is a thread in this app, so it cannot be cycled without
              //     the app. Telling a phone user to "restart it where you started it" told them
              //     nothing (M-NET-3).
              <span className="text-xs opacity-80">
                {ownership?.attached
                  ? "Restart it where you started it."
                  : "Close and reopen the app to apply these."}
              </span>
            )}
          </div>
        )}

        {section === "appearance" && (
          <Card title="Interface">
            <Segmented<Theme>
              label="Theme"
              hint="System follows your desktop's light/dark preference."
              value={s.appearance.theme}
              options={[
                { value: "system", label: "System", icon: <FiMonitor /> },
                { value: "dark", label: "Dark", icon: <FiMoon /> },
                { value: "light", label: "Light", icon: <FiSun /> },
              ]}
              onChange={(theme) => update((d) => (d.appearance.theme = theme))}
            />
            <Toggle
              label="Notifications"
              hint="Raise a desktop notification when node events happen."
              checked={s.appearance.notifications}
              note={note("appearance.notifications")}
              onChange={(v) => update((d) => (d.appearance.notifications = v))}
            />
          </Card>
        )}

        {section === "behavior" && (
          <div className="grid grid-cols-1 gap-4 wide:grid-cols-2">
            <Card title="Startup">
              <Toggle
                label="Auto-start"
                hint="Launch Nodera at login so your worlds stay on the network without opening the app."
                checked={s.behavior.auto_start}
                onChange={(v) => update((d) => (d.behavior.auto_start = v))}
              />
            </Card>

            <Card
              title="Power management"
              hint="Keep the node from draining a laptop while it is away from a charger."
            >
              <Toggle
                label="Only transfer while charging"
                hint="Pause upload and download whenever the device is on battery."
                checked={s.behavior.only_when_charging}
                note={note("behavior.only_when_charging")}
                onChange={(v) => update((d) => (d.behavior.only_when_charging = v))}
              />
              <Toggle
                label="Battery control"
                hint="Stop transferring once the battery falls below a threshold."
                checked={s.behavior.battery_control}
                note={note("behavior.battery_control")}
                onChange={(v) => update((d) => (d.behavior.battery_control = v))}
              />
              {s.behavior.battery_control && (
                <div className={NESTED}>
                  <Slider
                    label="Stop below"
                    hint="Transfers resume once the battery climbs back above this level."
                    value={s.behavior.battery_threshold_percent}
                    min={5}
                    max={95}
                    suffix="%"
                    onChange={(v) => update((d) => (d.behavior.battery_threshold_percent = v))}
                  />
                  {/* Revealed only once battery control is on — on its own it would have nothing
                      to modify, and an always-visible dead toggle is just a puzzle. */}
                  <Toggle
                    label="Apply even while the game is open"
                    hint="Off by default: pausing the swarm mid-session is the one moment you would notice it."
                    checked={s.behavior.power_rules_during_game}
                    note={note("behavior.power_rules_during_game")}
                    onChange={(v) => update((d) => (d.behavior.power_rules_during_game = v))}
                  />
                </div>
              )}
            </Card>
          </div>
        )}

        {section === "network" && (
          <div className="grid grid-cols-1 gap-4 wide:grid-cols-2">
            <Card
              title="Default trackers"
              hint="Discovery services this node announces its worlds to and queries for peers. One per line — tcp://host:port; a bare host:port means TCP."
              right={note("network.default_trackers")}
            >
              <textarea
                className="w-full resize-y rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] font-mono text-[12px] leading-relaxed focus:border-brand-1 focus:outline-none"
                rows={4}
                value={s.network.default_trackers.join("\n")}
                onChange={(e) =>
                  update(
                    (d) =>
                      (d.network.default_trackers = e.target.value
                        .split("\n")
                        .map((l) => l.trim())
                        .filter(Boolean)),
                  )
                }
              />
            </Card>

            <Card
              title="Rendezvous relays"
              hint="Used to reach peers that cannot accept an inbound connection. One per line, same syntax as the trackers."
              right={note("network.rendezvous_endpoints")}
            >
              <textarea
                className="w-full resize-y rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] font-mono text-[12px] leading-relaxed focus:border-brand-1 focus:outline-none"
                rows={3}
                value={s.network.rendezvous_endpoints.join("\n")}
                onChange={(e) =>
                  update(
                    (d) =>
                      (d.network.rendezvous_endpoints = e.target.value
                        .split("\n")
                        .map((l) => l.trim())
                        .filter(Boolean)),
                  )
                }
              />
            </Card>

            {/* The two boxes above are what this install typed. They are not the whole list, and
                omitting the rest is what made a store look inert: a fresh install has empty boxes,
                one built-in store, and a screen that appeared to say it had no trackers. */}
            <StoreContributions />

            {/* A cost rule, not a speed limit — which is why it is not in the bandwidth card. On a
                phone this is the difference between a helpful node and an unexpected bill; on a
                desktop it is normally inert, and says so. */}
            <Card
              title="Move data over"
              hint="Which connections this node will use to carry other people's worlds."
              right={note("network.transfer_network")}
            >
              <Segmented
                label="Allowed connections"
                value={s.network.transfer_network}
                options={[
                  { value: "any", label: "Any" },
                  { value: "unmetered_only", label: "Unmetered only" },
                  { value: "wifi_only", label: "Wi-Fi only" },
                ]}
                onChange={(v) =>
                  update((d) => (d.network.transfer_network = v as NetworkPolicy))
                }
              />
            </Card>

            <Card title="Connections">
              <Toggle
                label="Unlimited connections only"
                hint="Skip peers that advertise a connection cap of their own."
                checked={s.network.unlimited_connections_only}
                note={note("network.unlimited_connections_only")}
                onChange={(v) => update((d) => (d.network.unlimited_connections_only = v))}
              />
              <Toggle
                label="Use random port"
                hint="Let the OS assign the listening port. Turn off to pin a range your router forwards."
                checked={s.network.use_random_port}
                note={note("network.use_random_port")}
                onChange={(v) => update((d) => (d.network.use_random_port = v))}
              />
              {!s.network.use_random_port && (
                <div className={cx(NESTED, "grid grid-cols-2 gap-x-5 max-narrow:grid-cols-1")}>
                  <NumberField
                    label="Port range start"
                    value={s.network.port_range_start}
                    min={1}
                    max={65535}
                    note={note("network.port_range")}
                    onChange={(v) => update((d) => (d.network.port_range_start = v))}
                  />
                  <NumberField
                    label="Port range end"
                    value={s.network.port_range_end}
                    min={1}
                    max={65535}
                    note={note("network.port_range")}
                    onChange={(v) => update((d) => (d.network.port_range_end = v))}
                  />
                </div>
              )}
            </Card>

            <Card title="Limits" hint="0 means unlimited.">
              <NumberField
                label="Max. connections"
                value={s.network.max_connections}
                min={0}
                note={note("network.max_connections")}
                onChange={(v) => update((d) => (d.network.max_connections = v))}
              />
              <NumberField
                label="Max. connections per world"
                value={s.network.max_connections_per_world}
                min={0}
                note={note("network.max_connections_per_world")}
                onChange={(v) => update((d) => (d.network.max_connections_per_world = v))}
              />
              <NumberField
                label="Max. upload slots per world"
                value={s.network.max_upload_slots_per_world}
                min={0}
                note={note("network.max_upload_slots_per_world")}
                onChange={(v) => update((d) => (d.network.max_upload_slots_per_world = v))}
              />
            </Card>

            <Card title="Speed">
              <SpeedSlider
                label="Max. upload speed"
                value={s.network.max_upload_bytes_per_sec}
                note={note("network.max_upload_bytes_per_sec")}
                onChange={(v) => update((d) => (d.network.max_upload_bytes_per_sec = v))}
              />
              <SpeedSlider
                label="Max. download speed"
                value={s.network.max_download_bytes_per_sec}
                note={note("network.max_download_bytes_per_sec")}
                onChange={(v) => update((d) => (d.network.max_download_bytes_per_sec = v))}
              />
            </Card>
          </div>
        )}

        {section === "storage" && (
          <div className="grid grid-cols-1 gap-4 wide:grid-cols-2">
          <Card
            title="Storage"
            hint="Worlds you hold for other people — the copies that keep a world alive when its host goes offline."
          >
            <TextField
              label="Save peer worlds to"
              hint="Leave empty to use the worker's default (~/.nodera/archive)."
              placeholder="~/.nodera/archive"
              value={s.storage.peer_worlds_dir}
              note={note("storage.peer_worlds_dir")}
              onChange={(v) => update((d) => (d.storage.peer_worlds_dir = v))}
            />
            {/* Offered beside the field rather than instead of it: a desktop user may type any
                path, and this only saves them finding out after the fact that a location is not
                writable — every option here was probed with a real write. */}
            <StorageChoices
              current={s.storage.peer_worlds_dir}
              onPick={(path) => update((d) => (d.storage.peer_worlds_dir = path))}
            />
            <Disclosure title="What gets stored here?">
              <p>
                Content-addressed pieces of world archives, each verified against its manifest hash
                before it is written. Nothing here is trusted on read: a piece that no longer
                matches its hash is discarded and re-fetched.
              </p>
            </Disclosure>
          </Card>

        {/* The worker has honoured both of these since the replication lane landed and the app
            never sent either, so a node's disk budget was whatever the worker decided. */}
          <Card
            title="Supporting other people's worlds"
            hint="How much of this machine goes to keeping worlds you do not own alive."
          >
            <NumberField
              label="Disk budget (GB)"
              hint="0 uses the worker's own default."
              value={Math.round(s.storage.replication_budget_bytes / 1_000_000_000)}
              min={0}
              note={note("storage.replication_budget_bytes")}
              onChange={(v) =>
                update((d) => (d.storage.replication_budget_bytes = Math.max(0, v) * 1_000_000_000))
              }
            />
            <NumberField
              label="Sweep every (seconds)"
              hint="How often the node re-checks what needs replicating. 0 uses the worker's default; it clamps anything under 30."
              value={s.storage.replication_sweep_seconds}
              min={0}
              note={note("storage.replication_sweep_seconds")}
              onChange={(v) => update((d) => (d.storage.replication_sweep_seconds = Math.max(0, v)))}
            />
          </Card>
          </div>
        )}

        {/* The five sections below are screens that used to have their own rail entries. They are
            not settings in the "toggle a preference" sense — they are the machinery, and the point
            of moving them is that a launcher's front page should not be a report about a daemon. */}
        {section === "stores" && <TrackerStoresScreen />}

        {section === "peers" && <PeersScreen d={props.d} />}

        {section === "minecraft" && <ModInstallScreen />}

        {section === "diagnostics" && <ConsoleScreen d={props.d} sys={props.sys} />}

        {section === "privacy" && (
          <PrivacyCard status={telemetry} onChanged={setTelemetry} />
        )}

        {section === "about" && <AboutScreen />}
      </section>
      </div>
    </div>
  );
}

/** A speed control over preset steps — a raw bytes/sec number field is unusable for bandwidth. */
function SpeedSlider(props: {
  label: string;
  value: number;
  note?: ReactNode;
  onChange: (v: number) => void;
}) {
  // Nearest preset, not exact match. `indexOf` returned -1 for any value not in the table — a
  // hand-edited settings.json, or a value written by an older build with a different table — and
  // `Math.max(0, -1)` parked the slider on "unlimited" while the hint underneath printed the real
  // rate. The next nudge then silently discarded the user's number.
  const exact = SPEEDS.indexOf(props.value);
  const index =
    exact >= 0
      ? exact
      : SPEEDS.reduce(
          (best, speed, i) =>
            Math.abs(speed - props.value) < Math.abs(SPEEDS[best] - props.value) ? i : best,
          0,
        );
  return (
    <Slider
      label={props.label}
      value={index}
      min={0}
      max={SPEEDS.length - 1}
      note={props.note}
      hint={props.value === 0 ? "Unlimited" : `${formatBytes(props.value)}/s`}
      onChange={(i) => props.onChange(SPEEDS[i])}
    />
  );
}

/**
 * What the tracker stores contribute, beside the boxes the user types into.
 *
 * ## Why this card exists
 *
 * The two textareas above hold `network.default_trackers` and `network.rendezvous_endpoints` — what
 * *this install* typed. They were rendered as though they were the tracker list, and they are not:
 * everything handed to the worker is those plus whatever the stores add, which is what
 * `worker_env`, `WorkerConfig::of` and the synchronisation file have always built.
 *
 * On a fresh install the boxes are empty and the built-in store supplies both services, so this
 * screen showed two empty boxes and the settings row read "0 tracker(s)" — while Tracker stores,
 * one screen away, listed a tracker and a relay. Nothing was broken; the app simply never said what
 * it was actually going to dial. That reads exactly like a store being ignored.
 *
 * Read-only, because these are another publisher's addresses. Changing them means removing the
 * store or adding a different one, and an editable copy here would be a second place holding the
 * same list.
 */
function StoreContributions() {
  const [effective, setEffective] = useState<Record<
    "trackers" | "rendezvous",
    ResolvedEndpoint[]
  > | null>(null);

  useEffect(() => {
    resolvedServices().then(setEffective).catch(() => setEffective(null));
  }, []);

  if (!effective) return null;
  const groups = [
    { label: "Trackers", entries: effective.trackers.filter((e) => e.store) },
    { label: "Relays", entries: effective.rendezvous.filter((e) => e.store) },
  ].filter((group) => group.entries.length > 0);

  return (
    <Card
      title="From your tracker stores"
      hint="Addresses this node also uses, contributed by the lists you trust. The boxes above are your own; these are added to them, never in place of them."
    >
      {groups.length === 0 ? (
        <p className="py-1 text-xs text-faint">
          No store is contributing an address. Add one under Tracker stores and it appears here.
        </p>
      ) : (
        groups.map((group) => (
          <div key={group.label} className="py-1">
            <p className="text-[11px] tracking-wide text-faint uppercase">{group.label}</p>
            <ul className="mt-1 flex flex-col gap-1">
              {group.entries.map((entry) => (
                <li key={entry.endpoint} className="flex items-baseline justify-between gap-3">
                  <code className="font-mono text-[12px] break-all">{entry.endpoint}</code>
                  <span className="shrink-0 text-xs text-faint">from {entry.store}</span>
                </li>
              ))}
            </ul>
          </div>
        ))
      )}
    </Card>
  );
}
