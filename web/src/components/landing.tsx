import type { ReactNode } from "react";
import { VERSION, openLimitations, status } from "./data";
import { HeroFigure, NoModTunnel, Step1Share, Step2Find, Step3Committee, Step4Rehost } from "./diagrams";

/**
 * The landing page, band by band.
 *
 * Every capability sentence here has a row in `web/content/claims.json` naming the file in this
 * repository that backs it and the string that has to be found there, and a test fails the build
 * when either side moves. That is not ceremony: this is a peer-to-peer system whose parts are at
 * genuinely different stages of doneness, and the difference between "a world was re-hosted from
 * the network in about three seconds in a live test" and "your world always stays up" is the
 * difference between a report and a lie.
 */

function Band(props: { id?: string; tone?: "bg" | "surface"; children: ReactNode }) {
  return (
    <section id={props.id} className={`${props.tone === "surface" ? "bg-surface" : "bg-bg"} py-20 sm:py-24`}>
      <div className="page-canvas">{props.children}</div>
    </section>
  );
}

function ArrowLink(props: { to: string; children: ReactNode }) {
  return (
    <a href={props.to} className="inline-flex items-center gap-1.5 text-sm text-brand-1 hover:text-text">
      {props.children}
      <span aria-hidden="true">→</span>
    </a>
  );
}

/** The accent bloom behind the hero figure. Blur only; there is nothing to read through it. */
export function HeroGlow() {
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none absolute top-1/2 left-1/2 -z-10 h-48 w-48 -translate-x-1/2 -translate-y-1/2 rounded-full blur-[40px] sm:h-64 sm:w-64 sm:blur-[56px] lg:h-80 lg:w-80 lg:blur-[72px]"
      style={{ backgroundImage: "var(--hero-bloom)", opacity: 0.35 }}
    />
  );
}

export function Hero() {
  return (
    <section className="relative overflow-hidden">
      <div className="page-canvas grid items-center gap-12 pt-12 pb-20 lg:grid-cols-[2fr_1fr] lg:pt-20 lg:pb-24">
        <div className="order-2 max-w-[592px] lg:order-1">
          <h1 className="display-type text-4xl leading-tight font-bold text-text sm:text-5xl lg:text-6xl">
            <span className="bg-brand bg-clip-text text-transparent">NoderaMC</span>
            <br />
            Minecraft without a server
          </h1>
          <p className="mt-6 text-lg leading-8 text-dim sm:text-xl">
            The world is split into 8×8-chunk regions, and a small committee of player-run machines
            simulates and re-checks each one. Any player shares a world straight from the pause menu.
          </p>
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <a
              href="/docs/start/what-noderamc-is"
              className="bg-brand inline-flex h-10 items-center rounded-full px-6 text-sm font-medium text-on-play"
            >
              Get started
            </a>
            <a
              href="/download"
              className="inline-flex h-10 items-center rounded-full border border-line px-6 text-sm font-medium text-text hover:border-brand-1"
            >
              Download {VERSION}
            </a>
          </div>
          <p className="mt-6 text-xs text-faint">
            Alpha. Some of Minecraft is not in the validated lane yet —{" "}
            <a href="/status" className="underline decoration-line underline-offset-2 hover:text-dim">
              see what is not done
            </a>
            .
          </p>
        </div>
        <div className="relative order-1 flex justify-center lg:order-2">
          <HeroGlow />
          <HeroFigure />
        </div>
      </div>
    </section>
  );
}

function ShieldIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <path d="M12 3l7 3v5.5c0 4.2-2.9 7.8-7 9.5-4.1-1.7-7-5.3-7-9.5V6l7-3Z" />
    </svg>
  );
}

function CommitteeIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <circle cx="12" cy="6" r="2.6" />
      <circle cx="5.5" cy="17" r="2.6" />
      <circle cx="18.5" cy="17" r="2.6" />
      <path d="M10.4 8.1 7.1 14.8M13.6 8.1l3.3 6.7M8.1 17h7.8" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7v5.2l3.4 2" />
    </svg>
  );
}

export function FeatureCard(props: {
  icon: ReactNode;
  tint: string;
  title: string;
  body: ReactNode;
  to: string;
  linkLabel: string;
}) {
  return (
    <article className="flex flex-col rounded-lg border border-surface-2 bg-surface-2 p-6 hover:border-brand-1">
      <div className={`mb-5 grid h-12 w-12 place-items-center rounded-sm ${props.tint}`} style={{ backgroundColor: "var(--brand-dimm)" }}>
        {props.icon}
      </div>
      <h3 className="text-lg font-medium text-text">{props.title}</h3>
      <p className="mt-3 flex-1 text-body leading-7 text-dim">{props.body}</p>
      <p className="mt-5">
        <ArrowLink to={props.to}>{props.linkLabel}</ArrowLink>
      </p>
    </article>
  );
}

export function FeatureGrid() {
  return (
    <Band>
      <div className="grid gap-5 md:grid-cols-3">
        <FeatureCard
          icon={<ShieldIcon />}
          tint="text-brand-1"
          title="No central server"
          body={
            <>
              There is no machine that has to be up for your world to work. Discovery and NAT
              traversal run through trackers and relays that hold no authority — they can hide a peer
              or list one that cannot be reached, and that is the whole of what they can do.
            </>
          }
          to="/docs/operate/"
          linkLabel="What a tracker actually does"
        />
        <FeatureCard
          icon={<CommitteeIcon />}
          tint="text-brand-3"
          title="A committee, not a host"
          body={
            <>
              Each 8×8-chunk region is simulated by one peer and re-executed by the others in its
              committee. A batch commits only with a quorum, so no single machine — including the one
              that made the world — decides on its own what happened.
            </>
          }
          to="/#how-it-works"
          linkLabel="How validation works"
        />
        <FeatureCard
          icon={<ClockIcon />}
          tint="text-up"
          title="Your world outlives your session"
          body={
            <>
              A headless worker keeps your node on the network with Minecraft closed. In a live test,
              a world whose host was killed was re-hosted by another peer from the network in about
              three seconds.
            </>
          }
          to="/docs/using/when-the-host-leaves"
          linkLabel="When the host leaves"
        />
      </div>
    </Band>
  );
}

const STEPS = [
  {
    art: <Step1Share />,
    title: "You share a world.",
    body: (
      <>
        <strong className="font-medium text-text">Open to Nodera</strong> in the pause menu — vanilla's{" "}
        <em>Open to LAN</em>, one slot over. Your machine announces the world to whichever trackers
        you have configured, and offers its pieces to whoever asks for them.
      </>
    ),
  },
  {
    art: <Step2Find />,
    title: "Other players find it.",
    body: (
      <>
        A tracker answers the question "who is on this world". A relay introduces two machines that
        are both behind a NAT, and bridges an end-to-end-encrypted circuit when there is no direct
        path. Neither can forge a world, an identity or a vote: everything they say is checked
        against signatures the peers already hold.
      </>
    ),
  },
  {
    art: <Step3Committee />,
    title: "Regions get committees.",
    body: (
      <>
        The world is a grid of 8×8-chunk regions. One committee member simulates a region; the others
        re-execute the same inputs and compare the resulting{" "}
        <code className="font-mono text-sm text-brand-3">StateRoot</code>. A batch commits with a
        quorum, or it does not commit.
      </>
    ),
  },
  {
    art: <Step4Rehost />,
    title: "Nobody has to stay.",
    body: (
      <>
        Every peer that has played holds pieces of the world. When the machine that started it goes
        away, a peer that holds the pieces can re-host it.
      </>
    ),
  },
];

export function HowItWorks() {
  return (
    <Band id="how-it-works" tone="surface">
      <h2 className="display-type text-3xl font-bold text-text">How it works</h2>
      <div className="mt-10 grid gap-10 md:grid-cols-2">
        {STEPS.map((step) => (
          <div key={step.title}>
            <div className="rounded-lg border border-line-soft bg-bg p-4 text-dim">{step.art}</div>
            <h3 className="mt-5 text-lg font-medium text-text">{step.title}</h3>
            <p className="mt-2 text-body leading-7 text-dim">{step.body}</p>
          </div>
        ))}
      </div>
      <p className="mt-10 text-sm text-dim">
        That is the design.{" "}
        <a href="/status" className="text-brand-1 hover:text-text">
          Where the build actually stands
        </a>{" "}
        says which parts of Minecraft have made it into the validated lane so far.
      </p>
    </Band>
  );
}

export function NoModBand() {
  return (
    <Band id="no-mod">
      <div className="grid items-center gap-12 lg:grid-cols-2">
        <div>
          <h2 className="display-type text-3xl font-bold text-text">Play with no mod installed</h2>
          <p className="mt-5 text-body leading-7 text-dim">
            The companion app is also how you browse and join NoderaMC worlds from an{" "}
            <strong className="font-medium text-text">unmodified Minecraft</strong>. The host presses
            vanilla's own <em>Open to LAN</em>; the worker hears the beacon, asks the host for
            consent, and publishes the session. You pick it out of a directory in the app and press
            Play, and the worker binds a local port while your normal Minecraft profile starts — from
            the game's point of view it is a server on{" "}
            <code className="font-mono text-sm text-brand-3">127.0.0.1</code>.
          </p>
          <p className="mt-4 text-body leading-7 text-dim">
            Nothing is installed into either game, and{" "}
            <strong className="font-medium text-text">no save is ever downloaded</strong>: the tunnel
            carries the game's own connection and the host stays the sole authority over its world.
            You name a session, never an address — which is what stops every peer being an open proxy
            into its own loopback.
          </p>
          <p className="mt-6">
            <ArrowLink to="/docs/start/play-without-the-mod">How the tunnel works</ArrowLink>
          </p>
        </div>
        <div className="rounded-lg border border-line-soft bg-surface p-6 text-dim">
          <NoModTunnel />
        </div>
      </div>
    </Band>
  );
}

/**
 * The band that says what is not finished.
 *
 * The prose is authored and the count is generated, and that split is the point. Generating the
 * prose is how a page starts lying fluently; typing the count is how it starts being wrong quietly.
 */
export function HonestyBand() {
  const open = openLimitations().length;
  return (
    <Band id="honest" tone="surface">
      <div className="border-l-2 border-warn pl-6">
        <h2 className="display-type text-2xl font-bold text-text">
          NoderaMC is alpha, and it says so where you can check
        </h2>
        <ul className="mt-6 flex max-w-[720px] flex-col gap-4 text-body leading-7 text-dim">
          <li>
            The engine, the network stack and both services are proven Minecraft-free. Wiring them to
            a real <code className="font-mono text-sm text-brand-3">ServerLevel</code> — the live
            validation lane — is the largest piece still open.
          </li>
          <li>
            Redstone, fluids, weather, mobs and lighting are not all in the validated lane yet.
            Regions holding them fall back to ordinary vanilla behaviour rather than being validated.
          </li>
          <li>
            There is no macOS build.{" "}
            <a href="/download#no-macos" className="text-brand-1 hover:text-text">
              The reason is written down.
            </a>
          </li>
        </ul>
        <p className="mt-6 text-body text-dim">
          <strong className="font-medium text-text">
            {open} open or retiring limitations
          </strong>{" "}
          across {status.registerCount} registers, each with an owning task and the exact test that
          retires it.{" "}
          <a href="/status" className="text-brand-1 hover:text-text">
            Read them all →
          </a>
        </p>
      </div>
    </Band>
  );
}

const DOORS = [
  {
    title: "Play it",
    line: "Install the app, install the mod, share a world.",
    to: "/docs/start/what-noderamc-is",
  },
  {
    title: "Run a service",
    line: "A tracker is cheap and holds no authority. The project wants more of them than it runs itself.",
    to: "/docs/operate/",
  },
  {
    title: "Build on it",
    line: "The wire protocol, the engine SDK, and the mod-compatibility contract.",
    to: "/docs/develop/",
  },
];

export function DoorStrip() {
  return (
    <Band id="doors">
      <div className="grid gap-8 md:grid-cols-3 md:gap-0">
        {DOORS.map((door, i) => (
          <div key={door.title} className={i > 0 ? "md:border-l md:border-line-soft md:pl-8" : "md:pr-8"}>
            <h3 className="text-xl font-medium text-text">{door.title}</h3>
            <p className="mt-3 text-body leading-7 text-dim">{door.line}</p>
            <p className="mt-4">
              <ArrowLink to={door.to}>Start here</ArrowLink>
            </p>
          </div>
        ))}
      </div>
    </Band>
  );
}
