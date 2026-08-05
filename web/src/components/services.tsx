import { storeOfferHref } from "nodera-ui";
import type { ServiceEntry } from "../generated/types";
import { services } from "./data";
import { Badge } from "./primitives";

/**
 * The published service directory.
 *
 * The list itself is generated from the `services` branch through the repository's own resolver, so
 * this page cannot list a tracker the network does not know about and cannot miss one it does. No
 * URL is spelled here.
 */

/**
 * The button that hands a list address to the app.
 *
 * It is an ordinary https link to `/add-store`, and that indirection is load-bearing rather than
 * decorative: GitHub's markdown sanitiser strips every scheme but http, https and mailto, so a
 * `nodera://` href in a README is silently deleted. `/add-store` is the https hop that turns a click
 * into the scheme, and it fires nothing on load — the scheme is only ever reached from a press.
 *
 * The href is composed by the shared kit rather than here, because the encoding of that query
 * parameter is a contract with the app's deep-link handler and two implementations of it would
 * eventually encode a `+` differently.
 */
export function AddToNoderaButton(props: { url: string; label?: string }) {
  return (
    <a
      href={storeOfferHref(props.url)}
      className="inline-flex h-8 shrink-0 items-center rounded-full border border-brand-1 px-4 text-sm font-medium text-brand-1 hover:bg-surface-hover"
    >
      {props.label ?? "Add to NoderaMC"}
    </a>
  );
}

function Row(props: { entry: ServiceEntry }) {
  const { entry } = props;
  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-3 border-b border-line-soft px-5 py-4 last:border-b-0">
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-center gap-2 text-body text-text">
          {entry.name}
          <Badge tone={entry.kind === "tracker" ? "brand" : "muted"}>{entry.kind}</Badge>
        </p>
        <p className="mt-1 font-mono text-sm break-all text-faint">{entry.endpoints.join("  ·  ")}</p>
        {entry.operator ? <p className="mt-1 text-sm text-dim">Run by {entry.operator}</p> : null}
      </div>
      <AddToNoderaButton url={entry.storeUrl} />
    </li>
  );
}

export function ServiceTable() {
  if (services.services.length === 0) {
    return (
      <p className="mt-8 text-body text-dim">
        The published list came back empty for this build. That is a list this page could not read,
        not a network with nothing on it.
      </p>
    );
  }
  return (
    <ul className="mt-8 rounded-lg border border-line-soft bg-surface">
      {services.services.map((entry) => (
        <Row key={`${entry.kind}-${entry.name}`} entry={entry} />
      ))}
    </ul>
  );
}
