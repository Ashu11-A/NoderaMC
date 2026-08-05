/**
 * The documentation sidebar, hand-authored.
 *
 * It is deliberately **not** derived from the file tree. The repository's own documentation is
 * named for a contract rather than for a reader — `Task.7.md`, `LIMITATIONS.fixed.md`,
 * `REFERENCE.md` — and a nav generated from those names would be unreadable and, worse, would make
 * the site's information architecture hostage to an internal file-naming rule that exists for
 * completely different reasons.
 *
 * Order here is also the order `<PrevNext />` walks, so the flattened list below is the site's one
 * statement of "what comes after this page". Two orderings would eventually disagree, and the one
 * that disagreed silently would be this one.
 */

export interface SidebarLink {
  readonly title: string;
  readonly to: string;
  /**
   * A declared stub: a real title and a real lede, with the rest openly unwritten. It appears here
   * because a route with no way to reach it is debris; it is marked because a reader deserves to
   * know before the click that there is nothing behind it. It is `noindex`, it is absent from the
   * sitemap, and nothing on the landing page or the start-here path links to it.
   */
  readonly stub?: boolean;
  /** Mirrored verbatim from a file in the repository. The page says so, under its heading. */
  readonly mirrored?: boolean;
}

export interface SidebarGroup {
  readonly title: string;
  readonly links: readonly SidebarLink[];
}

export const sidebar: readonly SidebarGroup[] = [
  {
    title: "The project",
    links: [
      { title: "Documentation home", to: "/docs/" },
      { title: "Download", to: "/download" },
      { title: "Trackers and relays", to: "/services/" },
      { title: "Where the build stands", to: "/status" },
    ],
  },
  {
    title: "Getting started",
    links: [
      { title: "What NoderaMC is", to: "/docs/start/what-noderamc-is" },
      { title: "Install", to: "/docs/start/install" },
      { title: "Share a world, and join one", to: "/docs/start/share-and-join" },
      { title: "Play with no mod installed", to: "/docs/start/play-without-the-mod" },
      { title: "On Android", to: "/docs/start/on-android", stub: true },
    ],
  },
  {
    title: "Using NoderaMC",
    links: [
      { title: "When the host leaves", to: "/docs/using/when-the-host-leaves" },
      { title: "Who controls a world", to: "/docs/using/world-ownership", stub: true },
      { title: "Network and performance", to: "/docs/faq/network-and-performance", stub: true },
    ],
  },
  {
    title: "Running a service",
    links: [
      { title: "Why run one", to: "/docs/operate/" },
      { title: "Run a tracker", to: "/docs/operate/tracker", mirrored: true },
      { title: "Run a relay", to: "/docs/operate/rendezvous", mirrored: true },
      { title: "Tracker reference", to: "/docs/operate/tracker-reference", mirrored: true },
      { title: "Relay reference", to: "/docs/operate/rendezvous-reference", mirrored: true },
      { title: "Publish a service list", to: "/docs/operate/publish-a-service-list", mirrored: true },
    ],
  },
  {
    title: "Building on it",
    links: [
      { title: "Start here", to: "/docs/develop/" },
      { title: "Wire protocol", to: "/docs/develop/wire-protocol", mirrored: true },
      { title: "Engine SDK", to: "/docs/develop/engine-sdk", mirrored: true },
      { title: "Mod compatibility", to: "/docs/develop/mod-compatibility", mirrored: true },
      { title: "Roadmap", to: "/docs/develop/roadmap", mirrored: true },
    ],
  },
];

/** The sidebar flattened into reading order. `<PrevNext />` is a lookup into this. */
export const sidebarFlat: readonly SidebarLink[] = sidebar.flatMap((group) => group.links);

/** The previous and next entries in reading order, skipping stubs so nothing leads into one. */
export function neighbours(path: string): { prev?: SidebarLink; next?: SidebarLink } {
  const index = sidebarFlat.findIndex((link) => link.to === path);
  if (index < 0) return {};
  const back = (from: number) => {
    for (let i = from; i >= 0; i -= 1) if (!sidebarFlat[i].stub) return sidebarFlat[i];
    return undefined;
  };
  const forward = (from: number) => {
    for (let i = from; i < sidebarFlat.length; i += 1) if (!sidebarFlat[i].stub) return sidebarFlat[i];
    return undefined;
  };
  return { prev: back(index - 1), next: forward(index + 1) };
}
