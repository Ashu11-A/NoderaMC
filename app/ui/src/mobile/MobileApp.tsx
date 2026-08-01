// The phone layout: a Material 3 shell over the worker this app runs.
//
// # Scrolling
//
// The document does not scroll — `body { overflow: hidden }` is the desktop shell's rule and it
// applies here too. So the shell is a column of fixed height with exactly one scrolling child, and
// the bars are laid out in the flow rather than positioned over it. A page that "scrolls" by
// growing past a hidden overflow does not scroll at all, which is what the first version did.
//
// # What is on screen
//
// The same node the desktop app shows, because on Android the Java worker runs inside this process
// (see `android/kotlin/NoderaWorker.kt`). Everything here is read from the worker's own state over
// the control socket — there is no phone-specific data model and no second peer.
import { useEffect, useState } from "react";
import { FiActivity, FiGlobe, FiHome, FiSettings, FiWifiOff } from "react-icons/fi";
import {
  Button,
  Card,
  Chip,
  ListItem,
  Metric,
  NavigationBar,
  TopAppBar,
  cx,
} from "../m3/components";
import { applyDynamicColour, storeSource, storedSource } from "../m3/theme";
import { FadeThrough, Stagger, StaggerItem } from "../m3/motion";
import { BatteryModal, useBatteryPolicy } from "./Battery";
import { SettingsScreen, type Page as SettingsPage } from "./Settings";
import { useBackStack } from "./nav";
import { SetupFlow } from "./Setup";
import { fetchSetupState, fetchWorkerLogs, type Settings as SettingsDoc } from "../ipc";
import { formatBytes, heldBytes, isStale, linkFault, shortId, type Dashboard } from "../api";

type Tab = "node" | "worlds" | "activity" | "settings";

const TABS = [
  { id: "node", label: "Node", icon: <FiHome /> },
  { id: "worlds", label: "Worlds", icon: <FiGlobe /> },
  { id: "activity", label: "Activity", icon: <FiActivity /> },
  { id: "settings", label: "Settings", icon: <FiSettings /> },
];

export function MobileApp(props: {
  dashboard: Dashboard;
  scheme: "light" | "dark";
  /** Raised when a settings screen saves, so the shell's theme follows without a relaunch. */
  onSettings: (next: SettingsDoc) => void;
}) {
  const d = props.dashboard;
  const [tab, setTab] = useState<Tab>("node");
  const [source, setSource] = useState(storedSource);
  // One stack for the whole shell: settings sub-screens push onto the WebView's history, so the
  // system back gesture leaves them the same way the on-screen chevron does.
  const nav = useBackStack<SettingsPage>("root");
  const [setupDone, setSetupDone] = useState<boolean | null>(null);
  const battery = useBatteryPolicy();

  useEffect(() => {
    applyDynamicColour(source, props.scheme);
  }, [source, props.scheme]);

  useEffect(() => {
    fetchSetupState()
      .then((s) => setSetupDone(s.completed))
      // A device that cannot answer has not been set up; showing the flow is the safe direction,
      // since it can be completed again harmlessly and skipping it cannot be undone.
      .catch(() => setSetupDone(false));
  }, []);

  if (setupDone === null) return null;
  if (!setupDone) return <SetupFlow onDone={() => setSetupDone(true)} />;

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[var(--md-sys-color-surface)] text-[var(--md-sys-color-on-surface)]">
      {/* Raised once, after setup: a node whose OS may stop it is a node other people cannot rely
          on, and nothing else on screen would ever reveal that. */}
      <BatteryModal policy={battery} />
      {tab === "settings" ? (
        <SettingsScreen
          nav={nav}
          source={source}
          onSaved={props.onSettings}
          onSource={(hex) => {
            storeSource(hex);
            setSource(hex);
          }}
        />
      ) : (
        <>
          <TopAppBar
            title={tab === "node" ? "Nodera" : tab === "worlds" ? "Worlds" : "Activity"}
            trailing={<LinkChip d={d} />}
          />
          {/* Keyed by tab so the scroll offset does not travel between destinations. `scrollTop`
              belongs to this node, not to the tab rendered inside it; without the key, opening a
              short tab after scrolling a long one landed past the end of its content. */}
          <div key={tab} className="flex-1 overflow-y-auto overscroll-contain px-4 pb-6">
            <FadeThrough screenKey={tab}>
              {tab === "node" && (
                <NodeTab
                  d={d}
                  onAddTrackers={() => {
                    setTab("settings");
                    nav.push("network");
                  }}
                />
              )}
              {tab === "worlds" && <WorldsTab d={d} />}
              {tab === "activity" && <ActivityTab />}
            </FadeThrough>
          </div>
        </>
      )}
      {/* Hidden inside a sub-screen: a phone shows one destination at a time, and a navigation bar
          under a detail page invites the user to lose their place. */}
      {nav.depth === 0 && (
        <NavigationBar destinations={TABS} active={tab} onSelect={(id) => setTab(id as Tab)} />
      )}
    </div>
  );
}

/** Link state in one chip. `linkFault` returns null when there is nothing wrong worth saying. */
function LinkChip(props: { d: Dashboard }) {
  const fault = linkFault(props.d.link);
  return (
    <Chip tone={fault ? "bad" : "good"}>{fault ? "Offline" : "Online"}</Chip>
  );
}

/* ---------------------------------------------------------------------------------------- node */

function NodeTab(props: { d: Dashboard; onAddTrackers: () => void }) {
  const { d } = props;
  const known = d.link.has_data;
  const trackers = d.discovery.trackers;
  const reachable = trackers.filter((t) => t.reachable).length;

  // The takeover is only right when there is genuinely nothing else to show. A node with peers and
  // traffic but no reachable tracker is NOT idle — it simply cannot be found by anyone new — and
  // replacing its whole screen with an error hid a peer and 68 KB of live transfer during testing.
  // Configured-but-unreachable still counts as none: what matters is whether anything answered.
  if (known && reachable === 0 && d.counts.peers === 0 && d.worlds.length === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 px-6 py-16 text-center">
        <span className="text-[40px] text-[var(--md-sys-color-on-surface-variant)]">
          <FiWifiOff />
        </span>
        <h2 className="text-[22px] leading-7 text-[var(--md-sys-color-on-surface)]">
          {trackers.length === 0 ? "No trackers configured" : "No tracker is answering"}
        </h2>
        <p className="max-w-[300px] text-[14px] leading-5 text-[var(--md-sys-color-on-surface-variant)]">
          {trackers.length === 0
            ? "Your node cannot be found by other players until it announces to one."
            : `${trackers.length} configured, none reachable from this network.`}
        </p>
        <Button onClick={props.onAddTrackers}>
          {trackers.length === 0 ? "Add a tracker" : "Check trackers"}
        </Button>
      </div>
    );
  }

  return (
    <Stagger className="flex flex-col gap-3">
      {isStale(d.link) && <StaleNotice />}
      <StaggerItem className="grid grid-cols-2 gap-3">
        <Metric label="Peers" value={known ? String(d.counts.peers) : "—"} />
        <Metric
          label="Trackers"
          value={trackers.length === 0 ? "—" : `${reachable}/${trackers.length}`}
        />
        <Metric label="Worlds" value={known ? String(d.worlds.length) : "—"} />
        <Metric label="Shared" value={known ? formatBytes(d.traffic.total_sent_bytes) : "—"} />
      </StaggerItem>

      <StaggerItem>
        <Card>
          <ListItem headline={d.node.node_id ? shortId(d.node.node_id, 14, 6) : "—"} supporting="Node" />
          <ListItem headline={d.node.self_route || "—"} supporting="Address" />
          <ListItem headline={d.node.roles.join(", ") || "—"} supporting="Roles" />
        </Card>
      </StaggerItem>

      {/* Not a takeover: the node is working, and this is the one thing that is not.
          Gated on `known`, because "the worker has not told us anything yet" is not "there are no
          trackers". Without that gate this card appeared on every cold start and after every link
          drop, telling a user with a perfectly good store that nothing was configured — which is
          the one conclusion the screen must never invite them to draw. */}
      {known && reachable === 0 && (
        <StaggerItem>
          <Card variant="outlined">
            <ListItem
              leading={
                <span className="text-[var(--md-sys-color-error)]">
                  <FiWifiOff />
                </span>
              }
              headline={trackers.length === 0 ? "No trackers configured" : "No tracker is answering"}
              supporting="New players cannot find this node until one responds"
              trailing={
                <Button variant="text" onClick={props.onAddTrackers}>
                  Fix
                </Button>
              }
            />
          </Card>
        </StaggerItem>
      )}

      <StaggerItem>
      <Card>
        {!known ? (
          // Same rule as the card above: an app that has not yet heard from its worker knows
          // nothing about the tracker list, and saying "No trackers" is an assertion it cannot
          // make. It sent a user to add a tracker they already had.
          <ListItem headline="—" supporting="Waiting for the peer to report" />
        ) : trackers.length === 0 ? (
          <ListItem
            headline="No trackers"
            supporting="Add one in Settings › Network, or a list under Settings › Tracker stores"
          />
        ) : (
          trackers.map((t) => (
            <ListItem
              key={`${t.host}:${t.port}`}
              headline={`${t.host}:${t.port}`}
              supporting={t.reachable ? `${t.latency_ms ?? "—"} ms` : "unreachable"}
              trailing={<Chip tone={t.reachable ? "good" : "bad"}>{t.reachable ? "up" : "down"}</Chip>}
            />
          ))
        )}
      </Card>
      </StaggerItem>
    </Stagger>
  );
}

/* -------------------------------------------------------------------------------------- worlds */

/**
 * The same three groups the desktop Worlds screen uses, in the same order and for the same reason:
 * where you are, what you run, what you carry for other people.
 *
 * A phone shows less, so what it shows has to be the part that answers a question. Each row's
 * supporting line is this device's contribution — pieces held and the bytes they amount to — rather
 * than the world's size, which is the same on every peer and therefore tells the owner of this
 * phone nothing about their own node.
 */
function WorldsTab(props: { d: Dashboard }) {
  const worlds = props.d.worlds;
  const playing = worlds.filter((w) => w.connected);
  const mine = worlds.filter((w) => !w.connected && w.administered);
  const helping = worlds.filter((w) => !w.connected && !w.administered);

  if (worlds.length === 0) {
    return (
      <Card>
        <ListItem
          headline="No worlds yet"
          supporting="Worlds you play in, run, or help share appear here"
        />
      </Card>
    );
  }

  return (
    <Stagger className="flex flex-col gap-3">
      {isStale(props.d.link) && <StaleNotice />}
      <StaggerItem className="grid grid-cols-2 gap-3">
        <Metric label="Playing in" value={String(props.d.counts.connected_worlds)} />
        <Metric label="You serve" value={formatBytes(props.d.counts.shared_bytes)} />
      </StaggerItem>
      <WorldGroup title="You are playing in" worlds={playing} />
      <WorldGroup title="Worlds you run" worlds={mine} />
      <WorldGroup title="Worlds you help share" worlds={helping} />
    </Stagger>
  );
}

/** One labelled group, omitted entirely when empty — a phone has no room for three empty states. */
function WorldGroup(props: { title: string; worlds: Dashboard["worlds"] }) {
  if (props.worlds.length === 0) return null;
  return (
    <StaggerItem>
      <h2 className="px-1 pt-1 pb-2 text-[13px] font-medium text-[var(--md-sys-color-on-surface-variant)]">
        {props.title}
      </h2>
      <Card>
        {props.worlds.map((w) => (
          <ListItem
            key={w.world_id}
            headline={w.name || shortId(w.world_id, 10, 6)}
            supporting={
              w.piece_count === 0
                ? "no pieces here yet"
                : `${formatBytes(heldBytes(w))} held · ${w.pieces_held}/${w.piece_count} pieces`
            }
            trailing={
              <Chip tone={w.connected ? "good" : w.game_endpoint ? "good" : "neutral"}>
                {w.connected ? "here" : w.game_endpoint ? "live" : "idle"}
              </Chip>
            }
          />
        ))}
      </Card>
    </StaggerItem>
  );
}

/** "These figures are the last known picture, not the current one." Mirrors the desktop
 *  `StaleDataNotice`: a phone showing stale numbers without saying so reads as a live but idle node
 *  when the worker has actually stopped (A-UX-1). */
function StaleNotice() {
  return (
    <div className="rounded-[12px] bg-[var(--md-sys-color-error-container)] px-4 py-3 text-[13px] text-[var(--md-sys-color-on-error-container)]">
      Showing the last known picture — the worker link is offline.
    </div>
  );
}

/* ------------------------------------------------------------------------------------ activity */

function ActivityTab() {
  const [lines, setLines] = useState<string[]>([]);

  useEffect(() => {
    let alive = true;
    const pull = () =>
      fetchWorkerLogs()
        .then((l) => alive && setLines(l))
        .catch(() => {});
    pull();
    const timer = window.setInterval(pull, 2000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  const shown = lines.slice(-30);
  return (
    <Card>
      {shown.length === 0 ? (
        <ListItem headline="Nothing yet" supporting="The peer writes here as it works" />
      ) : (
        <pre
          className={cx(
            "overflow-x-auto font-mono text-[11px] leading-[1.6] whitespace-pre-wrap",
            "text-[var(--md-sys-color-on-surface-variant)]",
          )}
        >
          {shown.join("\n")}
        </pre>
      )}
    </Card>
  );
}
