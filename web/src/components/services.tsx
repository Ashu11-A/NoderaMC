import { useEffect, useState } from "react";
import { storeOfferHref } from "nodera-ui";
import type { EndpointStatus, ProbeStamp, ServiceEntry } from "../generated/types";
import { services } from "./data";
import { Badge } from "./primitives";

/**
 * The published service directory, and what each address was last measured doing.
 *
 * The list is generated from the `services` branch through the repository's own resolver, so this
 * page cannot list a tracker the network does not know about and cannot miss one it does. No URL is
 * spelled here.
 *
 * # Why the reachability is not measured in the browser
 *
 * Because it cannot be, twice over. A page cannot open a TCP socket, and the services are
 * `tcp://…:6969` and `:7500` — there is no https endpoint to fetch. Even if there were, the deployed
 * CSP is `connect-src 'self'`, so the page may not call another origin at all.
 *
 * So it is measured off the visitor's machine, by `.github/workflows/service-probe.yml`, published
 * as a dated record on the orphan `probes` branch, and read at build time by
 * `web/scripts/build-services.mjs`. That leaves exactly one thing this file must get right.
 *
 * # The rule this file exists to keep
 *
 * **An interface may not render a confident state it does not have.** A reading has an age, and the
 * age is computed here, in the browser, against the READER's clock — never baked into the HTML,
 * because a static page can sit in a cache for a week and "checked 4 minutes ago" is a sentence that
 * becomes false in silence. Before hydration the absolute timestamp is what ships, which is the
 * honest thing to show when nothing has been able to compute an age yet.
 *
 * And a service nobody has measured is **unknown**, never down. Those are different facts and only
 * one of them is bad news about the network. This is the same rule the launcher's `StaleDataNotice`
 * exists to enforce; a status page is the last place to reintroduce a green light that means "we did
 * not check".
 */

/** Past this, a reading is still shown — with its age — and is no longer presented as current. */
const STALE_AFTER_MS = 24 * 60 * 60 * 1000;

/**
 * The button that hands a list address to the app.
 *
 * It is an ordinary https link to `/add-store`, and that indirection is load-bearing rather than
 * decorative: GitHub's markdown sanitiser strips every scheme but http, https and mailto, so a
 * `nodera://` href in a README is silently deleted. `/add-store` is the https hop that turns a click
 * into the scheme, and it fires nothing on load — the scheme is only ever reached from a press.
 *
 * `indexUrl` is the PUBLISHER'S address, and the prop is named for that rather than `url` on
 * purpose. It used to be `url`, and the generated data used to arrive already composed, so the href
 * was built twice and every button on this page shipped as
 * `/add-store?url=%2Fadd-store%3Furl%3D…` — which the deep-link page refused, correctly, as an
 * address that is not a URL. Two nouns, one of which is a link, is what made that invisible.
 *
 * The href is composed by the shared kit rather than here, because the encoding of that query
 * parameter is a contract with the app's deep-link handler and two implementations of it would
 * eventually encode a `+` differently. That function now throws on a value it produced itself, so
 * the same mistake is a build failure rather than a dead button.
 */
export function AddToNoderaButton(props: { indexUrl: string; label?: string }) {
  return (
    <a
      href={storeOfferHref(props.indexUrl)}
      className="inline-flex h-8 shrink-0 items-center rounded-full border border-brand-1 px-4 text-sm font-medium text-brand-1 hover:bg-surface-hover focus-visible:outline-2 focus-visible:outline-focus"
    >
      {props.label ?? "Add to NoderaMC"}
    </a>
  );
}

/* ------------------------------------------------------------------------------ measuring time */

/** Whole units, largest that fits. Nothing here rounds up into a claim of freshness it does not have. */
function ageInWords(ms: number): string {
  if (ms < 0) return "in the future — one of the two clocks is wrong";
  const minutes = Math.floor(ms / 60000);
  if (minutes < 1) return "less than a minute ago";
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? "" : "s"} ago`;
}

/** The reader's clock, once mounted. `null` on the server and on the first client render. */
function useNow(): number | null {
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    setNow(Date.now());
    // A page left open should not keep saying "2 minutes ago" an hour later.
    const timer = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(timer);
  }, []);
  return now;
}

/**
 * When a measurement was taken: an absolute UTC timestamp in the HTML, an age once there is a clock.
 *
 * Both are always available — the age is the text, the absolute time is the `title` and the
 * `dateTime` — so a reading is never a relative phrase with no anchor under it.
 */
function Measured(props: { iso: string }) {
  const now = useNow();
  const taken = Date.parse(props.iso);
  const absolute = new Date(taken).toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC");
  return (
    <time dateTime={props.iso} title={absolute}>
      {now === null ? absolute : ageInWords(now - taken)}
    </time>
  );
}

/* ---------------------------------------------------------------------------- one address's state */

type Reading = "reachable" | "unreachable" | "unknown";

/** What was measured, reduced to the three states the page is allowed to draw. */
function readingOf(status: EndpointStatus | undefined): Reading {
  if (!status || !status.state) return "unknown";
  return status.state;
}

const DOT: Record<Reading, string> = {
  reachable: "bg-up",
  unreachable: "bg-danger",
  // Deliberately the line colour rather than a fourth signal colour: unknown is the absence of a
  // measurement, and it should not look like a state the network is in.
  unknown: "bg-faint",
};

const WORD: Record<Reading, string> = {
  reachable: "Accepting connections",
  unreachable: "Not answering",
  unknown: "Not measured",
};

const TEXT: Record<Reading, string> = {
  reachable: "text-up",
  unreachable: "text-danger",
  unknown: "text-faint",
};

/**
 * One published address and its reading.
 *
 * The endpoint is shown exactly as published, scheme included: this page is a directory of what the
 * list SAYS, and `tcp://host:port` tidied into `host:port` would be a value nobody published and
 * would hide the one a person has to paste.
 */
function Endpoint(props: { status: EndpointStatus; stale: boolean }) {
  const { status } = props;
  const reading = readingOf(status);
  return (
    <li className="min-w-0">
      <p className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className={`inline-block size-2 shrink-0 self-center rounded-full ${DOT[reading]}`} aria-hidden="true" />
        <span className={`text-sm ${props.stale && reading !== "unknown" ? "text-dim" : TEXT[reading]}`}>
          {WORD[reading]}
        </span>
        {status.rttMs !== null && status.rttMs !== undefined ? (
          <span className="font-mono text-sm text-dim">{status.rttMs} ms</span>
        ) : null}
        {/* A partial result is a fact and reads as one. Three of three is the normal case and says
            nothing; two of three is the shape of a service that is up and struggling. */}
        {status.succeeded !== undefined &&
        status.attempts !== undefined &&
        status.succeeded > 0 &&
        status.succeeded < status.attempts ? (
          <span className="text-sm text-warn">
            {status.succeeded} of {status.attempts} connects
          </span>
        ) : null}
      </p>
      <p className="mt-1 font-mono text-sm break-all text-faint">{status.endpoint}</p>
      {status.peerClosed ? (
        <p className="mt-1 text-sm text-warn">
          The port accepted the connection and then closed it without being asked anything.
        </p>
      ) : null}
      {reading === "unreachable" && status.error ? (
        <p className="mt-1 text-sm text-dim">
          The connection failed with <span className="font-mono">{status.error}</span>.
        </p>
      ) : null}
    </li>
  );
}

function Row(props: { entry: ServiceEntry; stale: boolean }) {
  const { entry } = props;
  return (
    <li className="flex min-w-0 flex-col gap-4 rounded-lg border border-line-soft bg-surface p-5">
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2">
        <p className="min-w-0 text-body font-medium break-words text-text">{entry.name}</p>
        <Badge tone={entry.kind === "tracker" ? "brand" : "muted"}>{entry.kind}</Badge>
      </div>
      <ul className="flex min-w-0 flex-col gap-4">
        {entry.endpoints.map((endpoint, i) => (
          <Endpoint
            key={endpoint}
            status={entry.endpointStatus[i] ?? { endpoint }}
            stale={props.stale}
          />
        ))}
      </ul>
      <div className="mt-auto flex flex-wrap items-center justify-between gap-3">
        {entry.operator ? <p className="min-w-0 text-sm text-dim">Run by {entry.operator}</p> : <span />}
        <AddToNoderaButton indexUrl={entry.storeIndexUrl} />
      </div>
    </li>
  );
}

/* --------------------------------------------------------------------------- what the page says */

/**
 * The line under the wall that dates every reading above it.
 *
 * It is not optional and it is not small print. Every state on this page is a claim about a machine
 * on the other side of the internet, made at a moment that is not now, and the difference between an
 * honest one and a dishonest one is entirely this sentence.
 */
function ProbeNotice(props: { probe: ProbeStamp | null; stale: boolean }) {
  if (!props.probe) {
    return (
      <p className="mt-6 text-sm text-faint" style={{ maxWidth: "var(--prose-measure)" }}>
        Nothing has measured these addresses yet, so this build has nothing to show. That is an
        absent reading, not a report that the services are down — a scheduled probe publishes one,
        and the next build of this page reads it.
      </p>
    );
  }
  return (
    <p className="mt-6 text-sm text-faint" style={{ maxWidth: "var(--prose-measure)" }}>
      {props.stale ? (
        <span className="text-warn">
          These readings are more than a day old. They say what was true when they were taken.{" "}
        </span>
      ) : null}
      Measured <Measured iso={props.probe.measuredAt} /> by {props.probe.measuredBy}
      {props.probe.attempts ? `, ${props.probe.attempts} connects per address` : null}. A connection
      is not a conversation: this measures that the port accepted, not that the service answered
      correctly.
    </p>
  );
}

export function ServiceTable() {
  const now = useNow();
  const probe = services.probe;
  // Before hydration there is no clock, so nothing is called stale: an unproven "this is old" is the
  // same species of claim as an unproven "this is current".
  const stale =
    probe !== null && now !== null && now - Date.parse(probe.measuredAt) > STALE_AFTER_MS;

  if (services.services.length === 0) {
    return (
      <p className="mt-8 text-body text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
        The published list came back empty for this build. That is a list this page could not read,
        not a network with nothing on it.
      </p>
    );
  }
  return (
    <>
      <ul className="card-grid mt-8 gap-4" aria-label="Published services">
        {services.services.map((entry) => (
          <Row key={`${entry.kind}-${entry.name}`} entry={entry} stale={stale} />
        ))}
      </ul>
      <ProbeNotice probe={probe} stale={stale} />
    </>
  );
}
