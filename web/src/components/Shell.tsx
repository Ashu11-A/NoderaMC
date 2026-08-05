import type { ReactNode } from "react";
import type { RouteEntry } from "../routes";
import { SiteFooter, SiteHeader } from "./chrome";

/**
 * The chrome around every page.
 *
 * The build resolves this by glob, so it is optional by construction and a page renders bare
 * without it. Putting the header and the footer here rather than in each page is what makes that
 * useful: there is exactly one place that decides the site has a header, and a page is only ever
 * responsible for its own body.
 *
 * It takes the route entry, which is how the header knows to be transparent on the landing page and
 * opaque everywhere else without a page having to tell it.
 */
export default function Shell(props: { route: RouteEntry; children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-bg">
      <SiteHeader landing={props.route.path === "/"} current={props.route.path} />
      <main className="flex-1">{props.children}</main>
      <SiteFooter />
    </div>
  );
}
