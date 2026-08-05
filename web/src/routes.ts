// The route table. Pure data, zero imports.
//
// It is data and it is owned by one workstream on purpose. The build resolves each entry's module
// through `import.meta.glob` in `main.tsx`, so the person writing pages never edits this file and
// the person writing the build never edits a page — the bijection between the two is recovered by
// `web/tests/routes-are-real-files.test.mjs` rather than by a compiler error in a file two people
// are both trying to change.
//
// Titles and descriptions live here and nowhere else. A page module must not render its own
// `<title>`: two answers to "what is this page called" is how a `<title>` and an `<h1>` end up
// disagreeing, and only one of them is what a search result shows.

export interface RouteEntry {
  /** The URL. No trailing slash except "/" and section indexes. */
  path: string;
  /**
   * The module under `web/src/pages`, e.g. `docs/start/install.tsx`.
   *
   * `null` means this URL is not produced by the site generator at all. There is exactly one such
   * route — `/add-store` — and it is deliberate: that page is the project's only live external
   * contract (the README badge links to it) and it ships as the reviewed bytes of
   * `web/add-store.html`, installed by `scripts/build-site.sh`. A React port lands only when it can
   * pass `web/tests/add-store.test.mjs`'s seventeen rules unmodified.
   */
  file: string | null;
  /** `<title>` and `og:title`. */
  title: string;
  /** `<meta name="description">` and `og:description`. 160 characters or fewer. */
  description: string;
  /** A = landing/marketing, B = prose, C = generated data, X = preserved artefact. */
  tier: "A" | "B" | "C" | "X";
  /** A route with a real title and a lede and no fabricated content. Implies noindex. */
  stub: boolean;
  /** False for `/add-store`, `/404` and every stub. */
  sitemap: boolean;
}

export const routes: readonly RouteEntry[] = [
  {
    path: "/",
    file: "index.tsx",
    title: "NoderaMC — Minecraft worlds that do not need a server",
    description:
      "Play together from your own machines. Your world stays up when the person who started it leaves, and nobody has to rent anything.",
    tier: "A",
    stub: false,
    sitemap: true,
  },
  {
    path: "/download",
    file: "download.tsx",
    title: "Download NoderaMC",
    description:
      "Installers for Linux, Windows and Android, the NeoForge and Paper builds, the headless node, and the checksums for all of them.",
    tier: "C",
    stub: false,
    sitemap: true,
  },
  {
    path: "/status",
    file: "status.tsx",
    title: "What works today — NoderaMC",
    description:
      "Every category's finished and unfinished work, and every known limitation, read out of the repository's own registers.",
    tier: "C",
    stub: false,
    sitemap: true,
  },
  {
    path: "/services/",
    file: "services/index.tsx",
    title: "Public trackers and relays — NoderaMC",
    description:
      "The published service list: who runs a tracker or a rendezvous point, where it is, and how to add a list of your own.",
    tier: "C",
    stub: false,
    sitemap: true,
  },
  {
    path: "/privacy",
    file: "privacy.tsx",
    title: "Privacy — NoderaMC",
    description:
      "What this site collects (nothing), what the app collects (nothing unless you opt in), and what a tracker can see about you.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/404",
    file: "404.tsx",
    title: "Not found — NoderaMC",
    description: "That page is not here. The documentation index and the sidebar are.",
    tier: "A",
    stub: false,
    sitemap: false,
  },

  // --- the deep-link hop -------------------------------------------------------------------
  {
    path: "/add-store",
    file: null,
    title: "Add a service list — NoderaMC",
    description:
      "The https hop that hands a publisher's service list to the app. Nothing fires until you click.",
    tier: "X",
    stub: false,
    sitemap: false,
  },

  // --- documentation -----------------------------------------------------------------------
  {
    path: "/docs/",
    file: "docs/index.tsx",
    title: "Documentation — NoderaMC",
    description:
      "Three reading paths: get playing, run a service, or build on the protocol. Start where you are.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/start/what-noderamc-is",
    file: "docs/start/what-noderamc-is.tsx",
    title: "What NoderaMC is",
    description:
      "A Minecraft world hosted by the people playing in it, validated by more than one of them, and kept alive when any of them leaves.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/start/install",
    file: "docs/start/install.tsx",
    title: "Install NoderaMC",
    description:
      "The app, the mod, and what to do when the node cannot take its port. Ten minutes, and nothing to rent.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/start/share-and-join",
    file: "docs/start/share-and-join.tsx",
    title: "Share a world, and join one",
    description:
      "Open a world to the network, hand somebody the link, and see what each of you is actually running.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/start/play-without-the-mod",
    file: "docs/start/play-without-the-mod.tsx",
    title: "Play without the mod",
    description:
      "An unmodified Minecraft can join a shared world through the app's LAN tunnel. Here is what that does and does not give you.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/start/on-android",
    file: "docs/start/on-android.tsx",
    title: "NoderaMC on Android",
    description:
      "The Android companion runs the same node as the desktop app. What it can do today, and what is still open.",
    tier: "B",
    stub: true,
    sitemap: false,
  },
  {
    path: "/docs/using/when-the-host-leaves",
    file: "docs/using/when-the-host-leaves.tsx",
    title: "When the host leaves",
    description:
      "What happens to a world whose host closes the game: who takes it over, how long it takes, and what is lost.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/using/world-ownership",
    file: "docs/using/world-ownership.tsx",
    title: "Who administers a world",
    description:
      "A world has an author, and administration is proved rather than claimed. The rules, and where they are still moving.",
    tier: "B",
    stub: true,
    sitemap: false,
  },
  {
    path: "/docs/faq/network-and-performance",
    file: "docs/faq/network-and-performance.tsx",
    title: "Network and performance",
    description:
      "How much bandwidth a node uses, what it stores, and what it costs to keep somebody else's world alive.",
    tier: "B",
    stub: true,
    sitemap: false,
  },

  // --- operating a service ------------------------------------------------------------------
  {
    path: "/docs/operate/",
    file: "docs/operate/index.tsx",
    title: "Run a service — NoderaMC",
    description:
      "A tracker or a rendezvous point is a hint about where to start looking, never an authority. Here is how to run one.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/operate/tracker",
    file: "docs/operate/tracker.tsx",
    title: "Self-hosting a tracker",
    description:
      "Run the discovery service: install, configure, expose, and prove it is answering.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/operate/rendezvous",
    file: "docs/operate/rendezvous.tsx",
    title: "Self-hosting a rendezvous point",
    description:
      "Run the NAT-traversal service: hole punching, relay fallback, and what it costs to carry other people's traffic.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/operate/tracker-reference",
    file: "docs/operate/tracker-reference.tsx",
    title: "Tracker reference",
    description: "Every tracker setting, endpoint and wire message, as the service itself defines them.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/operate/rendezvous-reference",
    file: "docs/operate/rendezvous-reference.tsx",
    title: "Rendezvous reference",
    description:
      "Every rendezvous setting, endpoint and wire message, as the service itself defines them.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/operate/publish-a-service-list",
    file: "docs/operate/publish-a-service-list.tsx",
    title: "Publish a service list",
    description:
      "A service list is a JSON index served over https. Publish one, and anyone can add it in two clicks.",
    tier: "B",
    stub: false,
    sitemap: true,
  },

  // --- building on it -----------------------------------------------------------------------
  {
    path: "/docs/develop/",
    file: "docs/develop/index.tsx",
    title: "Build on NoderaMC",
    description:
      "The wire protocol, the engine SDK, what a mod has to do to be compatible, and where the work is going.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/develop/wire-protocol",
    file: "docs/develop/wire-protocol.tsx",
    title: "Wire protocol reference",
    description: "Every frame, tag and negotiation rule on the NDR2 wire, in both of its planes.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/develop/engine-sdk",
    file: "docs/develop/engine-sdk.tsx",
    title: "Engine SDK",
    description:
      "The deterministic region engine as an API: what a batch is, what a quorum proves, and what a mod may touch.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/develop/mod-compatibility",
    file: "docs/develop/mod-compatibility.tsx",
    title: "Mod compatibility",
    description:
      "Which mods work alongside NoderaMC, which cannot, and the property that decides which is which.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
  {
    path: "/docs/develop/roadmap",
    file: "docs/develop/roadmap.tsx",
    title: "Roadmap",
    description:
      "Every category's tasks and their real status, mirrored from the repository's own central roadmap.",
    tier: "B",
    stub: false,
    sitemap: true,
  },
];

/**
 * Where a route's HTML file is written under `dist/`.
 *
 * One definition, because the generator, the sitemap and three tests all need the same answer and
 * three copies of `path === "/" ? …` is three chances to disagree about `/404`.
 *
 * `/404` is the exception and it is not cosmetic: Caddy's `handle_errors` rewrites a genuine miss to
 * `/404.html` and serves it with status 404. A `404/index.html` would be a page that answers 200,
 * which is the "every 404 becomes a 200" trade this site exists to avoid making.
 */
export function outputPath(path: string): string {
  if (path === "/") return "index.html";
  if (path === "/404") return "404.html";
  return `${path.replace(/^\/|\/$/g, "")}/index.html`;
}
