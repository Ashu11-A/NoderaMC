// Tracker stores — the lists of trackers and relays this install trusts.
//
// The shape is Mihon's extension-store screen, because it is the shape users of this kind of thing
// already know: a list, a per-row action set, an Add button, and a confirmation dialog that shows
// the URL before anything is fetched.
//
// One screen serves both layouts. The desktop rail and the mobile settings page render the same
// component: the content is a list of rows with the same four actions in both places, and two
// implementations of it would be two places to fix a bug in what a store is allowed to do.
import { useCallback, useEffect, useState } from "react";
import {
  FiAlertTriangle,
  FiCopy,
  FiExternalLink,
  FiPlus,
  FiRefreshCw,
  FiTag,
  FiTrash2,
} from "react-icons/fi";
import {
  addTrackerStore,
  pendingTrackerStore,
  refreshTrackerStores,
  removeTrackerStore,
  trackerStores,
  type TrackerStore,
} from "./ipc";

/** How often to look for a store offered by a deep link while this screen is open. */
const PENDING_POLL_MS = 1000;

function counts(store: TrackerStore): string {
  const trackers = store.services.filter((s) => s.kind === "tracker").length;
  const relays = store.services.filter((s) => s.kind === "rendezvous").length;
  const parts: string[] = [];
  if (trackers) parts.push(`${trackers} tracker${trackers === 1 ? "" : "s"}`);
  if (relays) parts.push(`${relays} relay${relays === 1 ? "" : "s"}`);
  return parts.length ? parts.join(" · ") : "no services";
}

function refreshedAt(store: TrackerStore): string {
  if (!store.last_refreshed_epoch_millis) return "never refreshed";
  return `refreshed ${new Date(store.last_refreshed_epoch_millis).toLocaleString()}`;
}

export default function TrackerStores() {
  const [stores, setStores] = useState<TrackerStore[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  // `typed` is the Add dialog; `offered` is a URL that arrived by link and needs confirming. They
  // are separate states on purpose — the confirmation wording differs, and a link must never
  // prefill a box the user could mistake for something they typed.
  const [typed, setTyped] = useState<string | null>(null);
  const [offered, setOffered] = useState<string | null>(null);

  const reload = useCallback(() => {
    trackerStores().then(setStores).catch((e) => setError(String(e)));
  }, []);

  useEffect(reload, [reload]);

  // A link can arrive while this screen is open, or can have launched the app. Polling rather than
  // subscribing keeps this working on a cold start, where the link is already waiting before any
  // listener could have been registered.
  useEffect(() => {
    let live = true;
    const check = () => {
      pendingTrackerStore()
        .then((url) => {
          if (live && url) setOffered(url);
        })
        .catch(() => undefined);
    };
    check();
    const timer = setInterval(check, PENDING_POLL_MS);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, []);

  const add = async (url: string) => {
    setBusy(true);
    setError("");
    try {
      await addTrackerStore(url);
      reload();
      setTyped(null);
      setOffered(null);
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (url: string) => {
    setError("");
    try {
      await removeTrackerStore(url);
      reload();
    } catch (e) {
      setError(String(e));
    }
  };

  const refresh = async () => {
    setBusy(true);
    setError("");
    try {
      setStores(await refreshTrackerStores());
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-fg">Tracker stores</h2>
          <p className="mt-1 max-w-2xl text-sm text-fg-muted">
            Lists of trackers and relays you choose to trust. A store only says{" "}
            <em>where to look</em> — every service found through one still proves its own identity
            before this node will use it, and none of them can affect a world.
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={refresh}
            disabled={busy || stores.length === 0}
            className="flex items-center gap-2 rounded-lg border border-line px-3 py-2 text-sm text-fg hover:bg-surface-2 disabled:opacity-50"
          >
            <FiRefreshCw className={busy ? "animate-spin" : ""} /> Refresh
          </button>
          <button
            type="button"
            onClick={() => setTyped("")}
            className="flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-sm font-medium text-accent-fg hover:opacity-90"
          >
            <FiPlus /> Add
          </button>
        </div>
      </header>

      {error && (
        <div className="flex items-start gap-2 rounded-lg border border-danger/40 bg-danger/10 p-3 text-sm text-danger">
          <FiAlertTriangle className="mt-0.5 shrink-0" />
          <span className="break-words">{error}</span>
        </div>
      )}

      {stores.length === 0 ? (
        <div className="rounded-xl border border-dashed border-line p-10 text-center">
          <p className="text-sm text-fg-muted">You have not added a tracker store yet.</p>
          <p className="mt-1 text-xs text-fg-subtle">
            Without one, this node only uses the trackers and relays you have typed in yourself.
          </p>
        </div>
      ) : (
        <ul className="flex flex-col gap-3">
          {stores.map((store) => (
            <li key={store.url} className="rounded-xl border border-line bg-surface p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <FiTag className="shrink-0 text-fg-muted" />
                    <span className="truncate font-medium text-fg">{store.name}</span>
                    {store.built_in && (
                      <span className="rounded border border-line px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-fg-subtle">
                        built in
                      </span>
                    )}
                  </div>
                  {store.description && (
                    <p className="mt-1 text-sm text-fg-muted">{store.description}</p>
                  )}
                  <p className="mt-1 break-all text-xs text-fg-subtle">{store.url}</p>
                  <p className="mt-1 text-xs text-fg-subtle">
                    {counts(store)} · {refreshedAt(store)}
                  </p>
                  {store.last_error && (
                    // Beside the services, not instead of them: the list above is the last one
                    // that worked, and it is still in use.
                    <p className="mt-1 text-xs text-warning">
                      last refresh failed ({store.last_error}) — still using what it said before
                    </p>
                  )}
                </div>
                <div className="flex shrink-0 gap-1">
                  {store.homepage && (
                    <a
                      href={store.homepage}
                      target="_blank"
                      rel="noreferrer"
                      title="Open the store's homepage"
                      className="rounded p-2 text-fg-muted hover:bg-surface-2 hover:text-fg"
                    >
                      <FiExternalLink />
                    </a>
                  )}
                  <button
                    type="button"
                    title="Copy the store URL"
                    onClick={() => navigator.clipboard?.writeText(store.url)}
                    className="rounded p-2 text-fg-muted hover:bg-surface-2 hover:text-fg"
                  >
                    <FiCopy />
                  </button>
                  <button
                    type="button"
                    title="Remove this store"
                    onClick={() => remove(store.url)}
                    className="rounded p-2 text-fg-muted hover:bg-surface-2 hover:text-danger"
                  >
                    <FiTrash2 />
                  </button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {typed !== null && (
        <Dialog
          title="Add tracker store"
          confirmLabel="Add"
          busy={busy}
          onCancel={() => setTyped(null)}
          onConfirm={() => add(typed)}
          confirmDisabled={typed.trim().length === 0}
        >
          <label className="block text-sm text-fg-muted" htmlFor="store-url">
            Store index URL
          </label>
          <input
            id="store-url"
            autoFocus
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            placeholder="https://example.org/index.json"
            className="mt-2 w-full rounded-lg border border-line bg-surface-2 px-3 py-2 text-sm text-fg outline-none focus:border-accent"
          />
          <p className="mt-2 text-xs text-fg-subtle">Must be https.</p>
        </Dialog>
      )}

      {offered !== null && (
        // The link did not add anything. This is the whole point of the deep-link design: a page on
        // any website can send someone here, so the decision is taken by the person, with the URL
        // they are about to trust in front of them.
        <Dialog
          title="Add tracker store"
          confirmLabel="Add"
          busy={busy}
          onCancel={() => setOffered(null)}
          onConfirm={() => add(offered)}
        >
          <p className="text-sm text-fg">Do you wish to add the tracker store below?</p>
          <p className="mt-3 break-all rounded-lg border border-line bg-surface-2 p-3 text-sm text-fg">
            {offered}
          </p>
        </Dialog>
      )}
    </div>
  );
}

function Dialog(props: {
  title: string;
  confirmLabel: string;
  busy: boolean;
  confirmDisabled?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-md rounded-2xl border border-line bg-surface p-5 shadow-xl">
        <h3 className="text-base font-semibold text-fg">{props.title}</h3>
        <div className="mt-4">{props.children}</div>
        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={props.onCancel}
            className="rounded-lg px-4 py-2 text-sm text-accent hover:bg-surface-2"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={props.onConfirm}
            disabled={props.busy || props.confirmDisabled}
            className="rounded-lg px-4 py-2 text-sm font-medium text-accent hover:bg-surface-2 disabled:opacity-40"
          >
            {props.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
