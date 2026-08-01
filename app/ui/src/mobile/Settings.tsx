// Settings as a list of destinations, one screen per subject.
//
// The previous version put four unrelated subjects behind tabs and explained each one in a
// paragraph. Tabs are for switching between views of the same thing; settings are a list of places
// you go. And the explanations belonged in documentation, not on a phone screen — a settings row
// needs a name and, at most, its current value.
import { useCallback, useEffect, useState } from "react";
import TrackerStoresScreen from "../TrackerStores";
import {
  FiBatteryCharging,
  FiChevronLeft,
  FiChevronRight,
  FiDroplet,
  FiHardDrive,
  FiInfo,
  FiShield,
  FiTag,
  FiWifi,
} from "react-icons/fi";
import { Button, Card, ListItem, Switch, TopAppBar, cx } from "../m3/components";
import { SOURCE_CHOICES } from "../m3/theme";
import { useDiscovery } from "../api";
import { AboutScreen } from "./About";
import { BatteryPage } from "./Battery";
import { StoragePicker } from "./Storage";
import {
  EMPTY_NETWORK_STATE,
  EMPTY_TELEMETRY,
  TRANSPORT_LABEL,
  fetchNetworkState,
  fetchSettings,
  fetchSettingsFault,
  fetchStorageInfo,
  fetchTelemetryStatus,
  resolvedServices,
  saveSettings,
  setTelemetryConsent,
  type NetworkPolicy,
  type NetworkState,
  type ResolvedEndpoint,
  type Settings as SettingsDoc,
  type StorageInfo,
  type TelemetryStatus,
  type Theme,
} from "../ipc";

export type Page =
  | "root"
  | "appearance"
  | "storage"
  | "network"
  | "stores"
  | "battery"
  | "privacy"
  | "about";

const ROWS: { id: Page; icon: JSX.Element; title: string }[] = [
  { id: "appearance", icon: <FiDroplet />, title: "Appearance" },
  { id: "storage", icon: <FiHardDrive />, title: "Storage" },
  { id: "network", icon: <FiWifi />, title: "Network" },
  { id: "stores", icon: <FiTag />, title: "Tracker stores" },
  { id: "battery", icon: <FiBatteryCharging />, title: "Battery" },
  { id: "privacy", icon: <FiShield />, title: "Privacy" },
  { id: "about", icon: <FiInfo />, title: "About" },
];

export function SettingsScreen(props: {
  source: string;
  onSource: (hex: string) => void;
  /** The sub-screen stack, owned by the shell so it can hide the navigation bar with it. */
  nav: { current: Page; push: (page: Page) => void; back: () => void };
  /**
   * Raised whenever a screen here persists a new document.
   *
   * The shell needs it for the theme: `appearance.theme` is rendered by the *shell*, so a change
   * made down here would otherwise not be visible until the app was relaunched.
   */
  onSaved?: (next: SettingsDoc) => void;
}) {
  const page = props.nav.current;
  const [settings, setSettingsState] = useState<SettingsDoc | null>(null);
  const [storage, setStorage] = useState<StorageInfo | null>(null);
  // What this node will actually dial: the boxes below PLUS whatever the stores contribute. The
  // root row used to count `network.default_trackers` and so read "0 tracker(s)" on an install
  // whose tracker came from the built-in store — while Tracker stores, one row down, listed it.
  const [effective, setEffective] = useState<ResolvedServices | null>(null);
  const [fault, setFault] = useState("");
  const onSaved = props.onSaved;
  const setSettings = useCallback(
    (next: SettingsDoc) => {
      setSettingsState(next);
      onSaved?.(next);
    },
    [onSaved],
  );

  useEffect(() => {
    // Only the pages that read them. Refetching the whole document on the way into About or
    // Battery was two IPC round trips for screens that use neither.
    if (page === "root" || page === "appearance" || page === "network") {
      fetchSettings().then(setSettings).catch(() => {});
    }
    // Re-read on the way into either screen that reports a count, and on the way back out of the
    // store screen — adding or removing a store changes this without touching the settings document.
    if (page === "root" || page === "network") {
      resolvedServices().then(setEffective).catch(() => {});
    }
    if (page === "root" || page === "storage") {
      fetchStorageInfo().then(setStorage).catch(() => {});
    }
  }, [page]);

  useEffect(() => {
    fetchSettingsFault().then(setFault).catch(() => {});
  }, []);

  if (page === "root") {
    return (
      <>
        <TopAppBar title="Settings" />
        <div className="flex-1 overflow-y-auto px-2 pb-6">
          {fault && (
            <div className="mx-2 mb-2 rounded-[12px] bg-[var(--md-sys-color-error-container)] p-4 text-[13px] text-[var(--md-sys-color-on-error-container)]">
              Your saved settings could not be read, so these are defaults. The file has been left
              alone. {fault}
            </div>
          )}
          {ROWS.map((row) => (
            <ListItem
              key={row.id}
              leading={row.icon}
              headline={row.title}
              supporting={summary(row.id, settings, storage, effective)}
              trailing={<FiChevronRight className="text-[var(--md-sys-color-on-surface-variant)]" />}
              onClick={() => props.nav.push(row.id)}
            />
          ))}
        </div>
      </>
    );
  }

  const title = ROWS.find((r) => r.id === page)?.title ?? "Settings";
  return (
    <>
      <TopAppBar
        title={title}
        leading={
          <button
            onClick={props.nav.back}
            aria-label="Back"
            className="grid h-10 w-10 place-items-center rounded-full text-[20px]"
          >
            <FiChevronLeft />
          </button>
        }
      />
      {/* Keyed by page: each sub-screen starts at its own top, not at the offset the previous one
          was left on. */}
      <div key={page} className="flex-1 overflow-y-auto px-4 pb-6">
        {page === "appearance" && (
          <Appearance
            source={props.source}
            onSource={props.onSource}
            settings={settings}
            onSaved={setSettings}
          />
        )}
        {page === "storage" && <StoragePicker info={storage} onChanged={setStorage} />}
        {page === "network" && (
          <NetworkPage settings={settings} effective={effective} onSaved={setSettings} />
        )}
        {page === "stores" && <TrackerStoresScreen shell="mobile" />}
        {page === "battery" && <BatteryPage />}
        {page === "privacy" && <PrivacyPage />}
        {page === "about" && <AboutScreen />}
      </div>
    </>
  );
}

const THEME_LABEL: Record<Theme, string> = {
  system: "System theme",
  light: "Light theme",
  dark: "Dark theme",
};

const POLICY_LABEL: Record<NetworkPolicy, string> = {
  any: "any connection",
  unmetered_only: "unmetered only",
  wifi_only: "Wi-Fi only",
};

/**
 * The one line under each row: its current value, not a description of what it is.
 *
 * `undefined` rather than `""` for a row with nothing to say — `ListItem` renders the supporting
 * slot whenever it is not `undefined`, so an empty string produced a blank line that left the About
 * row visibly taller than its neighbours.
 */
/** The effective endpoint lists, keyed the way `resolved_services` returns them. */
type ResolvedServices = Record<"trackers" | "rendezvous", ResolvedEndpoint[]>;

function summary(
  page: Page,
  settings: SettingsDoc | null,
  storage: StorageInfo | null,
  effective: ResolvedServices | null,
): string | undefined {
  switch (page) {
    case "appearance":
      return settings ? THEME_LABEL[settings.appearance.theme] : "…";
    case "storage":
      return storage ? shortPath(storage.current) : "…";
    case "network":
      if (!settings) return "…";
      // The EFFECTIVE count, not `network.default_trackers.length`. Those differ exactly when a
      // store is contributing, which is the normal case on a fresh install — and reporting the raw
      // setting there said "0 tracker(s)" about a node with a tracker.
      if (!effective) return "…";
      return `${effective.trackers.length} tracker(s) · ${
        POLICY_LABEL[settings.network.transfer_network]
      }`;
    case "battery":
      return "Background restrictions";
    case "privacy":
      return "Telemetry and identity";
    default:
      return undefined;
  }
}

function shortPath(path: string): string {
  const parts = path.split("/").filter(Boolean);
  return parts.length <= 2 ? path : `…/${parts.slice(-2).join("/")}`;
}

/* ---------------------------------------------------------------------------------- appearance */

const THEMES: { id: Theme; label: string }[] = [
  { id: "system", label: "System" },
  { id: "light", label: "Light" },
  { id: "dark", label: "Dark" },
];

/**
 * Theme and source colour.
 *
 * The theme control is new here. `appearance.theme` has always existed, has always been applied,
 * and has always been settable — from the *desktop* screen. On a phone the value was whatever the
 * settings file happened to hold, with no way to change it from the device it renders on.
 */
function Appearance(props: {
  source: string;
  onSource: (hex: string) => void;
  settings: SettingsDoc | null;
  onSaved: (next: SettingsDoc) => void;
}) {
  const { settings } = props;
  const [error, setError] = useState("");

  const choose = (theme: Theme) => {
    if (!settings) return;
    const next: SettingsDoc = {
      ...settings,
      appearance: { ...settings.appearance, theme },
    };
    // Applied optimistically: the shell re-reads the document on every settings save, and waiting
    // for the round trip would make a theme tap feel broken on a slow device.
    props.onSaved(next);
    setError("");
    saveSettings(next).catch((e: unknown) => setError(String(e)));
  };

  return (
    <>
      <Card>
        <p className="pb-3 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">Theme</p>
        <div className="flex gap-2">
          {THEMES.map((choice) => {
            const selected = settings?.appearance.theme === choice.id;
            return (
              <button
                key={choice.id}
                type="button"
                disabled={!settings}
                onClick={() => choose(choice.id)}
                aria-pressed={selected}
                className={cx(
                  "flex-1 rounded-full px-4 py-2.5 text-[14px] transition-transform active:scale-95",
                  selected
                    ? "bg-[var(--md-sys-color-secondary-container)] text-[var(--md-sys-color-on-secondary-container)]"
                    : "border border-[var(--md-sys-color-outline-variant)] text-[var(--md-sys-color-on-surface-variant)]",
                )}
              >
                {choice.label}
              </button>
            );
          })}
        </div>
        {error && (
          <p className="pt-3 text-[13px] text-[var(--md-sys-color-error)]">{error}</p>
        )}
      </Card>
      <div className="h-3" />
      <Card>
        <p className="pb-3 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">
          Source colour
        </p>
        <div className="flex flex-wrap gap-3">
          {SOURCE_CHOICES.map((choice) => (
            <button
              key={choice.hex}
              type="button"
              onClick={() => props.onSource(choice.hex)}
              aria-label={choice.name}
              className={cx(
                "h-12 w-12 rounded-full border-2 transition-transform active:scale-95",
                props.source.toLowerCase() === choice.hex.toLowerCase()
                  ? "border-[var(--md-sys-color-on-surface)]"
                  : "border-transparent",
              )}
              style={{ backgroundColor: choice.hex }}
            />
          ))}
        </div>
      </Card>
    </>
  );
}

/* ------------------------------------------------------------------------------------- network */

const POLICIES: { id: NetworkPolicy; label: string; hint: string }[] = [
  { id: "any", label: "Any connection", hint: "Seeds on mobile data too." },
  {
    id: "unmetered_only",
    label: "Unmetered only",
    hint: "Follows what your phone marks as metered, so an unlimited SIM counts.",
  },
  { id: "wifi_only", label: "Wi-Fi only", hint: "Never uses mobile data." },
];

function NetworkPage(props: {
  settings: SettingsDoc | null;
  /** The effective lists, so the box below is not mistaken for the whole of what this node dials. */
  effective: ResolvedServices | null;
  onSaved: (next: SettingsDoc) => void;
}) {
  const { settings, effective } = props;
  const [draft, setDraft] = useState<string | null>(null);
  // `null` = nothing to report. An empty string used to mean both "no result yet" and "cleared",
  // and the success text was never cleared at all — so "Saved" sat next to an edited, unsaved box.
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [connection, setConnection] = useState<NetworkState>(EMPTY_NETWORK_STATE);
  const [portStart, setPortStart] = useState("");
  const [portEnd, setPortEnd] = useState("");
  const [portResult, setPortResult] = useState<{ ok: boolean; text: string } | null>(null);
  // Pushed by the backend on a transition, so this page learns a tracker came back without
  // polling for it. `null` means nobody has said yet — deliberately not rendered as "none".
  const discovery = useDiscovery();

  useEffect(() => {
    if (settings && draft === null) setDraft(settings.network.default_trackers.join("\n"));
  }, [settings, draft]);

  useEffect(() => {
    if (!settings) return;
    setPortStart(String(settings.network.port_range_start));
    setPortEnd(String(settings.network.port_range_end));
  }, [settings?.network.port_range_start, settings?.network.port_range_end]);

  useEffect(() => {
    let alive = true;
    const read = () => {
      fetchNetworkState()
        .then((state) => alive && setConnection(state))
        .catch(() => {});
    };
    read();
    const timer = window.setInterval(read, 5000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  if (!settings)
    return <p className="p-2 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">…</p>;

  const save = (next: SettingsDoc, message: string) => {
    saveSettings(next)
      .then(() => {
        props.onSaved(next);
        setResult({ ok: true, text: message });
      })
      .catch((e: unknown) => setResult({ ok: false, text: String(e) }));
  };

  const savePorts = (next: SettingsDoc, message: string) => {
    saveSettings(next)
      .then(() => {
        props.onSaved(next);
        setPortResult({ ok: true, text: message });
      })
      .catch((e: unknown) => setPortResult({ ok: false, text: String(e) }));
  };

  const trackers = (draft ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  return (
    <>
      {connection.supported && (
        <>
          <Card>
            <p className="pb-1 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">
              This connection
            </p>
            <p className="text-[16px] text-[var(--md-sys-color-on-surface)]">
              {TRANSPORT_LABEL[connection.transport]}
              {connection.metered && " · metered"}
              {connection.vpn && " · VPN"}
            </p>
            {connection.error && (
              <p className="pt-2 text-[13px] text-[var(--md-sys-color-error)]">
                {connection.error}
              </p>
            )}
          </Card>
          <div className="h-3" />
        </>
      )}

      <Card>
        <p className="pb-3 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">
          Move data over
        </p>
        {POLICIES.map((policy) => (
          <ListItem
            key={policy.id}
            headline={policy.label}
            supporting={policy.hint}
            trailing={
              settings.network.transfer_network === policy.id ? (
                <span className="text-[var(--md-sys-color-primary)]">●</span>
              ) : undefined
            }
            onClick={() =>
              save(
                {
                  ...settings,
                  network: { ...settings.network, transfer_network: policy.id },
                },
                "Saved",
              )
            }
          />
        ))}
      </Card>

      <div className="h-3" />

      <Card>
        <ListItem
          headline="Use random port"
          supporting="Let Android choose the P2P listening port. Turn off to use a router-forwarded range after fully relaunching Nodera."
          trailing={
            <Switch
              checked={settings.network.use_random_port}
              label="Use random P2P port"
              onChange={(useRandom) =>
                savePorts(
                  {
                    ...settings,
                    network: { ...settings.network, use_random_port: useRandom },
                  },
                  "Saved · fully relaunch Nodera to apply",
                )
              }
            />
          }
        />
        {!settings.network.use_random_port && (
          <div className="border-t border-[var(--md-sys-color-outline-variant)] px-4 pt-4 pb-2">
            <div className="grid grid-cols-2 gap-3">
              <label className="text-[13px] text-[var(--md-sys-color-on-surface-variant)]">
                Range start
                <input
                  type="number"
                  min={1}
                  max={65535}
                  value={portStart}
                  onChange={(event) => {
                    setPortStart(event.currentTarget.value);
                    setPortResult(null);
                  }}
                  className="mt-1 w-full rounded-[8px] border border-[var(--md-sys-color-outline-variant)] bg-[var(--md-sys-color-surface-container-highest)] px-3 py-2 text-[var(--md-sys-color-on-surface)]"
                />
              </label>
              <label className="text-[13px] text-[var(--md-sys-color-on-surface-variant)]">
                Range end
                <input
                  type="number"
                  min={1}
                  max={65535}
                  value={portEnd}
                  onChange={(event) => {
                    setPortEnd(event.currentTarget.value);
                    setPortResult(null);
                  }}
                  className="mt-1 w-full rounded-[8px] border border-[var(--md-sys-color-outline-variant)] bg-[var(--md-sys-color-surface-container-highest)] px-3 py-2 text-[var(--md-sys-color-on-surface)]"
                />
              </label>
            </div>
            <div className="mt-3 flex items-center gap-3">
              <Button
                onClick={() => {
                  const start = Number(portStart);
                  const end = Number(portEnd);
                  if (
                    !Number.isInteger(start) ||
                    !Number.isInteger(end) ||
                    start < 1 ||
                    end > 65535 ||
                    start > end
                  ) {
                    setPortResult({
                      ok: false,
                      text: "Use ports 1–65535, with start no higher than end.",
                    });
                    return;
                  }
                  savePorts(
                    {
                      ...settings,
                      network: {
                        ...settings.network,
                        port_range_start: start,
                        port_range_end: end,
                      },
                    },
                    "Saved · fully relaunch Nodera to apply",
                  );
                }}
              >
                Save range
              </Button>
              {portResult && (
                <span
                  className={cx(
                    "text-[13px]",
                    portResult.ok
                      ? "text-[var(--md-sys-color-on-surface-variant)]"
                      : "text-[var(--md-sys-color-error)]",
                  )}
                >
                  {portResult.text}
                </span>
              )}
            </div>
          </div>
        )}
        {settings.network.use_random_port && portResult && (
          <p
            className={cx(
              "px-4 pb-3 text-[13px]",
              portResult.ok
                ? "text-[var(--md-sys-color-on-surface-variant)]"
                : "text-[var(--md-sys-color-error)]",
            )}
          >
            {portResult.text}
          </p>
        )}
      </Card>

      <div className="h-3" />

      <Card>
        <div className="flex items-baseline justify-between pb-2">
          <p className="text-[14px] text-[var(--md-sys-color-on-surface-variant)]">
            Trackers, one per line
          </p>
          {discovery && (
            <span
              className={cx(
                "text-[13px]",
                discovery.connected
                  ? "text-[var(--md-sys-color-primary)]"
                  : "text-[var(--md-sys-color-error)]",
              )}
            >
              {discovery.reachable}/{discovery.total} answering
            </span>
          )}
        </div>
        <textarea
          className={cx(
            "min-h-[120px] w-full rounded-[8px] p-3 font-mono text-[13px]",
            "bg-[var(--md-sys-color-surface-container-highest)] text-[var(--md-sys-color-on-surface)]",
            "border border-[var(--md-sys-color-outline-variant)] focus:border-[var(--md-sys-color-primary)] focus:outline-none",
          )}
          value={draft ?? ""}
          onChange={(e) => {
            setDraft(e.currentTarget.value);
            // A stale "Saved" beside an edited box is a lie about what is stored.
            setResult(null);
          }}
          spellCheck={false}
          autoCapitalize="none"
          autoCorrect="off"
        />
        <div className="mt-3 flex items-center gap-3">
          <Button
            onClick={() => {
              // Emptying the box is only undiscoverable when nothing else is contributing. It used
              // to warn unconditionally, which was wrong on the common install: with a store
              // supplying a tracker, clearing this box changes nothing about being findable, and a
              // warning that is false is a warning people learn to click through.
              const fromStores = effective?.trackers.filter((e) => e.store).length ?? 0;
              if (
                trackers.length === 0
                && settings.network.default_trackers.length > 0
                && fromStores === 0
              ) {
                setResult({
                  ok: false,
                  text: "Removing every tracker means nobody can find this node. Press Save again to confirm.",
                });
                if (result?.ok !== false) return;
              }
              save(
                {
                  ...settings,
                  network: { ...settings.network, default_trackers: trackers },
                },
                trackers.length === 0 ? "Saved — no trackers" : `Saved ${trackers.length}`,
              );
            }}
          >
            Save
          </Button>
          {result && (
            <span
              className={cx(
                "text-[13px]",
                result.ok
                  ? "text-[var(--md-sys-color-on-surface-variant)]"
                  : "text-[var(--md-sys-color-error)]",
              )}
            >
              {result.text}
            </span>
          )}
        </div>
      </Card>

      {/* The box above is what the user typed. This is everything else the node will dial, and it
          exists because its absence read as "adding a store does nothing": a fresh install has an
          empty box, one store, and — until this card — a screen that said "0 tracker(s)". */}
      <FromStores effective={effective} />
    </>
  );
}

/**
 * The endpoints the stores contribute, shown where a user goes looking for their trackers.
 *
 * Read-only on purpose. These are not this install's settings — they are another publisher's list,
 * and the way to change them is to remove the store or add a different one, which is one row up
 * under **Tracker stores**. An editable copy here would be a second place to keep the same list.
 */
function FromStores(props: { effective: ResolvedServices | null }) {
  const { effective } = props;
  if (!effective) return null;
  const rows = [
    { label: "Trackers", entries: effective.trackers.filter((e) => e.store) },
    { label: "Relays", entries: effective.rendezvous.filter((e) => e.store) },
  ].filter((group) => group.entries.length > 0);

  return (
    <>
      <div className="h-3" />
      <Card>
        <p className="pb-1 text-[14px] text-[var(--md-sys-color-on-surface-variant)]">
          From your stores
        </p>
        {rows.length === 0 ? (
          <ListItem
            headline="Nothing yet"
            supporting="Add a store under Settings › Tracker stores and its addresses appear here."
          />
        ) : (
          rows.map((group) => (
            <div key={group.label}>
              <p className="pt-2 text-[12px] tracking-wide text-[var(--md-sys-color-on-surface-variant)] uppercase">
                {group.label}
              </p>
              {group.entries.map((entry) => (
                <ListItem
                  key={entry.endpoint}
                  headline={entry.endpoint}
                  supporting={`from ${entry.store}`}
                />
              ))}
            </div>
          ))
        )}
      </Card>
    </>
  );
}

/* ------------------------------------------------------------------------------------- privacy */

function PrivacyPage() {
  const [status, setStatus] = useState<TelemetryStatus>(EMPTY_TELEMETRY);

  useEffect(() => {
    fetchTelemetryStatus().then(setStatus).catch(() => {});
  }, []);

  return (
    <Card>
      <ListItem
        headline="Anonymous diagnostics"
        supporting={
          // An answer that has not reached the node yet is its own state, and saying "On" for it
          // would claim a collection setting nothing is applying. The switch follows what the
          // person chose, the text says whether the node has it.
          status.pending
            ? `${status.answer ? "On" : "Off"} — saved on this device, waiting for your node`
            : status.supported
              ? status.consent === "granted"
                ? "On"
                : "Off"
              : "The peer has not reported yet"
        }
        trailing={
          <Switch
            checked={status.pending ? status.answer === true : status.consent === "granted"}
            label="Anonymous diagnostics"
            onChange={(next) => {
              setTelemetryConsent(next).then(setStatus).catch(() => {});
            }}
          />
        }
      />
    </Card>
  );
}
