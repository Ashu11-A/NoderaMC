import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (name) => readFileSync(new URL(`../src/${name}`, import.meta.url), "utf8");

test("launch choices use backend-issued stable identity", () => {
  const lane = read("play.ts");
  const screen = read("PlayScreen.tsx");

  assert.match(lane, /export interface LaunchTarget \{\s+id: string;/);
  assert.match(screen, /value: target\.id/);
  assert.match(screen, /targetChoice\?\.target\.id/);
  assert.match(screen, /const delegated = launch\.handoff/);
  assert.doesNotMatch(screen, /function targetKey/);
});

test("Discover starts the coordinated launcher instead of ending at a copied address", () => {
  const app = read("App.tsx");
  const discover = read("Network.tsx");
  assert.match(app, /<NetworkScreen[\s\S]*onPlay=[\s\S]*launchPlay\(sessionId, worldName\)/);
  assert.match(discover, /\.onPlay\(sessionId,\s*world\.name \|\| sessionId\)/);
  assert.match(discover, /Starting…[\s\S]*Play/);
  assert.match(app, /launchWorld: \{ id: sessionId, name: worldName \}/);
  assert.match(read("PlayScreen.tsx"), /props\.initialWorld\?\.id \|\| launch\.world_id/);
});

test("connection close failures remain visible and do not remove joined state", () => {
  const play = read("PlayScreen.tsx");
  const discover = read("Network.tsx");

  assert.match(play, /if \(!outcome\.ok\) throw new Error/);
  assert.match(play, /Connection is still open:/);
  assert.match(discover, /if \(outcome\.ok\)[\s\S]*setJoined[\s\S]*else[\s\S]*setError/);
});

test("launch restore subscribes first and cannot overwrite a newer event", () => {
  const play = read("PlayScreen.tsx");
  const lane = read("play.ts");
  const listener = play.indexOf("onLaunch((incoming)");
  const restore = play.indexOf("const restored = await fetchLaunchState()", listener);

  assert.ok(listener >= 0 && restore > listener, "restore must begin after listener registration");
  assert.match(play, /eventRevision === revisionBeforeFetch/);
  assert.match(play, /mergeLaunchState\(current, incoming\)/);
  assert.match(lane, /current\.correlation_id === incoming\.correlation_id/);
  assert.match(lane, /PHASE_ORDER\[incoming\.phase\] < PHASE_ORDER\[current\.phase\]/);
});

test("desktop dialogs share focus trap, scroll lock, and dialog semantics", () => {
  const components = read("components.tsx");
  assert.match(read("Consent.tsx"), /<Modal/);
  assert.match(read("Lan.tsx"), /<Modal/);
  assert.match(read("TrackerStores.tsx"), /<Modal title=\{props\.title\}/);
  assert.match(components, /role="dialog"/);
  assert.match(components, /aria-modal="true"/);
  assert.match(components, /event\.key !== "Tab"/);
  assert.match(components, /document\.body\.style\.overflow = "hidden"/);
  assert.match(components, /previousFocus\?\.focus\(\)/);
});

test("retained tunnels disable Play and remedies execute their own action", () => {
  const play = read("PlayScreen.tsx");
  assert.match(play, /Boolean\(launch\.session_id\)/);
  assert.match(play, /remedy === "retry"[\s\S]*startLaunch\(\)/);
  assert.match(play, /remedy === "install-java"[\s\S]*adoptium\.net/);
  assert.match(play, /remedy === "sign-in"[\s\S]*LIMITATIONS\.md/);
});

test("filter handlers reset pagination before clamp effects run", () => {
  for (const name of ["Network.tsx", "About.tsx", "TrackerStores.tsx"]) {
    const source = read(name);
    assert.doesNotMatch(source, /useEffect\(\(\) => setPage\(1\)/, `${name} races reset and clamp`);
    assert.match(source, /setPage\(1\)/, `${name} does not reset from its input handler`);
  }
});

test("piece map and wide world layout state their real composition", () => {
  const world = read("World.tsx");
  assert.match(world, /const EXACT_PIECE_LIMIT = 2048/);
  assert.match(world, /Exact map: one cell per piece/);
  assert.match(world, /each cell summarizes a contiguous numbered piece range/);
  assert.match(world, /Math\.ceil\(props\.held\.length \/ Math\.max\(1, bucketCount\)\)/);
  assert.match(world, /page-canvas page-grid flex-1/);
  assert.match(world, /wide:col-span-7/);
  assert.match(world, /wide:col-span-5/);
});

/* ------------------------------------------------------------------- the library and the picker */

/**
 * The screens that draw a wall of worlds, and the module each lives in.
 *
 * Two of them now: the library, and the Play screen's picker. They were one grid and one `<select>`
 * before, which is why they could disagree — the rules below are written against the pair so a fix
 * applied to one cannot silently leave the other behind.
 */
const WORLD_WALLS = ["Worlds.tsx", "PlayScreen.tsx"];

test("a wall of worlds reflows instead of being pinned to a fraction of the canvas", () => {
  for (const name of WORLD_WALLS) {
    const source = read(name);
    // The floor lives in `styles.css` as `@utility card-grid`, so two screens of cards cannot end
    // up at different densities on the same window.
    assert.match(source, /className=\{CARD_GRID\}/, `${name} does not use the shared card wall`);
    assert.doesNotMatch(
      source,
      /grid-cols-\[repeat\(auto-/,
      `${name} rolls its own wall instead of consuming the shared one`,
    );
    // A wall in six of twelve columns is two cards across at 1180px and still two at 1920px, which
    // is the "content does not fill the display" report: the window got wider and the grid did not.
    assert.doesNotMatch(
      source,
      /wide:col-span-/,
      `${name} pins a reflowing wall to a fixed fraction of the canvas`,
    );
  }
});

test("the library can be searched, and claims nothing about a library it has not heard of", () => {
  const worlds = read("Worlds.tsx");
  // Thirty worlds with no search is the composition failure this screen was rebuilt for.
  assert.match(worlds, /label="Search your world library"/, "the library has no named search");
  assert.match(worlds, /<FilterBar/);
  // Gated on the worker having reported. A search box over a set nobody has described asserts an
  // empty one, which is the em-dash rule wearing a different hat.
  assert.match(worlds, /const searchable = known && d\.worlds\.length > 0/);
  // A group a query missed is a different fact from a group with nothing in it, and saying "share a
  // world from the pause menu" to somebody who has thirty and mistyped one is the wrong answer.
  assert.match(worlds, /No match in this group/);
});

test("the library's four tiles still separate zero from not-yet-known", () => {
  const worlds = read("Worlds.tsx");
  for (const count of ["connected_worlds", "administered_worlds", "shared_for_others"]) {
    assert.match(
      worlds,
      new RegExp(String.raw`known \? String\(d\.counts\.${count}\) : UNKNOWN`),
      `${count} would render 0 before the worker has spoken`,
    );
  }
  assert.match(worlds, /known \? formatBytes\(d\.counts\.shared_bytes\) : UNKNOWN/);
  // The group chips count the same way: they are the size of a set, and before the first snapshot
  // there is no set — which is why the bar they live in does not render at all.
  assert.match(worlds, /const known = d\.link\.has_data/);
});

test("no card ever renders a confident player count", () => {
  // A denser card is worth nothing if it reports `0 players` where the truth is "no node that is in
  // the world has told us". Only `show` turns null into the em dash, and only the null branch may
  // carry the sentence explaining it — a title on a real figure would explain a number that needs
  // no explaining, and would be wrong.
  for (const name of [...WORLD_WALLS, "World.tsx"]) {
    const source = read(name);
    assert.match(
      source,
      /show\(w\.players, String\)/,
      `${name} renders a player count without passing it through show()`,
    );
    const claims = [...source.matchAll(/^.*"player count unknown".*$/gm)].map((m) => m[0]);
    assert.ok(claims.length > 0 || name === "World.tsx", `${name} stopped explaining its em dash`);
    for (const line of claims) {
      assert.match(
        line,
        /w\.players === null \|\| w\.players === undefined/,
        `${name} attaches "player count unknown" to something other than the unknown branch`,
      );
    }
  }
});

test("the Play screen reports traffic only when it has traffic to report", () => {
  const play = read("PlayScreen.tsx");
  // Three conditions, and each removes a different lie: a broken link would publish the last rates
  // as current, a paused node would publish rates it is deliberately not producing, and a node that
  // has never answered would publish zeroes as measurements.
  assert.match(
    play,
    /!fault && !paused && d\.link\.has_data/,
    "the hero's traffic readout lost one of its three guards",
  );
});

test("settings has one main landmark and every shared search has a name", () => {
  const settings = read("Settings.tsx");
  const components = read("components.tsx");
  assert.doesNotMatch(settings, /<main\b/);
  assert.match(settings, /<section aria-label="Settings content"/);
  assert.match(components, /aria-label=\{props\.label\}/);
  assert.match(read("About.tsx"), /label="Search third-party packages"/);
  assert.match(read("Network.tsx"), /label="Search discoverable worlds"/);
});

test("light theme uses dark, visible accent and focus roles", () => {
  const css = read("styles.css");
  const light = css.slice(css.indexOf(':root[data-theme="light"]'));
  assert.match(light, /--brand-1:\s*#4b3d8f/);
  assert.match(light, /--brand-2:\s*#5f4fa8/);
  assert.match(light, /--brand-3:\s*#145f8a/);
  assert.match(light, /--focus-ring:\s*#4b3d8f/);
  assert.match(light, /--warn:\s*#845400/);
  assert.match(light, /--danger:\s*#b4231a/);
});
