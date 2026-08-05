import { useEffect, useState } from "react";
import { VERSION } from "./data";
import { SearchButton, SearchDialog, useSearch } from "./search";
import { useMounted, usePrefersReducedMotion, useResolvedTheme, useScrollLock } from "./use-theme";

/**
 * The header, the footer, and the theme control.
 *
 * The version in the header is **prerendered**, not patched in after mount. It comes out of the
 * generated release data, which took it from the repository's one `VERSION` file — so there is no
 * way for the site to display a version the build does not have. Shipping the literal string
 * `Get v{version}` in the static HTML and rewriting it on load is the other option, and there is no
 * data split here forcing it.
 */
const REPO = "https://github.com/Ashu11-A/NoderaMC";

const NAV = [
  { label: `Get ${VERSION}`, to: "/download" },
  { label: "Docs", to: "/docs/" },
  { label: "Services", to: "/services/" },
  { label: "Status", to: "/status" },
];

/** The mark. Three lit cells in a region grid — the same figure the hero draws larger. */
function Mark() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6" aria-hidden="true">
      <g fill="none" stroke="currentColor" strokeWidth="1.5">
        <rect x="2.5" y="2.5" width="19" height="19" rx="4" />
      </g>
      <g fill="currentColor">
        <rect x="6" y="6" width="5" height="5" rx="1.2" opacity="0.9" />
        <rect x="13" y="13" width="5" height="5" rx="1.2" opacity="0.55" />
        <rect x="13" y="6" width="5" height="5" rx="1.2" opacity="0.25" />
      </g>
    </svg>
  );
}

export function ThemeToggle() {
  const { resolved, set } = useResolvedTheme();
  const mounted = useMounted();
  return (
    <button
      type="button"
      className="grid h-9 w-9 place-items-center rounded-md text-dim hover:bg-surface-hover hover:text-text focus-visible:outline-2 focus-visible:outline-focus"
      aria-label={mounted ? `Switch to the ${resolved === "dark" ? "light" : "dark"} theme` : "Switch theme"}
      onClick={() => set(resolved === "dark" ? "light" : "dark")}
    >
      <svg viewBox="0 0 24 24" className="h-5 w-5" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 3v18" />
        <path d="M12 3a9 9 0 0 1 0 18" fill="currentColor" stroke="none" />
      </svg>
    </button>
  );
}

export function NavMenu(props: { current?: string }) {
  return (
    <nav className="hidden items-center gap-1 md:flex" aria-label="Site">
      {NAV.map((item) => {
        const active = props.current?.startsWith(item.to) && item.to !== "/download";
        return (
          <a
            key={item.to}
            href={item.to}
            className={`rounded-md px-3 py-1.5 text-sm hover:bg-surface-hover hover:text-text ${
              active ? "text-text" : "text-dim"
            }`}
          >
            {item.label}
          </a>
        );
      })}
    </nav>
  );
}

export function MobileNavOverlay(props: { open: boolean; onClose: () => void }) {
  useScrollLock(props.open);
  if (!props.open) return null;
  return (
    <div className="fixed inset-0 z-50 bg-bg px-6 py-5 md:hidden" role="dialog" aria-modal="true" aria-label="Site navigation">
      <div className="flex h-16 items-center justify-between">
        <span className="display-type text-body font-bold text-text">NoderaMC</span>
        <button
          type="button"
          className="grid h-9 w-9 place-items-center rounded-md text-dim hover:bg-surface-hover hover:text-text"
          onClick={props.onClose}
          aria-label="Close navigation"
        >
          <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      <nav className="mt-6 flex flex-col gap-1" aria-label="Site">
        {NAV.map((item) => (
          <a key={item.to} href={item.to} className="rounded-md px-3 py-3 text-body text-text hover:bg-surface-hover">
            {item.label}
          </a>
        ))}
        <a href={REPO} rel="noopener noreferrer" className="rounded-md px-3 py-3 text-body text-dim hover:bg-surface-hover">
          Source on GitHub
        </a>
      </nav>
    </div>
  );
}

export function SiteHeader(props: { landing?: boolean; current?: string }) {
  const reduced = usePrefersReducedMotion();
  const [atTop, setAtTop] = useState(false);
  const [menu, setMenu] = useState(false);
  // One owner for the dialog and its shortcut. The header is the only place both the button and the
  // dialog are rendered, so a context provider would be ceremony around a boolean.
  const search = useSearch();

  useEffect(() => {
    if (!props.landing || reduced) return;
    let frame = 0;
    const onScroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        setAtTop(window.scrollY === 0);
      });
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, [props.landing, reduced]);

  return (
    <header
      className={`sticky top-0 z-40 h-16 ${
        atTop ? "bg-transparent" : "border-b border-line-soft bg-rail"
      }`}
    >
      <div className="page-canvas flex h-16 items-center justify-between gap-4">
        <a href="/" className="flex items-center gap-2 text-text" aria-label="NoderaMC home">
          <span className="text-brand-1">
            <Mark />
          </span>
          <span className="display-type text-body font-bold">NoderaMC</span>
        </a>
        <div className="flex min-w-0 items-center gap-1">
          <NavMenu current={props.current} />
          {/* Two renderings of one control, not two controls: the labelled button where there is
              room for a label, the icon where there is not. Both open the same dialog, and the
              shortcut works whichever is on screen. */}
          <span className="hidden md:block">
            <SearchButton onOpen={search.onOpen} />
          </span>
          <span className="md:hidden">
            <SearchButton onOpen={search.onOpen} compact />
          </span>
          <a
            href={REPO}
            rel="noopener noreferrer"
            className="hidden h-9 w-9 place-items-center rounded-md text-dim hover:bg-surface-hover hover:text-text md:grid"
            aria-label="Source on GitHub"
          >
            <svg viewBox="0 0 24 24" className="h-5 w-5" fill="currentColor" aria-hidden="true">
              <path d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.61.07-.61 1 .07 1.53 1.03 1.53 1.03.9 1.53 2.36 1.09 2.94.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.64 0 0 .84-.27 2.75 1.02a9.5 9.5 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.37.2 2.39.1 2.64.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2Z" />
            </svg>
          </a>
          <ThemeToggle />
          <button
            type="button"
            className="grid h-9 w-9 place-items-center rounded-md text-dim hover:bg-surface-hover hover:text-text md:hidden"
            aria-label="Open navigation"
            onClick={() => setMenu(true)}
          >
            <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
              <path d="M4 7h16M4 12h16M4 17h16" />
            </svg>
          </button>
        </div>
      </div>
      <MobileNavOverlay open={menu} onClose={() => setMenu(false)} />
      <SearchDialog open={search.open} onClose={search.onClose} />
    </header>
  );
}

const FOOTER = [
  {
    title: "Product",
    links: [
      { label: "Download", to: "/download" },
      { label: "Trackers and relays", to: "/services/" },
      { label: "Where the build stands", to: "/status" },
    ],
  },
  {
    title: "Documentation",
    links: [
      { label: "What NoderaMC is", to: "/docs/start/what-noderamc-is" },
      { label: "Install", to: "/docs/start/install" },
      { label: "Run a service", to: "/docs/operate/" },
      { label: "Build on it", to: "/docs/develop/" },
    ],
  },
  {
    title: "Project",
    links: [
      { label: "Source", to: REPO },
      { label: "Roadmap", to: "/docs/develop/roadmap" },
      { label: "Wire protocol", to: "/docs/develop/wire-protocol" },
    ],
  },
];

/**
 * The build the page was made from is printed when there is one to print.
 *
 * `VITE_SITE_SHA` is set by CI. A local build has no commit to name, and the footer says nothing
 * rather than saying `undefined` or, worse, a plausible placeholder.
 *
 * There is no licence line. This repository has no `LICENSE` file, and the correct response to that
 * is an absent line rather than a typed one — a footer claiming a licence the repository does not
 * declare is a legal claim invented by a web page.
 */
export function SiteFooter() {
  const sha = (import.meta.env?.VITE_SITE_SHA as string | undefined)?.slice(0, 7);
  return (
    <footer className="mt-24 border-t border-line-soft bg-rail">
      <div className="page-canvas py-12">
        {/* `card-grid` rather than `md:grid-cols-3`, which stepped three columns straight to one at
            768px and left a 900px window reading a single stack with the rest of the row empty. A
            14rem floor is narrower than the utility's default because these are short link lists,
            not cards: three columns hold from about 700px up, two below that. */}
        <div className="card-grid gap-8 [--card-min:14rem]">
          {FOOTER.map((column) => (
            <div key={column.title}>
              <p className="mb-3 text-2xs font-medium tracking-wide text-faint uppercase">{column.title}</p>
              <ul className="flex flex-col gap-2">
                {column.links.map((link) => (
                  <li key={link.to}>
                    <a
                      href={link.to}
                      rel={link.to.startsWith("http") ? "noopener noreferrer" : undefined}
                      className="text-sm text-dim hover:text-text"
                    >
                      {link.label}
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <p className="mt-10 border-t border-line-soft pt-6 text-xs text-faint">
          NoderaMC {VERSION}
          {sha ? <> · built from {sha}</> : null} ·{" "}
          <a href="/privacy" className="hover:text-dim">
            Privacy
          </a>{" "}
          · This site collects nothing.
        </p>
      </div>
    </footer>
  );
}
