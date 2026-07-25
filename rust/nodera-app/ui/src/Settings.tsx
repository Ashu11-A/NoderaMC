// The settings screen: Appearance · Behavior · Network · Storage.
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
} from "react-icons/fi";
import {
  Card,
  Toggle,
  Segmented,
  NumberField,
  TextField,
  Slider,
  Disclosure,
  StatusBadge,
  cx,
} from "./components";
import {
  saveSettings,
  fetchSettingStatus,
  fetchConfigStatus,
  fetchWorkerOwnership,
  restartWorker,
  formatBytes,
  EMPTY_CONFIG_STATUS,
  type Settings as SettingsDoc,
  type SettingStatus,
  type SettingState,
  type ConfigStatus,
  type WorkerOwnership,
  type Theme,
} from "./ipc";

type Section = "appearance" | "behavior" | "network" | "storage";

const SECTIONS: { id: Section; label: string; icon: JSX.Element }[] = [
  { id: "appearance", label: "Appearance", icon: <FiEye /> },
  { id: "behavior", label: "Behavior", icon: <FiPower /> },
  { id: "network", label: "Network", icon: <FiWifi /> },
  { id: "storage", label: "Storage", icon: <FiHardDrive /> },
];

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
  // A non-empty reason on `unenforced` means the limitation is structural and permanent — the one
  // case that must not look like a pending feature.
  const permanent = status.state === "unenforced" && status.reason.length > 0;
  return (
    <StatusBadge
      tone={permanent ? "muted" : spec.tone}
      label={permanent ? "not supported" : spec.label}
      title={status.reason || "Saved to your settings file, but nothing applies it yet."}
    />
  );
}

const NAV_BTN = "flex items-center gap-2.5 rounded-sm px-[11px] py-2 text-left text-sm";
const NESTED = "my-0.5 ml-3.5 border-l-2 border-line pl-3.5";

export function SettingsScreen(props: {
  settings: SettingsDoc | null;
  onChange: (next: SettingsDoc) => void;
}) {
  const [section, setSection] = useState<Section>("appearance");
  const [statuses, setStatuses] = useState<SettingStatus[]>([]);
  const [config, setConfig] = useState<ConfigStatus>(EMPTY_CONFIG_STATUS);
  const [ownership, setOwnership] = useState<WorkerOwnership | null>(null);
  const [error, setError] = useState<string>("");
  const [restarting, setRestarting] = useState(false);

  // Statuses are re-read after every save, because a push can change them: a key moves to `live`
  // only once the worker has confirmed it, so the badges settle a beat after the toggle does.
  const refreshStatus = useCallback(() => {
    fetchSettingStatus().then(setStatuses).catch(() => {});
    fetchConfigStatus().then(setConfig).catch(() => {});
  }, []);

  useEffect(() => {
    refreshStatus();
    fetchWorkerOwnership().then(setOwnership).catch(() => {});
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
    <div className="grid min-h-full grid-cols-[190px_1fr] max-narrow:grid-cols-1">
      <nav className="flex flex-col gap-0.5 border-r border-line px-3 py-5 max-narrow:flex-row max-narrow:overflow-x-auto max-narrow:border-r-0 max-narrow:border-b max-narrow:px-3 max-narrow:py-2.5">
        {SECTIONS.map((sec) => (
          <button
            key={sec.id}
            className={cx(
              NAV_BTN,
              section === sec.id
                ? "bg-surface-hover font-semibold text-text"
                : "text-dim hover:bg-surface-hover hover:text-text",
            )}
            onClick={() => setSection(sec.id)}
          >
            {sec.icon}
            {sec.label}
          </button>
        ))}
      </nav>

      <div className="flex max-w-[860px] flex-col gap-3.5 px-[26px] pt-5 pb-10">
        {error && (
          <div className="flex items-center gap-2 rounded-sm border border-danger/45 bg-danger/12 px-3.5 py-2.5 text-sm text-danger">
            <FiAlertCircle aria-hidden /> {error}
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
              // Attach mode: the worker belongs to whoever started it (scripts/dev.sh, an
              // operator). Offering a button that kills someone else's process would be wrong.
              <span className="text-xs opacity-80">Restart it where you started it.</span>
            )}
          </div>
        )}

        {section === "appearance" && (
          <Card title="Appearance">
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
              hint="Node events: a peer joins, a world is recovered, the worker stops answering."
              checked={s.appearance.notifications}
              onChange={(v) => update((d) => (d.appearance.notifications = v))}
            />
          </Card>
        )}

        {section === "behavior" && (
          <>
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
          </>
        )}

        {section === "network" && (
          <>
            <Card
              title="Default trackers"
              hint="Discovery services this node announces its worlds to and queries for peers. One per line — tcp://host:port or udp://host:port; a bare host:port means TCP."
            >
              <textarea
                className="w-full resize-y rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] font-mono text-[12px] leading-relaxed focus:border-brand-2 focus:outline-none"
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
          </>
        )}

        {section === "storage" && (
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
            <Disclosure title="What gets stored here?">
              <p>
                Content-addressed pieces of world archives, each verified against its manifest hash
                before it is written. Nothing here is trusted on read: a piece that no longer
                matches its hash is discarded and re-fetched.
              </p>
            </Disclosure>
          </Card>
        )}
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
  const index = Math.max(0, SPEEDS.indexOf(props.value));
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
