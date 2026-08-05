import { PageLayout } from "../../components/docs";

/**
 * The documentation hub.
 *
 * It deliberately renders without the sidebar. The sidebar carries the three declared stubs — marked
 * as such, because a route nothing links to is unreachable debris — and the one page that must never
 * offer a reader a stub is the page a reader lands on when they are trying to find where to start.
 * A hub with three doors is also simply the better shape for this page.
 */

const PATHS = [
  {
    title: "Play it",
    lede: "Start at the top and read down. Four pages, about twenty minutes.",
    links: [
      { to: "/docs/start/what-noderamc-is", label: "What NoderaMC is", note: "Regions, committees, and what this is not." },
      { to: "/docs/start/install", label: "Install", note: "The app first, then the mod. In that order, and here is why." },
      { to: "/docs/start/share-and-join", label: "Share a world, and join one", note: "Both ends of the same session." },
      { to: "/docs/start/play-without-the-mod", label: "Play with no mod installed", note: "The tunnel, for the friend who will not install anything." },
      { to: "/docs/using/when-the-host-leaves", label: "When the host leaves", note: "Why the world does not end when you close the game." },
    ],
  },
  {
    title: "Run a service",
    lede: "A tracker or a relay. Neither holds authority over anyone's world, which is what makes running one a small decision.",
    links: [
      { to: "/docs/operate/", label: "Why run one", note: "What a service can and cannot do." },
      { to: "/docs/operate/tracker", label: "Run a tracker", note: "The image, the configuration, what to watch." },
      { to: "/docs/operate/rendezvous", label: "Run a relay", note: "NAT introduction and circuit fallback." },
      { to: "/docs/operate/publish-a-service-list", label: "Publish a service list", note: "Get yours in front of people." },
    ],
  },
  {
    title: "Build on it",
    lede: "The three documents that are normative, mirrored from the repository so they cannot drift from it.",
    links: [
      { to: "/docs/develop/", label: "Start here", note: "Where the code is and what the gate is." },
      { to: "/docs/develop/wire-protocol", label: "Wire protocol", note: "The NDR2 frame and how two builds negotiate." },
      { to: "/docs/develop/engine-sdk", label: "Engine SDK", note: "The deterministic region engine's surface." },
      { to: "/docs/develop/mod-compatibility", label: "Mod compatibility", note: "What another mod may do inside a validated region." },
    ],
  },
];

export default function Page() {
  return (
    <PageLayout path="/docs/">
      <>
        <p className="mt-6 text-body leading-7 text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
          NoderaMC is not a mod you drop in a folder, and pretending otherwise wastes your first hour.
          It needs a companion app running beside the game, every player in a world runs the mod, and
          the thing that makes it interesting — that the world keeps existing after you close
          Minecraft — is worth understanding before you install anything. The first path below is
          written to be read in order.
        </p>

        <div className="mt-14 grid gap-10 md:grid-cols-3">
          {PATHS.map((path) => (
            <section key={path.title}>
              <h2 className="display-type text-xl font-bold text-text">{path.title}</h2>
              <p className="mt-2 text-sm leading-6 text-dim">{path.lede}</p>
              <ul className="mt-5 flex flex-col gap-4">
                {path.links.map((link) => (
                  <li key={link.to}>
                    <a href={link.to} className="text-body font-medium text-brand-1 hover:text-text">
                      {link.label}
                    </a>
                    <p className="mt-0.5 text-sm leading-6 text-faint">{link.note}</p>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>

        <div className="mt-16 border-t border-line-soft pt-8" style={{ maxWidth: "var(--prose-measure)" }}>
          <h2 className="display-type text-xl font-bold text-text">What is not on this site</h2>
          <p className="mt-3 text-body leading-7 text-dim">
            The repository's own documentation is a specification tree: one folder per component, a
            task file per deliverable, and a register of limitations with an exit test on every row.
            It is written for whoever is doing the work, and it is the thing this site's claims are
            checked against — but it is not a manual. Where a document in it is genuinely written for
            outsiders, it is mirrored here rather than summarised, because a summary is a second copy
            that starts being wrong immediately.
          </p>
          <p className="mt-4 text-body leading-7 text-dim">
            <a href="/status" className="text-brand-1 hover:text-text">
              Where the build stands
            </a>{" "}
            is the honest index of what does not work yet.
          </p>
        </div>
      </>
    </PageLayout>
  );
}
