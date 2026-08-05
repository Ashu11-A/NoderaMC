import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { rank } from "nodera-ui/search";
import type { SearchIndex, SearchResult } from "nodera-ui/search";
import { useMounted, useScrollLock } from "./use-theme";

/**
 * Search, without a search service.
 *
 * # Where the work happens, and why it is split there
 *
 * The similarity is **precomputed**. `web/scripts/build-search-index.mjs` walks every built page,
 * counts terms, weights them by how rare they are across the corpus, and ships one unit vector per
 * document. This file does the only part that depends on what somebody typed: tokenize the query,
 * look its terms up, add weights, sort.
 *
 * That split is forced rather than chosen. There is no server — the site is static files behind
 * Caddy — and there may be no third-party search: the deployed CSP is `connect-src 'self'`, which
 * forbids calling one, and an earlier study rejected the reference site's Algolia key for that exact
 * reason. Corpus statistics are the expensive half and they do not change per query, so they are
 * computed once by the machine that already has every page open.
 *
 * # Why the index is fetched rather than bundled
 *
 * Two reasons. It is built from the PRERENDERED pages, which do not exist until after the bundle
 * does — so bundling it is a cycle. And it is fetched on the first press rather than on load, so a
 * visitor who never searches never downloads it. Same origin, which is all the policy permits.
 *
 * # The rule this file keeps
 *
 * A search box that returns nothing looks identical whether the corpus has no match or the index
 * failed to load, and only one of those is the reader's fault. So every state is named: loading,
 * failed with the reason, no matches for this query, or results. It never renders an empty list
 * silently.
 */

/* -------------------------------------------------------------------------------- the index */

/** The shape this file knows how to score. A different one is refused rather than misread. */
const SUPPORTED_VERSION = 1;

/** Where the build writes it. Same origin — the only origin `connect-src 'self'` allows. */
const INDEX_URL = "/search-index.json";

type IndexState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; index: SearchIndex }
  | { status: "failed"; why: string };

/** Fetched once per page load, then held. A second press must not re-download 64 kB. */
let cached: SearchIndex | null = null;

function useSearchIndex(wanted: boolean): IndexState {
  const [state, setState] = useState<IndexState>(() =>
    cached ? { status: "ready", index: cached } : { status: "idle" },
  );

  useEffect(() => {
    if (!wanted || state.status !== "idle") return;
    let cancelled = false;
    setState({ status: "loading" });
    fetch(INDEX_URL)
      .then(async (response) => {
        if (!response.ok) throw new Error(`the server answered ${response.status}`);
        const index = (await response.json()) as SearchIndex;
        if (index.version !== SUPPORTED_VERSION) {
          // A page held in a cache while the index moved on. Refusing is the honest read: scoring
          // against fields that have changed shape produces confident nonsense.
          throw new Error(`this page expects index version ${SUPPORTED_VERSION}, not ${index.version}`);
        }
        if (!Array.isArray(index.terms) || !Array.isArray(index.docs)) {
          throw new Error("the index is not an index");
        }
        cached = index;
        if (!cancelled) setState({ status: "ready", index });
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setState({ status: "failed", why: error instanceof Error ? error.message : String(error) });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [wanted, state.status]);

  return state;
}

/* ---------------------------------------------------------------------------------- the dialog */

/** Where a result goes: the section when one matched, the page otherwise. */
function hrefFor(result: SearchResult): string {
  return result.section ? `${result.doc.p}#${result.section.id}` : result.doc.p;
}

function Results(props: {
  state: IndexState;
  query: string;
  results: SearchResult[];
  active: number;
  onHover: (i: number) => void;
}) {
  const { state, query, results } = props;

  if (state.status === "failed") {
    return (
      <p className="px-5 py-8 text-body text-warn">
        The search index could not be loaded — {state.why}. Nothing is wrong with your search; this
        page could not read the thing it searches.{" "}
        <a href="/docs/" className="underline">
          The documentation index
        </a>{" "}
        lists every page.
      </p>
    );
  }
  if (state.status !== "ready") {
    return <p className="px-5 py-8 text-body text-faint">Loading the index…</p>;
  }
  if (query.trim().length === 0) {
    return (
      <p className="px-5 py-8 text-body text-faint">
        Type to search {state.index.docs.length} pages. Arrow keys move, Enter opens, Escape closes.
      </p>
    );
  }
  if (results.length === 0) {
    return (
      <p className="px-5 py-8 text-body text-dim">
        Nothing on this site matches “{query}”. That is {state.index.docs.length} pages searched and
        none of them about it — not a search that failed.
      </p>
    );
  }
  return (
    <ul id="site-search-results" role="listbox" aria-label="Search results" className="min-w-0 py-2">
      {results.map((result, i) => (
        <li
          key={result.doc.p}
          id={`site-search-result-${i}`}
          role="option"
          aria-selected={i === props.active}
          className={`min-w-0 border-l-2 px-5 py-3 ${
            i === props.active ? "border-brand-1 bg-surface-hover" : "border-transparent"
          }`}
          onMouseMove={() => props.onHover(i)}
        >
          {/* An anchor, so a result can be opened in a new tab and copied as a link — the keyboard
              path activates this same href rather than a second navigation mechanism. */}
          <a href={hrefFor(result)} className="block min-w-0" tabIndex={-1}>
            <span className="block text-body font-medium text-text">{result.doc.t}</span>
            {result.section ? (
              <span className="mt-0.5 block text-sm text-brand-1">§ {result.section.text}</span>
            ) : null}
            <span className="mt-0.5 block text-sm text-dim">{result.doc.d}</span>
            <span className="mt-1 block font-mono text-2xs text-faint">{hrefFor(result)}</span>
          </a>
        </li>
      ))}
    </ul>
  );
}

export function SearchDialog(props: { open: boolean; onClose: () => void }) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const dismiss = useRef<HTMLButtonElement>(null);
  const state = useSearchIndex(props.open);
  useScrollLock(props.open);

  const results = useMemo(
    () => (state.status === "ready" ? rank(state.index, query) : []),
    [state, query],
  );

  useEffect(() => {
    setActive(0);
  }, [query]);

  useEffect(() => {
    if (props.open) input.current?.focus();
  }, [props.open]);

  if (!props.open) return null;

  const go = (i: number) => {
    const result = results[i];
    if (result) window.location.href = hrefFor(result);
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "Escape") {
      event.preventDefault();
      props.onClose();
      return;
    }
    // The focus trap, and it is two elements rather than a library because the dialog has exactly
    // two tab stops: the query and the way out. The results are reached with the arrow keys through
    // `aria-activedescendant`, which is the combobox pattern — focus never leaves the input, so a
    // list of eight anchors never becomes eight tab stops between a reader and the close button.
    if (event.key === "Tab") {
      const first = input.current;
      const last = dismiss.current;
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
      return;
    }
    if (results.length === 0) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((i) => (i + 1) % results.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive((i) => (i - 1 + results.length) % results.length);
    } else if (event.key === "Home") {
      event.preventDefault();
      setActive(0);
    } else if (event.key === "End") {
      event.preventDefault();
      setActive(results.length - 1);
    } else if (event.key === "Enter") {
      event.preventDefault();
      go(active);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex min-w-0 justify-center bg-scrim px-4 pt-[10vh] pb-4">
      {/* The backdrop closes on a press, and is not the dialog's only exit: Escape closes it too,
          and the close button below is reachable by keyboard. A backdrop-only dismissal is a modal
          somebody using a keyboard cannot leave. */}
      <button
        type="button"
        className="absolute inset-0 cursor-default"
        aria-label="Close search"
        tabIndex={-1}
        onClick={props.onClose}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Search this site"
        className="relative flex max-h-[80vh] w-full min-w-0 flex-col overflow-hidden rounded-lg border border-line bg-bg shadow-lg sm:max-w-2xl"
        onKeyDown={onKeyDown}
      >
        <div className="flex min-w-0 items-center gap-3 border-b border-line-soft px-5 py-3">
          <svg viewBox="0 0 24 24" className="h-5 w-5 shrink-0 text-faint" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" />
          </svg>
          <input
            ref={input}
            type="text"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search the documentation"
            className="min-w-0 flex-1 bg-transparent py-1 text-body text-text outline-none placeholder:text-faint"
            // The combobox pattern, spelled out: the input owns the listbox, says whether it is
            // showing options, and names the option the arrow keys are on. Without
            // `aria-activedescendant` a screen reader announces nothing as the selection moves,
            // because focus never leaves the input.
            role="combobox"
            aria-expanded={results.length > 0}
            aria-controls="site-search-results"
            aria-activedescendant={results.length > 0 ? `site-search-result-${active}` : undefined}
            aria-autocomplete="list"
            autoComplete="off"
            spellCheck={false}
          />
          <button
            ref={dismiss}
            type="button"
            onClick={props.onClose}
            aria-label="Close search"
            className="shrink-0 rounded-sm border border-line px-2 py-0.5 text-2xs tracking-wide text-faint uppercase hover:text-text focus-visible:outline-2 focus-visible:outline-focus"
          >
            Esc
          </button>
        </div>
        <div className="min-w-0 flex-1 overflow-y-auto">
          <Results
            state={state}
            query={query}
            results={results}
            active={active}
            onHover={setActive}
          />
        </div>
        {/* Announced rather than merely displayed. Without a live region the count changes silently
            for anybody who cannot see the list, and the box reads as doing nothing. */}
        <p role="status" aria-live="polite" className="sr-only">
          {state.status === "ready" && query.trim().length > 0
            ? `${results.length} result${results.length === 1 ? "" : "s"} for ${query}`
            : ""}
        </p>
      </div>
    </div>
  );
}

/**
 * The visible button, and the shortcut.
 *
 * Both, always. `Ctrl K` is invisible to anybody who has not been told about it, and a search that
 * can only be reached by a shortcut is a search most readers never find; a button with no shortcut
 * is a slower path for everybody who has.
 */
export function SearchButton(props: { onOpen: () => void; compact?: boolean }) {
  const mounted = useMounted();
  // Rendered as `Ctrl K` on the server and corrected after mount. The platform is not knowable
  // during prerender, and a hydration that disagrees with the HTML is a React warning plus a flash.
  const mac = mounted && typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);
  const hint = mac ? "⌘ K" : "Ctrl K";

  if (props.compact) {
    return (
      <button
        type="button"
        onClick={props.onOpen}
        aria-label="Search this site"
        aria-keyshortcuts="Control+K Meta+K"
        className="grid h-9 w-9 place-items-center rounded-md text-dim hover:bg-surface-hover hover:text-text focus-visible:outline-2 focus-visible:outline-focus"
      >
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="M20 20l-3.5-3.5" />
        </svg>
      </button>
    );
  }
  return (
    <button
      type="button"
      onClick={props.onOpen}
      aria-keyshortcuts="Control+K Meta+K"
      className="flex h-9 min-w-0 items-center gap-2 rounded-md border border-line-soft px-3 text-sm text-dim hover:bg-surface-hover hover:text-text focus-visible:outline-2 focus-visible:outline-focus"
    >
      <svg viewBox="0 0 24 24" className="h-4 w-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
        <circle cx="11" cy="11" r="7" />
        <path d="M20 20l-3.5-3.5" />
      </svg>
      <span>Search</span>
      <kbd className="ml-2 hidden rounded-sm border border-line px-1.5 py-px font-sans text-2xs text-faint lg:inline">
        {hint}
      </kbd>
    </button>
  );
}

/**
 * The shortcut, and the state the button and the dialog share.
 *
 * A hook rather than a context: there is one header, it renders both halves, and a provider would be
 * ceremony around a boolean.
 */
export function useSearch(): { open: boolean; onOpen: () => void; onClose: () => void } {
  const [open, setOpen] = useState(false);
  // Where focus was before the dialog took it. Closing a modal and dropping focus on `<body>` sends
  // a keyboard reader back to the top of the document, which is the whole page again.
  const restoreTo = useRef<HTMLElement | null>(null);

  const onOpen = useCallback(() => {
    restoreTo.current = (document.activeElement as HTMLElement) ?? null;
    setOpen(true);
  }, []);
  const onClose = useCallback(() => {
    setOpen(false);
    restoreTo.current?.focus();
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        // Prevented, because Ctrl+K is the browser's own "focus the address bar to search" on some
        // platforms and letting both fire moves focus out of the page that just opened a dialog.
        event.preventDefault();
        setOpen((was) => {
          if (!was) restoreTo.current = (document.activeElement as HTMLElement) ?? null;
          else restoreTo.current?.focus();
          return !was;
        });
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return { open, onOpen, onClose };
}
