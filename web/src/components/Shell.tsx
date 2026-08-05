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
 *
 * `w-full min-w-0` on the outer element is belt to the braces in `styles.css`. That file resets
 * `#root` back to a block, so this is a block child of a block and `w-full` is a no-op — but if the
 * launcher ever reasserts `display: flex` on `#root` in a way this site cannot outrank, `w-full`
 * makes the shell fill its flex container instead of shrink-wrapping its own widest card, and
 * `min-w-0` lets it fall below its min-content width instead of pushing off the right of the screen.
 * The two together are the whole failure this site had, expressed as the two properties that
 * prevent it.
 */
export default function Shell(props: { route: RouteEntry; children: ReactNode }) {
  return (
    <div className="flex min-h-screen w-full min-w-0 flex-col bg-bg">
      <SiteHeader landing={props.route.path === "/"} current={props.route.path} />
      <main className="min-w-0 flex-1">{props.children}</main>
      <SiteFooter />
    </div>
  );
}
