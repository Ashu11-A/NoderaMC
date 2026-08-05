// The swap point, and it is one line on purpose.
//
// This module resolves to the Tauri host. The website's Vite configuration aliases the specifier
// `nodera-ui/platform/impl` to `./browser.ts` before resolution, so the browser bundle never
// contains a reference to `./tauri` and therefore never contains `@tauri-apps`.
//
// A `window.__TAURI_INTERNALS__` runtime probe was considered and rejected. It reads as the more
// flexible design and it is strictly worse here: both branches survive bundling, so the Tauri import
// lands in the browser build — which is exactly what `no-tauri-in-the-browser-bundle.test.mjs`
// forbids, and it forbids it because a dead import of a native bridge is dead weight that also makes
// the bundle test unable to tell a mistake from a design.
export { platform } from "./tauri";
