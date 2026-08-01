// Tracker stores — the lists of trackers and relays this install trusts, and the screen for
// importing more.
//
// ## What was wrong with the first version of this screen
//
// It was a faithful copy of Mihon's extension-repository list: rows, an Add button, a dialog with a
// text field. That shape works there because a Mihon user knows what an extension repository is
// before they go looking for one. Here it left three questions unanswered at the moment they were
// being asked.
//
// **What am I about to trust?** The dialog took a URL and added it. The first thing the user learned
// about the list they had just imported was a row appearing, and the only way to find out it was the
// wrong address was that nothing worked afterwards. So importing is now two steps: fetch, *show what
// was served* — the publisher's own name for the list and every service in it — then add. The fetch
// happens either way; the only change is that its result is shown to the person deciding.
//
// **What is in the ones I already have?** A row said "2 trackers · 1 relay" and stopped. Which
// trackers, run by whom, where — all of it was in the app and none of it was on the screen. Rows
// expand now.
//
// **What do I do from empty?** The empty state said "You have not added a tracker store yet", which
// is a restatement of the empty list. It now offers the project's own list as one button, because
// that is what almost everybody arriving here wants and typing a raw.githubusercontent URL by hand
// is not a thing anyone should be asked to do.
//
// ## One screen, two shells
//
// The desktop rail and the mobile settings page render this same component. Everything visual
// resolves through `--tracker-store-*` custom properties that the shell fills in, so the two look
// native to their platform without two implementations of what a store is allowed to do — two
// copies of that is two places to fix a permission bug.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  FiAlertTriangle,
  FiArrowLeft,
  FiCheck,
  FiChevronDown,
  FiCopy,
  FiExternalLink,
  FiPlus,
  FiRefreshCw,
  FiSearch,
  FiShield,
  FiTrash2,
  FiX,
} from "react-icons/fi";
import {
  addTrackerStore,
  officialStoreUrl,
  pendingTrackerStore,
  previewTrackerStore,
  refreshTrackerStore,
  refreshTrackerStores,
  removeTrackerStore,
  trackerStores,
  type StoreIndex,
  type StoreService,
  type TrackerStore,
} from "./ipc";

/** How often to look for a store offered by a deep link while this screen is open. */
const PENDING_POLL_MS = 1000;

/** Above this many stores, the filter box earns its space. */
const FILTER_THRESHOLD = 3;

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
    "[--tracker-store-surface-hover:var(--surface-hover)]",
    "[--tracker-store-outline:var(--line)]",
    "[--tracker-store-outline-soft:var(--line-soft)]",
    "[--tracker-store-primary:var(--brand-2)]",
    "[--tracker-store-primary-fill:var(--brand-gradient)]",
    "[--tracker-store-on-primary:var(--color-white)]",
    "[--tracker-store-error:var(--danger)]",
    "[--tracker-store-error-container:var(--surface-2)]",
    "[--tracker-store-on-error-container:var(--danger)]",
    "[--tracker-store-warning:var(--warn)]",
    "[--tracker-store-ok:var(--up)]",
    "[--tracker-store-scrim:var(--color-black)]",
    "[--tracker-store-mono:var(--font-mono)]",
  ].join(" "),
  mobile: [
    "[--tracker-store-on-surface:var(--md-sys-color-on-surface)]",
    "[--tracker-store-on-surface-variant:var(--md-sys-color-on-surface-variant)]",
    "[--tracker-store-muted:var(--md-sys-color-on-surface-variant)]",
    "[--tracker-store-surface:var(--md-sys-color-surface-container-high)]",
    "[--tracker-store-surface-container:var(--md-sys-color-surface-container-highest)]",
    "[--tracker-store-surface-hover:var(--md-sys-color-surface-container-highest)]",
    "[--tracker-store-outline:var(--md-sys-color-outline-variant)]",
    "[--tracker-store-outline-soft:var(--md-sys-color-outline-variant)]",
    "[--tracker-store-primary:var(--md-sys-color-primary)]",
    "[--tracker-store-primary-fill:var(--md-sys-color-primary)]",
    "[--tracker-store-on-primary:var(--md-sys-color-on-primary)]",
    "[--tracker-store-error:var(--md-sys-color-error)]",
    "[--tracker-store-error-container:var(--md-sys-color-error-container)]",
    "[--tracker-store-on-error-container:var(--md-sys-color-on-error-container)]",
    "[--tracker-store-warning:var(--md-sys-color-error)]",
    "[--tracker-store-ok:var(--md-sys-color-tertiary)]",
    "[--tracker-store-scrim:var(--md-sys-color-scrim)]",
    "[--tracker-store-mono:var(--font-mono)]",
  ].join(" "),
};

// ---------------------------------------------------------------------------------------------
// Wording
//
// A "tracker store" is a term this project invented, and the screen is where a user meets it. The
// strings below are the only explanation they get, so they say what a store *does* rather than what
// it is: "also look here for addresses to try" is the whole of it.
// ---------------------------------------------------------------------------------------------

const KIND_LABEL: Record<StoreService["kind"], string> = {
  tracker: "Tracker",
  rendezvous: "Relay",
};

const KIND_HELP: Record<StoreService["kind"], string> = {
  tracker: "Finds other players' worlds.",
  rendezvous: "Carries a connection when two players cannot reach each other directly.",
};

function countsOf(services: StoreService[]): { trackers: number; relays: number } {
  return {
    trackers: services.filter((service) => service.kind === "tracker").length,
    relays: services.filter((service) => service.kind === "rendezvous").length,
  };
}

function plural(count: number, one: string, many = `${one}s`): string {
  return `${count} ${count === 1 ? one : many}`;
}

function summarise(services: StoreService[]): string {
  const { trackers, relays } = countsOf(services);
  const parts: string[] = [];
  if (trackers) parts.push(plural(trackers, "tracker"));
  if (relays) parts.push(plural(relays, "relay"));
  return parts.length ? parts.join(" · ") : "no services";
}

/**
 * "4 minutes ago", and an exact timestamp on hover.
 *
 * A locale timestamp is precise and answers the wrong question. What the user is deciding is whether
 * this row is stale, and "3 days ago" answers that at a glance where "27/07/2026, 14:03:11" makes
 * them do arithmetic.
 */
function ago(epochMillis: number): string {
  if (!epochMillis) return "never checked";
  const seconds = Math.max(0, Math.round((Date.now() - epochMillis) / 1000));
  if (seconds < 45) return "checked just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `checked ${plural(minutes, "minute")} ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `checked ${plural(hours, "hour")} ago`;
  return `checked ${plural(Math.round(hours / 24), "day")} ago`;
}

/** The host of a URL, for a row that should read as an address and not as a path. */
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

type Health = "ok" | "stale" | "unknown";

function healthOf(store: TrackerStore): Health {
  if (store.last_error) return "stale";
  return store.last_refreshed_epoch_millis ? "ok" : "unknown";
}

const HEALTH_COLOUR: Record<Health, string> = {
  ok: "bg-[var(--tracker-store-ok)]",
  stale: "bg-[var(--tracker-store-warning)]",
  unknown: "bg-[var(--tracker-store-muted)]",
};

const HEALTH_LABEL: Record<Health, string> = {
  ok: "Answering",
  stale: "Last refresh failed — still using what it said before",
  unknown: "Not checked yet",
};

export default function TrackerStores(props: { shell?: Shell }) {
  const shell = props.shell ?? "desktop";
  const [stores, setStores] = useState<TrackerStore[]>([]);
  const [official, setOfficial] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [filter, setFilter] = useState("");
  const [importer, setImporter] = useState<ImportState>({ step: "closed" });
  const [pendingRemoval, setPendingRemoval] = useState<TrackerStore | null>(null);

  const reload = useCallback(() => {
    trackerStores().then(setStores).catch((e) => setError(String(e)));
  }, []);

  useEffect(reload, [reload]);

  // The official URL is asked for once and kept. It is used both for the empty state's primary
  // action and to recognise the project's own list if the user removed and re-added it by hand.
  useEffect(() => {
    officialStoreUrl().then(setOfficial).catch(() => undefined);
  }, []);

  // A link can arrive while this screen is open, or can have launched the app. Polling rather than
  // subscribing keeps this working on a cold start, where the link is already waiting before any
  // listener could have been registered.
  useEffect(() => {
    let live = true;
    const check = () => {
      pendingTrackerStore()
        .then((url) => {
          if (!live || !url) return;
          // `offered`, and on no account `checking`. A link that arrived from a web page must not
          // cause a request to the address it names before the user has acted: the fetch itself
          // tells that address this install exists and where from, and a link is an intent that a
          // person has not yet agreed to. So the URL is shown and nothing is contacted — which is
          // also why this is not the typed step, where a prefilled box would read as something the
          // user wrote themselves.
          setImporter((current) =>
            current.step === "closed" ? { step: "offered", url, source: "link" } : current,
          );
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

  const officialPresent = useMemo(
    () => stores.some((store) => store.built_in || (official && store.url === official)),
    [stores, official],
  );

  const totals = useMemo(() => {
    const services = stores.flatMap((store) => store.services);
    return { stores: stores.length, ...countsOf(services) };
  }, [stores]);

  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return stores;
    return stores.filter((store) =>
      [store.name, store.description, store.url, ...store.services.flatMap((s) => [s.name, s.region, s.operator, ...s.endpoints])]
        .join(" ")
        .toLowerCase()
        .includes(needle),
    );
  }, [stores, filter]);

  const addConfirmed = async (url: string) => {
    setError("");
    try {
      await addTrackerStore(url);
      setImporter({ step: "closed" });
      reload();
    } catch (e) {
      // Back to the preview with the failure attached. Closing the dialog on a failed add would
      // throw away the address the user just checked and make them paste it again.
      setImporter((current) =>
        current.step === "adding"
          ? { step: "preview", url: current.url, index: current.index, source: current.source, failure: String(e) }
          : current,
      );
    }
  };

  const remove = async (store: TrackerStore) => {
    setError("");
    setPendingRemoval(null);
    try {
      await removeTrackerStore(store.url);
      reload();
    } catch (e) {
      setError(String(e));
    }
  };

  const refreshOne = async (store: TrackerStore) => {
    setError("");
    try {
      const updated = await refreshTrackerStore(store.url);
      setStores((current) => current.map((held) => (held.url === updated.url ? updated : held)));
    } catch (e) {
      setError(String(e));
    }
  };

  const refreshAll = async () => {
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
      className={`tracker-stores flex flex-col gap-5 ${SHELL_COLOURS[shell]}`}
      data-shell={shell}
    >
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h2 className="text-lg font-semibold text-[var(--tracker-store-on-surface)]">
            Tracker stores
          </h2>
          <p className="mt-1 max-w-2xl text-sm text-[var(--tracker-store-on-surface-variant)]">
            A store is a published list of trackers and relays. Adding one means{" "}
            <em>also look here for addresses to try</em> — nothing more.
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <SecondaryButton onClick={refreshAll} disabled={busy || stores.length === 0}>
            <FiRefreshCw className={busy ? "animate-spin" : ""} aria-hidden />
            Refresh all
          </SecondaryButton>
          <PrimaryButton onClick={() => setImporter({ step: "url", url: "", source: "typed" })}>
            <FiPlus aria-hidden />
            Add store
          </PrimaryButton>
        </div>
      </header>

      {stores.length > 0 && (
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-[var(--tracker-store-muted)]">
          <span>{plural(totals.stores, "store")}</span>
          <span aria-hidden>·</span>
          <span>{plural(totals.trackers, "tracker")}</span>
          <span aria-hidden>·</span>
          <span>{plural(totals.relays, "relay")}</span>
          <span aria-hidden>·</span>
          {/* Said once, quietly, where it is relevant — rather than as a paragraph nobody reads. */}
          <span className="inline-flex items-center gap-1">
            <FiShield aria-hidden />
            every service still proves its own identity before this node uses it
          </span>
        </div>
      )}

      {error && (
        <Banner tone="error" onDismiss={() => setError("")}>
          {error}
        </Banner>
      )}

      {stores.length >= FILTER_THRESHOLD && (
        <label className="relative block">
          <FiSearch
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[var(--tracker-store-muted)]"
          />
          <input
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="Filter by name, address or region"
            aria-label="Filter stores"
            className="w-full rounded-lg border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface-container)] py-2 pr-3 pl-9 text-sm text-[var(--tracker-store-on-surface)] outline-none focus:border-[var(--tracker-store-primary)]"
          />
        </label>
      )}

      {stores.length === 0 ? (
        <EmptyState
          official={official}
          onAddOfficial={() =>
            setImporter({ step: "checking", url: official, source: "official" })
          }
          onAddOther={() => setImporter({ step: "url", url: "", source: "typed" })}
        />
      ) : visible.length === 0 ? (
        <p className="rounded-xl border border-dashed border-[var(--tracker-store-outline)] p-8 text-center text-sm text-[var(--tracker-store-on-surface-variant)]">
          No store here matches “{filter}”.
        </p>
      ) : (
        <ul className="flex flex-col gap-3">
          {visible.map((store) => (
            <StoreCard
              key={store.url}
              store={store}
              onRefresh={() => refreshOne(store)}
              onRemove={() => setPendingRemoval(store)}
            />
          ))}
        </ul>
      )}

      {/* The project's own list is one store among however many, so it is offered rather than
          reinstated: a user who removed it did so on purpose, and a screen that quietly put it back
          would make the project the authority this design does not want it to be. */}
      {stores.length > 0 && official && !officialPresent && (
        <Banner tone="hint">
          <span>
            The project's own list is not among these. It is one store like any other, and can be
            added back.
          </span>
          <button
            type="button"
            onClick={() => setImporter({ step: "checking", url: official, source: "official" })}
            className="shrink-0 font-medium text-[var(--tracker-store-primary)] underline underline-offset-2"
          >
            Add it
          </button>
        </Banner>
      )}

      {importer.step !== "closed" && (
        <ImportDialog
          state={importer}
          existing={stores}
          onState={setImporter}
          onConfirm={addConfirmed}
        />
      )}

      {pendingRemoval && (
        <RemoveDialog
          store={pendingRemoval}
          onCancel={() => setPendingRemoval(null)}
          onConfirm={() => remove(pendingRemoval)}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// The import flow
// ---------------------------------------------------------------------------------------------

/** Where the address came from. It changes the wording, and only the wording. */
type ImportSource = "typed" | "link" | "official";

/**
 * The import dialog is a state machine because the steps are genuinely sequential and a boolean per
 * step is how a dialog ends up briefly showing two of them at once.
 */
type ImportState =
  | { step: "closed" }
  // A link's address, shown and not yet contacted. See the deep-link effect for why this step is
  // not optional.
  | { step: "offered"; url: string; source: "link" }
  | { step: "url"; url: string; source: ImportSource; failure?: string }
  | { step: "checking"; url: string; source: ImportSource }
  | { step: "preview"; url: string; index: StoreIndex; source: ImportSource; failure?: string }
  | { step: "adding"; url: string; index: StoreIndex; source: ImportSource };

const SOURCE_TITLE: Record<ImportSource, string> = {
  typed: "Add a tracker store",
  link: "A link wants to add a store",
  official: "Add the project's own list",
};

function ImportDialog(props: {
  state: Exclude<ImportState, { step: "closed" }>;
  existing: TrackerStore[];
  onState: (state: ImportState) => void;
  onConfirm: (url: string) => void;
}) {
  const { state, existing, onState, onConfirm } = props;
  const close = () => onState({ step: "closed" });

  // The fetch that turns an address into something the user can look at. It runs on entering
  // `checking` from any of the three sources, so a typed URL, a link and the official button all
  // converge on one preview and there is one place where "what did that address serve" is decided.
  useEffect(() => {
    if (state.step !== "checking") return;
    let live = true;
    previewTrackerStore(state.url)
      .then((index) => {
        if (live) onState({ step: "preview", url: state.url, index, source: state.source });
      })
      .catch((e) => {
        if (!live) return;
        // A link or the official button has no address field to go back to, so the failure is shown
        // on one — pre-filled, because at that point correcting the address is the only way on.
        onState({ step: "url", url: state.url, source: state.source, failure: String(e) });
      });
    return () => {
      live = false;
    };
  }, [state, onState]);

  if (state.step === "offered") {
    return (
      <Dialog title={SOURCE_TITLE.link} onClose={close}>
        <p className="text-sm text-[var(--tracker-store-on-surface)]">
          A page you opened asked to add the tracker store below.
        </p>
        <Address url={state.url} />
        <p className="mt-3 text-xs text-[var(--tracker-store-on-surface-variant)]">
          Nothing has been added, and this address has not been contacted. Reading it tells its
          operator that this install exists — so that happens when you ask for it, and you will see
          what it serves before deciding.
        </p>
        <DialogActions>
          <GhostButton onClick={close}>Cancel</GhostButton>
          <PrimaryButton
            onClick={() => onState({ step: "checking", url: state.url, source: "link" })}
          >
            Check address
          </PrimaryButton>
        </DialogActions>
      </Dialog>
    );
  }

  if (state.step === "url") {
    return (
      <UrlStep
        state={state}
        onCancel={close}
        onCheck={(url) => onState({ step: "checking", url, source: state.source })}
      />
    );
  }

  if (state.step === "checking") {
    return (
      <Dialog title={SOURCE_TITLE[state.source]} onClose={close}>
        <p className="text-sm text-[var(--tracker-store-on-surface-variant)]">
          Reading the list at this address. Nothing is added yet.
        </p>
        <Address url={state.url} />
        <div className="mt-4 flex items-center gap-2 text-sm text-[var(--tracker-store-muted)]">
          <FiRefreshCw className="animate-spin" aria-hidden />
          Fetching…
        </div>
        <DialogActions>
          <GhostButton onClick={close}>Cancel</GhostButton>
        </DialogActions>
      </Dialog>
    );
  }

  const replacing = existing.some((store) => store.url === state.url);
  const busy = state.step === "adding";
  return (
    <Dialog title={SOURCE_TITLE[state.source]} onClose={busy ? undefined : close}>
      {state.source === "link" && (
        <p className="mb-3 text-sm text-[var(--tracker-store-on-surface)]">
          A page you opened asked to add the store below. It has not been added — that is your
          decision, and this is what the address actually serves.
        </p>
      )}
      <Address url={state.url} />
      <StorePreview index={state.index} />

      {replacing && (
        <p className="mt-3 text-xs text-[var(--tracker-store-warning)]">
          You already have this store. Adding it again replaces what it said before with what it says
          now.
        </p>
      )}
      {state.step === "preview" && state.failure && (
        <p className="mt-3 flex items-start gap-2 text-xs text-[var(--tracker-store-error)]">
          <FiAlertTriangle className="mt-0.5 shrink-0" aria-hidden />
          <span className="break-words">{state.failure}</span>
        </p>
      )}

      <DialogActions>
        {state.source === "typed" ? (
          <GhostButton
            disabled={busy}
            onClick={() => onState({ step: "url", url: state.url, source: state.source })}
          >
            <FiArrowLeft aria-hidden />
            Back
          </GhostButton>
        ) : (
          <GhostButton disabled={busy} onClick={close}>
            Cancel
          </GhostButton>
        )}
        <PrimaryButton
          disabled={busy}
          onClick={() => {
            if (state.step !== "preview") return;
            onState({ step: "adding", url: state.url, index: state.index, source: state.source });
            onConfirm(state.url);
          }}
        >
          {busy ? <FiRefreshCw className="animate-spin" aria-hidden /> : <FiCheck aria-hidden />}
          {replacing ? "Replace store" : "Add store"}
        </PrimaryButton>
      </DialogActions>
    </Dialog>
  );
}

function UrlStep(props: {
  state: Extract<ImportState, { step: "url" }>;
  onCancel: () => void;
  onCheck: (url: string) => void;
}) {
  const [url, setUrl] = useState(props.state.url);
  const ready = url.trim().length > 0;

  return (
    <Dialog title={SOURCE_TITLE[props.state.source]} onClose={props.onCancel}>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) props.onCheck(url.trim());
        }}
      >
        <label
          className="block text-sm font-medium text-[var(--tracker-store-on-surface)]"
          htmlFor="store-url"
        >
          Address of the list
        </label>
        <p className="mt-1 text-xs text-[var(--tracker-store-on-surface-variant)]">
          A publisher gives you this. It must start with <code>https://</code> — a list served over
          plaintext can be rewritten by anyone on the path, which is precisely the decision you would
          be making by adding it.
        </p>
        <input
          id="store-url"
          autoFocus
          inputMode="url"
          spellCheck={false}
          autoCapitalize="none"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://example.org/index.json"
          className="mt-3 w-full rounded-lg border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface-container)] px-3 py-2 font-[family-name:var(--tracker-store-mono)] text-sm text-[var(--tracker-store-on-surface)] outline-none focus:border-[var(--tracker-store-primary)]"
        />
        {props.state.failure && (
          <p className="mt-3 flex items-start gap-2 text-xs text-[var(--tracker-store-error)]">
            <FiAlertTriangle className="mt-0.5 shrink-0" aria-hidden />
            <span className="break-words">{props.state.failure}</span>
          </p>
        )}
        <DialogActions>
          <GhostButton type="button" onClick={props.onCancel}>
            Cancel
          </GhostButton>
          {/* Not "Add". The next thing that happens is a look at what the address serves, and a
              button that promises the wrong outcome is how a user learns to stop reading them. */}
          <PrimaryButton type="submit" disabled={!ready}>
            Check address
          </PrimaryButton>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/** What the address served, as the publisher describes it and as a list of services. */
function StorePreview(props: { index: StoreIndex }) {
  const { index } = props;
  return (
    <div className="mt-4 rounded-xl border border-[var(--tracker-store-outline)]">
      <div className="border-b border-[var(--tracker-store-outline-soft)] p-3">
        <div className="flex items-baseline justify-between gap-3">
          <span className="truncate font-medium text-[var(--tracker-store-on-surface)]">
            {index.name}
          </span>
          <span className="shrink-0 text-xs text-[var(--tracker-store-muted)]">
            {summarise(index.services)}
          </span>
        </div>
        {index.description && (
          <p className="mt-1 text-xs text-[var(--tracker-store-on-surface-variant)]">
            {index.description}
          </p>
        )}
        {index.updated && (
          <p className="mt-1 text-xs text-[var(--tracker-store-muted)]">
            The publisher last changed it on {index.updated}.
          </p>
        )}
      </div>
      <ServiceList services={index.services} />
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// The list of stores
// ---------------------------------------------------------------------------------------------

function StoreCard(props: {
  store: TrackerStore;
  onRefresh: () => Promise<void>;
  onRemove: () => void;
}) {
  const { store } = props;
  const [open, setOpen] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const health = healthOf(store);

  const refresh = async () => {
    setRefreshing(true);
    try {
      await props.onRefresh();
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <li className="overflow-hidden rounded-xl border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface)]">
      <div className="flex items-start gap-3 p-4">
        {/* The whole heading toggles, not a chevron the size of a full stop. */}
        <button
          type="button"
          onClick={() => setOpen((current) => !current)}
          aria-expanded={open}
          className="min-w-0 flex-1 text-left"
        >
          <div className="flex items-center gap-2">
            <span
              aria-hidden
              title={HEALTH_LABEL[health]}
              className={`size-2 shrink-0 rounded-full ${HEALTH_COLOUR[health]}`}
            />
            <span className="truncate font-medium text-[var(--tracker-store-on-surface)]">
              {store.name || hostOf(store.url)}
            </span>
            {store.built_in && (
              <span className="shrink-0 rounded border border-[var(--tracker-store-outline)] px-1.5 py-0.5 text-[10px] tracking-wide text-[var(--tracker-store-muted)] uppercase">
                built in
              </span>
            )}
            <FiChevronDown
              aria-hidden
              className={`ml-auto shrink-0 text-[var(--tracker-store-muted)] transition-transform ${open ? "rotate-180" : ""}`}
            />
          </div>
          {store.description && (
            <p className="mt-1 line-clamp-2 text-sm text-[var(--tracker-store-on-surface-variant)]">
              {store.description}
            </p>
          )}
          <p className="mt-1 text-xs text-[var(--tracker-store-muted)]">
            {summarise(store.services)} ·{" "}
            <span
              title={
                store.last_refreshed_epoch_millis
                  ? new Date(store.last_refreshed_epoch_millis).toLocaleString()
                  : undefined
              }
            >
              {ago(store.last_refreshed_epoch_millis)}
            </span>{" "}
            · <span className="break-all">{hostOf(store.url)}</span>
          </p>
          {store.last_error && (
            // Beside the services, not instead of them: the list above is the last one that worked,
            // and it is still in use.
            <p className="mt-1 flex items-start gap-1.5 text-xs text-[var(--tracker-store-warning)]">
              <FiAlertTriangle className="mt-0.5 shrink-0" aria-hidden />
              <span className="break-words">
                Last refresh failed ({store.last_error}). Still using what it said before.
              </span>
            </p>
          )}
        </button>

        <div className="flex shrink-0 gap-1">
          <IconButton label="Refresh this store" onClick={refresh} disabled={refreshing}>
            <FiRefreshCw className={refreshing ? "animate-spin" : ""} />
          </IconButton>
          <CopyButton value={store.url} label="Copy the store address" />
          {store.homepage && (
            <a
              href={store.homepage}
              target="_blank"
              rel="noreferrer"
              title="Open the publisher's page"
              aria-label="Open the publisher's page"
              className="rounded p-2 text-[var(--tracker-store-on-surface-variant)] hover:bg-[var(--tracker-store-surface-hover)] hover:text-[var(--tracker-store-on-surface)]"
            >
              <FiExternalLink />
            </a>
          )}
          <IconButton label="Remove this store" danger onClick={props.onRemove}>
            <FiTrash2 />
          </IconButton>
        </div>
      </div>

      {open && (
        <div className="border-t border-[var(--tracker-store-outline-soft)]">
          <ServiceList services={store.services} />
          <p className="border-t border-[var(--tracker-store-outline-soft)] px-3 py-2 font-[family-name:var(--tracker-store-mono)] text-[11px] break-all text-[var(--tracker-store-muted)]">
            {store.url}
          </p>
        </div>
      )}
    </li>
  );
}

/**
 * The services a store carries — the thing this screen used to keep to itself.
 *
 * Grouped by kind rather than listed flat, because "which trackers do I have" and "which relays do
 * I have" are the two questions actually being asked, and a mixed list makes both of them counting
 * exercises.
 */
function ServiceList(props: { services: StoreService[] }) {
  const groups: StoreService["kind"][] = ["tracker", "rendezvous"];
  return (
    <div>
      {groups.map((kind) => {
        const rows = props.services.filter((service) => service.kind === kind);
        if (rows.length === 0) return null;
        return (
          <section key={kind}>
            <h4 className="flex items-baseline gap-2 bg-[var(--tracker-store-surface-container)] px-3 py-1.5 text-[11px] font-semibold tracking-wide text-[var(--tracker-store-on-surface-variant)] uppercase">
              {KIND_LABEL[kind]}s
              <span className="text-[10px] font-normal normal-case opacity-80">
                {KIND_HELP[kind]}
              </span>
            </h4>
            <ul>
              {rows.map((service) => (
                <li
                  key={`${service.name}-${service.endpoints.join()}`}
                  className="border-t border-[var(--tracker-store-outline-soft)] px-3 py-2 first:border-t-0"
                >
                  <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                    <span className="text-sm text-[var(--tracker-store-on-surface)]">
                      {service.name}
                    </span>
                    {service.region && (
                      <span className="text-xs text-[var(--tracker-store-muted)]">
                        {service.region}
                      </span>
                    )}
                    {service.operator && (
                      <span className="text-xs text-[var(--tracker-store-muted)]">
                        run by {service.operator}
                      </span>
                    )}
                    {service.node_id && (
                      // Worth surfacing: an entry that publishes its identity is one where a
                      // hijacked DNS name is something a peer notices rather than accepts.
                      <span
                        title={`Published identity ${service.node_id}`}
                        className="inline-flex items-center gap-1 text-xs text-[var(--tracker-store-ok)]"
                      >
                        <FiShield aria-hidden />
                        identified
                      </span>
                    )}
                  </div>
                  {service.endpoints.map((endpoint) => (
                    <div key={endpoint} className="mt-1 flex items-center gap-1">
                      <code className="min-w-0 flex-1 truncate font-[family-name:var(--tracker-store-mono)] text-xs text-[var(--tracker-store-on-surface-variant)]">
                        {endpoint}
                      </code>
                      <CopyButton value={endpoint} label={`Copy ${endpoint}`} small />
                    </div>
                  ))}
                  {service.notes && (
                    <p className="mt-1 text-xs text-[var(--tracker-store-muted)]">{service.notes}</p>
                  )}
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function EmptyState(props: {
  official: string;
  onAddOfficial: () => void;
  onAddOther: () => void;
}) {
  return (
    <div className="rounded-xl border border-dashed border-[var(--tracker-store-outline)] p-8 text-center">
      <h3 className="text-base font-medium text-[var(--tracker-store-on-surface)]">
        No stores yet
      </h3>
      <p className="mx-auto mt-2 max-w-md text-sm text-[var(--tracker-store-on-surface-variant)]">
        Without one, this node only uses the trackers and relays you type in yourself — so it will
        not find worlds it was not told about.
      </p>
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        {props.official && (
          <PrimaryButton onClick={props.onAddOfficial}>
            <FiPlus aria-hidden />
            Add the project's list
          </PrimaryButton>
        )}
        <SecondaryButton onClick={props.onAddOther}>Add another store</SecondaryButton>
      </div>
      <p className="mx-auto mt-4 max-w-md text-xs text-[var(--tracker-store-muted)]">
        The project's list has no special standing. It is one store among however many you choose,
        and you can remove it like any other.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------------------------

function RemoveDialog(props: { store: TrackerStore; onCancel: () => void; onConfirm: () => void }) {
  const { trackers, relays } = countsOf(props.store.services);
  return (
    <Dialog title="Remove this store?" onClose={props.onCancel}>
      <p className="text-sm text-[var(--tracker-store-on-surface)]">
        <strong className="font-medium">{props.store.name || hostOf(props.store.url)}</strong> and
        the {plural(trackers, "tracker")} and {plural(relays, "relay")} it contributes will stop
        being used.
      </p>
      <p className="mt-2 text-xs text-[var(--tracker-store-on-surface-variant)]">
        Addresses you typed in yourself are not affected, and you can add this store again at any
        time.
      </p>
      <Address url={props.store.url} />
      <DialogActions>
        <GhostButton onClick={props.onCancel}>Cancel</GhostButton>
        <DangerButton onClick={props.onConfirm}>Remove</DangerButton>
      </DialogActions>
    </Dialog>
  );
}

/** An address shown for reading, not for editing. Monospace, wrapped, never truncated. */
function Address(props: { url: string }) {
  return (
    <p className="mt-3 rounded-lg border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface-container)] p-3 font-[family-name:var(--tracker-store-mono)] text-xs break-all text-[var(--tracker-store-on-surface)]">
      {props.url}
    </p>
  );
}

function Banner(props: {
  tone: "error" | "hint";
  onDismiss?: () => void;
  children: React.ReactNode;
}) {
  const error = props.tone === "error";
  return (
    <div
      role={error ? "alert" : undefined}
      className={`flex items-start gap-2 rounded-lg border p-3 text-sm ${
        error
          ? "border-[var(--tracker-store-error)] bg-[var(--tracker-store-error-container)] text-[var(--tracker-store-on-error-container)]"
          : "border-[var(--tracker-store-outline)] text-[var(--tracker-store-on-surface-variant)]"
      }`}
    >
      {error && <FiAlertTriangle className="mt-0.5 shrink-0" aria-hidden />}
      <span className="flex min-w-0 flex-1 flex-wrap items-center gap-2 break-words">
        {props.children}
      </span>
      {props.onDismiss && (
        <button
          type="button"
          onClick={props.onDismiss}
          aria-label="Dismiss"
          className="shrink-0 rounded p-0.5 opacity-70 hover:opacity-100"
        >
          <FiX />
        </button>
      )}
    </div>
  );
}

/** Copy, with the confirmation on the button itself rather than in a toast somewhere else. */
function CopyButton(props: { value: string; label: string; small?: boolean }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <IconButton
      label={props.label}
      small={props.small}
      onClick={() => {
        navigator.clipboard?.writeText(props.value);
        setCopied(true);
        clearTimeout(timer.current);
        timer.current = setTimeout(() => setCopied(false), 1400);
      }}
    >
      {copied ? <FiCheck className="text-[var(--tracker-store-ok)]" /> : <FiCopy />}
    </IconButton>
  );
}

function IconButton(props: {
  label: string;
  onClick: () => void;
  children: React.ReactNode;
  disabled?: boolean;
  danger?: boolean;
  small?: boolean;
}) {
  return (
    <button
      type="button"
      title={props.label}
      aria-label={props.label}
      disabled={props.disabled}
      onClick={props.onClick}
      className={`rounded text-[var(--tracker-store-on-surface-variant)] hover:bg-[var(--tracker-store-surface-hover)] disabled:opacity-40 ${
        props.small ? "p-1" : "p-2"
      } ${
        props.danger
          ? "hover:text-[var(--tracker-store-error)]"
          : "hover:text-[var(--tracker-store-on-surface)]"
      }`}
    >
      {props.children}
    </button>
  );
}

type ButtonProps = {
  children: React.ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  type?: "button" | "submit";
};

const BUTTON_BASE =
  "flex items-center gap-2 rounded-lg px-3 py-2 text-sm disabled:opacity-50 disabled:pointer-events-none";

function PrimaryButton(props: ButtonProps) {
  return (
    <button
      type={props.type ?? "button"}
      onClick={props.onClick}
      disabled={props.disabled}
      className={`${BUTTON_BASE} [background:var(--tracker-store-primary-fill)] font-medium text-[var(--tracker-store-on-primary)] hover:opacity-90`}
    >
      {props.children}
    </button>
  );
}

function SecondaryButton(props: ButtonProps) {
  return (
    <button
      type={props.type ?? "button"}
      onClick={props.onClick}
      disabled={props.disabled}
      className={`${BUTTON_BASE} border border-[var(--tracker-store-outline)] text-[var(--tracker-store-on-surface)] hover:bg-[var(--tracker-store-surface-hover)]`}
    >
      {props.children}
    </button>
  );
}

function GhostButton(props: ButtonProps) {
  return (
    <button
      type={props.type ?? "button"}
      onClick={props.onClick}
      disabled={props.disabled}
      className={`${BUTTON_BASE} text-[var(--tracker-store-primary)] hover:bg-[var(--tracker-store-surface-hover)]`}
    >
      {props.children}
    </button>
  );
}

function DangerButton(props: ButtonProps) {
  return (
    <button
      type={props.type ?? "button"}
      onClick={props.onClick}
      disabled={props.disabled}
      className={`${BUTTON_BASE} font-medium text-[var(--tracker-store-error)] hover:bg-[var(--tracker-store-surface-hover)]`}
    >
      {props.children}
    </button>
  );
}

function DialogActions(props: { children: React.ReactNode }) {
  return <div className="mt-6 flex justify-end gap-2">{props.children}</div>;
}

/**
 * The dialog shell.
 *
 * Escape and a click on the scrim both close it, except while a store is being added — an action
 * already in flight that a stray keypress abandons halfway is worse than one the user has to
 * finish.
 */
function Dialog(props: { title: string; onClose?: () => void; children: React.ReactNode }) {
  const { onClose } = props;
  useEffect(() => {
    if (!onClose) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <button
        aria-hidden
        tabIndex={-1}
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-[var(--tracker-store-scrim)] opacity-50"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={props.title}
        className="relative max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-2xl border border-[var(--tracker-store-outline)] bg-[var(--tracker-store-surface)] p-5 shadow-xl"
      >
        <h3 className="text-base font-semibold text-[var(--tracker-store-on-surface)]">
          {props.title}
        </h3>
        <div className="mt-3">{props.children}</div>
      </div>
    </div>
  );
}
