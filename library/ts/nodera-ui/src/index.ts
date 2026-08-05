// The shared frontend kit.
//
// It holds what the desktop launcher and the website both need and neither owns: the platform seam,
// and the one URL they must spell identically. The design system joins it when the launcher redesign
// lands — until then the palette lives in `app/ui/src/styles.css` and the site imports it across the
// package boundary, which is a temporary direction for the arrow and nothing else.
//
// **This package writes no Tailwind utility classes.** Not a style preference: Tailwind v4 detects
// its sources relative to the Vite root and does not scan dependency directories, so a class written
// here would resolve to nothing in both applications, silently, and the page would simply render
// plain. `tests/platform-seam.test.mjs` asserts it.
export * from "./platform/index";
export { storeOfferHref } from "./store-link.mjs";
