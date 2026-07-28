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
  saveSettings,
  setTelemetryConsent,
  type NetworkPolicy,
  type NetworkState,
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
              supporting={summary(row.id, settings, storage)}
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
        {page === "network" && <NetworkPage settings={settings} onSaved={setSettings} />}
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
function summary(
  page: Page,
  settings: SettingsDoc | null,
  storage: StorageInfo | null,
): string | undefined {
  switch (page) {
    case "appearance":
      return settings ? THEME_LABEL[settings.appearance.theme] : "…";
    case "storage":
      return storage ? shortPath(storage.current) : "…";
    case "network":
      if (!settings) return "…";
      return `${settings.network.default_trackers.length} tracker(s) · ${
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
  onSaved: (next: SettingsDoc) => void;
}) {
  const { settings } = props;
  const [draft, setDraft] = useState<string | null>(null);
  // `null` = nothing to report. An empty string used to mean both "no result yet" and "cleared",
  // and the success text was never cleared at all — so "Saved" sat next to an edited, unsaved box.
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [connection, setConnection] = useState<NetworkState>(EMPTY_NETWORK_STATE);
  // Pushed by the backend on a transition, so this page learns a tracker came back without
  // polling for it. `null` means nobody has said yet — deliberately not rendered as "none".
  const discovery = useDiscovery();

  useEffect(() => {
    if (settings && draft === null) setDraft(settings.network.default_trackers.join("\n"));
  }, [settings, draft]);

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
              // Emptying the box makes this node undiscoverable. That is a legitimate thing to want
              // and a terrible thing to do by accident, so it is confirmed rather than refused.
              if (trackers.length === 0 && settings.network.default_trackers.length > 0) {
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
          status.supported
            ? status.consent === "granted"
              ? "On"
              : "Off"
            : "The peer has not reported yet"
        }
        trailing={
          <Switch
            checked={status.consent === "granted"}
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
