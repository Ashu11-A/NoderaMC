import type { ReactNode } from "react";
import { VERSION, openLimitations } from "./data";
import { HeroFigure } from "./diagrams";

/**
 * The landing page. One screen: a hero, three cards, and the two buttons.
 *
 * It used to be eight bands, and the explanation in them was good — which is why the four
 * how-it-works panels are now the four steps at the top of `/docs/start/what-noderamc-is`, the LAN
 * band is `/docs/start/play-without-the-mod`, and the three doors are the documentation index's own
 * three reading paths. Nothing was deleted; a landing page is a door, and an explanation put in a
 * door is read by whoever was already leaving.
 *
 * Two properties of this file are load-bearing and neither is cosmetic.
 *
 * **It fits a screen.** The section carries `min-h-[calc(100svh-4rem)]` — the viewport less the
 * 64px header — and centres its content in it, so the footer is the only thing under the fold at
 * 1920×1080, 1440×900, 1280×800 and 1000×1000. That is a budget, and every line of copy here is
 * spent against it.
 *
 * **The honesty did not leave with the honesty band.** The hero's caveat carries the generated
 * count of open limitations, straight out of the registers the build reads; `/status` is one click
 * away and lists every row with the test that retires it. A landing page that got shorter by
 * dropping the part about what does not work would be a different kind of page.
 *
 * Every capability sentence here has a row in `web/content/claims.json` naming the file in this
 * repository that backs it and the string that has to be found there, and a test fails the build
 * when either side moves. That is not ceremony: the difference between "a world was re-hosted from
 * the network in about three seconds in a live test" and "your world always stays up" is the
 * difference between a report and a lie.
 */

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
      className="pointer-events-none absolute top-1/2 left-1/2 -z-10 h-40 w-40 -translate-x-1/2 -translate-y-1/2 rounded-full blur-[40px] sm:h-56 sm:w-56 sm:blur-[56px] lg:h-72 lg:w-72 lg:blur-[72px]"
      style={{ backgroundImage: "var(--hero-bloom)", opacity: 0.35 }}
    />
  );
}

function Hero() {
  const open = openLimitations().length;
  return (
    <div className="grid items-center gap-8 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] lg:gap-12">
      <div className="order-2 min-w-0 max-w-[640px] lg:order-1 lg:max-w-none">
        <h1 className="display-type font-bold text-text">
          <span className="bg-brand block bg-clip-text text-4xl leading-[1.06] text-transparent sm:text-5xl lg:text-6xl">
            NoderaMC
          </span>
          <span className="mt-1 block text-2xl leading-tight sm:text-3xl">
            Minecraft without a server
          </span>
        </h1>
        <p className="mt-4 max-w-[560px] text-lg leading-7 text-dim">
          The world is split into 8×8-chunk regions, and a small committee of player-run machines
          simulates and re-checks each one. Any player shares a world straight from the pause menu.
        </p>
        <div className="mt-6 flex flex-wrap items-center gap-3">
          <a
            href="/download"
            className="bg-brand inline-flex h-10 items-center rounded-full px-6 text-sm font-medium text-on-play"
          >
            Download {VERSION}
          </a>
          <a
            href="/docs/"
            className="inline-flex h-10 items-center rounded-full border border-line px-6 text-sm font-medium text-text hover:border-brand-1"
          >
            Read the documentation
          </a>
        </div>
        <p className="mt-4 max-w-[560px] text-xs leading-5 text-faint">
          Alpha — {open} open or retiring limitations,{" "}
          <a href="/status" className="underline decoration-line underline-offset-2 hover:text-dim">
            each with the test that retires it
          </a>
          .
        </p>
      </div>
      <div className="relative order-1 flex min-w-0 justify-center lg:order-2">
        <HeroGlow />
        <HeroFigure />
      </div>
    </div>
  );
}

function ShieldIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <path d="M12 3l7 3v5.5c0 4.2-2.9 7.8-7 9.5-4.1-1.7-7-5.3-7-9.5V6l7-3Z" />
    </svg>
  );
}

function CommitteeIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <circle cx="12" cy="6" r="2.6" />
      <circle cx="5.5" cy="17" r="2.6" />
      <circle cx="18.5" cy="17" r="2.6" />
      <path d="M10.4 8.1 7.1 14.8M13.6 8.1l3.3 6.7M8.1 17h7.8" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7v5.2l3.4 2" />
    </svg>
  );
}

/**
 * A card, dark-first: an elevated fill with a 1px border of its own colour, so the hover state has
 * something to move. `flex-1` on the body is what bottom-aligns the three links across three
 * different lengths of copy — `min-w-0` is what stops the longest word deciding the column width.
 */
function FeatureCard(props: {
  icon: ReactNode;
  tint: string;
  title: string;
  body: ReactNode;
  to: string;
  linkLabel: string;
}) {
  return (
    <article className="flex min-w-0 flex-col rounded-lg border border-surface-2 bg-surface-2 p-4 hover:border-brand-1">
      <div
        className={`mb-3 grid h-9 w-9 place-items-center rounded-sm ${props.tint}`}
        style={{ backgroundColor: "var(--brand-dimm)" }}
      >
        {props.icon}
      </div>
      <h2 className="text-body font-medium text-text">{props.title}</h2>
      <p className="mt-1.5 flex-1 text-sm leading-6 text-dim">{props.body}</p>
      <p className="mt-3">
        <ArrowLink to={props.to}>{props.linkLabel}</ArrowLink>
      </p>
    </article>
  );
}

function Cards() {
  return (
    <div className="grid gap-4 md:grid-cols-3">
      <FeatureCard
        icon={<ShieldIcon />}
        tint="text-brand-1"
        title="No central server"
        body={
          <>
            There is no machine that has to be up for your world to work. Trackers and relays hold no
            authority: the most a hostile one can do is hide a peer from you.
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
        to="/docs/start/what-noderamc-is#how-it-works"
        linkLabel="How validation works"
      />
      <FeatureCard
        icon={<ClockIcon />}
        tint="text-up"
        title="Your world outlives your session"
        body={
          <>
            A headless worker keeps your node on the network with Minecraft closed. In a live test, a
            world whose host was killed was re-hosted by another peer from the network in about three
            seconds.
          </>
        }
        to="/docs/using/when-the-host-leaves"
        linkLabel="When the host leaves"
      />
    </div>
  );
}

export function Landing() {
  return (
    <section className="page-canvas flex min-h-[calc(100svh-4rem)] flex-col justify-center gap-10 overflow-x-clip py-8">
      <Hero />
      <Cards />
    </section>
  );
}
