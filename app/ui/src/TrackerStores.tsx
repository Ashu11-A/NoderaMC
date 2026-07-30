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

type Shell = "desktop" | "mobile";

/**
 * Shared semantic roles, resolved by the shell rather than by this screen.
 *
 * Every child, including the fixed dialog, inherits these custom properties. Changing the mobile
 * Material You source colour therefore repaints this whole screen without duplicating its markup.
 */
const SHELL_COLOURS: Record<Shell, string> = {
  desktop: [
    "[--tracker-store-on-surface:var(--text)]",
    "[--tracker-store-on-surface-variant:var(--text-dim)]",
    "[--tracker-store-muted:var(--text-faint)]",
    "[--tracker-store-surface:var(--surface)]",
    "[--tracker-store-surface-container:var(--surface-2)]",
    "[--tracker-store-outline:var(--line)]",
    "[--tracker-store-primary:var(--brand-2)]",
    "[--tracker-store-primary-fill:var(--brand-gradient)]",
    "[--tracker-store-on-primary:var(--color-white)]",
    "[--tracker-store-error:var(--danger)]",
    "[--tracker-store-error-container:var(--surface-2)]",
    "[--tracker-store-on-error-container:var(--danger)]",
    "[--tracker-store-warning:var(--warn)]",
    "[--tracker-store-scrim:var(--color-black)]",
  ].join(" "),
  mobile: [
    "[--tracker-store-on-surface:var(--md-sys-color-on-surface)]",
    "[--tracker-store-on-surface-variant:var(--md-sys-color-on-surface-variant)]",
    "[--tracker-store-muted:var(--md-sys-color-on-surface-variant)]",
    "[--tracker-store-surface:var(--md-sys-color-surface-container-high)]",
    "[--tracker-store-surface-container:var(--md-sys-color-surface-container-highest)]",
    "[--tracker-store-outline:var(--md-sys-color-outline-variant)]",
    "[--tracker-store-primary:var(--md-sys-color-primary)]",
    "[--tracker-store-primary-fill:var(--md-sys-color-primary)]",
    "[--tracker-store-on-primary:var(--md-sys-color-on-primary)]",
    "[--tracker-store-error:var(--md-sys-color-error)]",
    "[--tracker-store-error-container:var(--md-sys-color-error-container)]",
    "[--tracker-store-on-error-container:var(--md-sys-color-on-error-container)]",
    "[--tracker-store-warning:var(--md-sys-color-error)]",
    "[--tracker-store-scrim:var(--md-sys-color-scrim)]",
  ].join(" "),
};

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

export default function TrackerStores(props: { shell?: Shell }) {
  const shell = props.shell ?? "desktop";
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
    <div
      className={`tracker-stores flex flex-col gap-4 ${SHELL_COLOURS[shell]}`}
      data-shell={shell}
    >
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-[var(--tracker-store-on-surface)]">
            Tracker stores
          </h2>
          <p className="mt-1 max-w-2xl text-sm text-[var(--tracker-store-on-surface-variant)]">
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
            className="flex items-center gap-2 rounded-lg border border-[var(--tracker-store-outline)] px-3 py-2 text-sm text-[var(--tracker-store-on-surface)] hover:bg-[var(--tracker-store-surface-container)] disabled:opacity-50"
          >
            <FiRefreshCw className={busy ? "animate-spin" : ""} /> Refresh
          </button>
          <button
            type="button"
            onClick={() => setTyped("")}
            className="flex items-center gap-2 rounded-lg [background:var(--tracker-store-primary-fill)] px-3 py-2 text-sm font-medium text-[var(--tracker-store-on-primary)] hover:opacity-90"
          >
            <FiPlus /> Add
          </button>
        </div>
      </header>

      {error && (
        <div className="flex items-start gap-2 rounded-lg border border-[var(--tracker-store-error)] bg-[var(--tracker-store-error-container)] p-3 text-sm text-[var(--tracker-store-on-error-container)]">
          <FiAlertTriangle className="mt-0.5 shrink-0" />
          <span className="break-words">{error}</span>
        </div>
      )}

      {stores.length === 0 ? (
        <div className="rounded-xl border border-dashed border-[var(--tracker-store-outline)] p-10 text-center">
          <p className="text-sm text-[var(--tracker-store-on-surface-variant)]">
            You have not added a tracker store yet.
          </p>
          <p className="mt-1 text-xs text-[var(--tracker-store-muted)]">
            Without one, this node only uses the trackers and relays you have typed in yourself.
          </p>
        </div>
      ) : (
        <ul className="flex flex-col gap-3">
          {stores.map((store) => (
            <li
              key={store.url}
              className="rounded-xl border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface)] p-4"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <FiTag className="shrink-0 text-[var(--tracker-store-on-surface-variant)]" />
                    <span className="truncate font-medium text-[var(--tracker-store-on-surface)]">
                      {store.name}
                    </span>
                    {store.built_in && (
                      <span className="rounded border border-[var(--tracker-store-outline)] px-1.5 py-0.5 text-[10px] tracking-wide text-[var(--tracker-store-muted)] uppercase">
                        built in
                      </span>
                    )}
                  </div>
                  {store.description && (
                    <p className="mt-1 text-sm text-[var(--tracker-store-on-surface-variant)]">
                      {store.description}
                    </p>
                  )}
                  <p className="mt-1 break-all text-xs text-[var(--tracker-store-muted)]">
                    {store.url}
                  </p>
                  <p className="mt-1 text-xs text-[var(--tracker-store-muted)]">
                    {counts(store)} · {refreshedAt(store)}
                  </p>
                  {store.last_error && (
                    // Beside the services, not instead of them: the list above is the last one
                    // that worked, and it is still in use.
                    <p className="mt-1 text-xs text-[var(--tracker-store-warning)]">
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
                      className="rounded p-2 text-[var(--tracker-store-on-surface-variant)] hover:bg-[var(--tracker-store-surface-container)] hover:text-[var(--tracker-store-on-surface)]"
                    >
                      <FiExternalLink />
                    </a>
                  )}
                  <button
                    type="button"
                    title="Copy the store URL"
                    onClick={() => navigator.clipboard?.writeText(store.url)}
                    className="rounded p-2 text-[var(--tracker-store-on-surface-variant)] hover:bg-[var(--tracker-store-surface-container)] hover:text-[var(--tracker-store-on-surface)]"
                  >
                    <FiCopy />
                  </button>
                  <button
                    type="button"
                    title="Remove this store"
                    onClick={() => remove(store.url)}
                    className="rounded p-2 text-[var(--tracker-store-on-surface-variant)] hover:bg-[var(--tracker-store-surface-container)] hover:text-[var(--tracker-store-error)]"
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
          <label
            className="block text-sm text-[var(--tracker-store-on-surface-variant)]"
            htmlFor="store-url"
          >
            Store index URL
          </label>
          <input
            id="store-url"
            autoFocus
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            placeholder="https://example.org/index.json"
            className="mt-2 w-full rounded-lg border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface-container)] px-3 py-2 text-sm text-[var(--tracker-store-on-surface)] outline-none focus:border-[var(--tracker-store-primary)]"
          />
          <p className="mt-2 text-xs text-[var(--tracker-store-muted)]">Must be https.</p>
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
          <p className="text-sm text-[var(--tracker-store-on-surface)]">
            Do you wish to add the tracker store below?
          </p>
          <p className="mt-3 break-all rounded-lg border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface-container)] p-3 text-sm text-[var(--tracker-store-on-surface)]">
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
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div
        aria-hidden
        className="absolute inset-0 bg-[var(--tracker-store-scrim)] opacity-50"
      />
      <div className="relative w-full max-w-md rounded-2xl border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface)] p-5 shadow-xl">
        <h3 className="text-base font-semibold text-[var(--tracker-store-on-surface)]">
          {props.title}
        </h3>
        <div className="mt-4">{props.children}</div>
        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={props.onCancel}
            className="rounded-lg px-4 py-2 text-sm text-[var(--tracker-store-primary)] hover:bg-[var(--tracker-store-surface-container)]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={props.onConfirm}
            disabled={props.busy || props.confirmDisabled}
            className="rounded-lg px-4 py-2 text-sm font-medium text-[var(--tracker-store-primary)] hover:bg-[var(--tracker-store-surface-container)] disabled:opacity-40"
          >
            {props.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
