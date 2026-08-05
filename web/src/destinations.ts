/**
 * Every place in the companion app, and every in-game command, that the prose is allowed to name.
 *
 * The prose never spells a screen name. It writes `<Ui to="tracker-stores" />` and this table turns
 * that into **Settings → Tracker stores**; an unknown key fails the build rather than rendering a
 * plausible-looking breadcrumb to a screen that no longer exists. That matters here more than it
 * usually would: the launcher is being redesigned in a sibling worktree as this site ships, so the
 * set of screens is a moving target and the site would otherwise quietly start citing screens that
 * were renamed a month ago.
 *
 * `dependsOn` is the whole breadcrumb mechanism: a destination names the destination it is reached
 * from, and `<Ui>` walks the chain. Renaming **Settings** is then one edit, not nineteen.
 */

export interface Destination {
  /**
   * The label exactly as the app writes it, including its capitalisation. Sentence case is the
   * app's convention; copying it verbatim is the point of this table.
   */
  readonly label: string;
  /** The destination this one is reached from. Omitted for the top-level navigation entries. */
  readonly dependsOn?: string;
  /**
   * The literal command text, for `<Cmd to="…" />`. A destination has either this or a place in the
   * screen tree, never both — a command is typed into the game, not navigated to.
   */
  readonly command?: string;
}

/**
 * The screen labels are read off `app/ui/src/App.tsx`'s `DESTINATIONS` array and
 * `app/ui/src/Settings.tsx`'s `SECTIONS` array. When one of those moves, this table is what has to
 * move with it, and `destinations.test.mjs` is what notices that the prose still points at the old
 * one.
 */
export const destinations: Readonly<Record<string, Destination>> = {
  // --- The three top-level screens.
  play: { label: "Play" },
  library: { label: "Library" },
  discover: { label: "Discover" },

  // --- Reached from Library rather than from the navigation.
  "world-detail": { label: "the world's page", dependsOn: "library" },
  "worlds-you-run": { label: "Worlds you run", dependsOn: "library" },
  "worlds-you-help-share": { label: "Worlds you help share", dependsOn: "library" },

  // --- The LAN lane. A card on **Library**, not a screen of its own, and not on Discover: `LanCard`
  // is rendered by `WorldsScreen`, and what it carries is the HOST's decision about a world this
  // machine can see being opened to LAN. A guest joining a published session browses the directory
  // on Discover instead — a different screen doing a different job, and the two were conflated here.
  "lan-sessions": { label: "Worlds open to LAN", dependsOn: "library" },

  // --- Settings and its sections.
  settings: { label: "Settings" },
  appearance: { label: "Appearance", dependsOn: "settings" },
  behavior: { label: "Behavior", dependsOn: "settings" },
  network: { label: "Network", dependsOn: "settings" },
  "tracker-stores": { label: "Tracker stores", dependsOn: "settings" },
  peers: { label: "Peers", dependsOn: "settings" },
  storage: { label: "Storage", dependsOn: "settings" },
  minecraft: { label: "Minecraft", dependsOn: "settings" },
  diagnostics: { label: "Diagnostics", dependsOn: "settings" },
  "privacy-settings": { label: "Privacy", dependsOn: "settings" },
  about: { label: "About", dependsOn: "settings" },

  // --- Commands, typed in the game's chat.
  "nodera-share": { label: "share a world", command: "/nodera share" },
  "nodera-share-password": {
    label: "change a world's password",
    command: "/nodera share password",
  },
  "nodera-worlds": { label: "list worlds on the network", command: "/nodera worlds" },
  "nodera-selftest": { label: "run the self test", command: "/nodera selftest" },
  "nodera-op": { label: "grant an operator", command: "/nodera op" },
  "nodera-deop": { label: "revoke an operator", command: "/nodera deop" },
  "nodera-telemetry": { label: "telemetry consent", command: "/nodera telemetry" },
  "nodera-debug": { label: "debug output", command: "/nodera debug" },
};

/** The breadcrumb for a screen destination, outermost first. Throws on an unknown key. */
export function breadcrumb(key: string): string[] {
  const seen: string[] = [];
  let cursor: string | undefined = key;
  while (cursor) {
    const entry: Destination | undefined = destinations[cursor];
    if (!entry) {
      throw new Error(
        `destinations: no destination named "${cursor}" (reached from "${key}"). ` +
          `Add it to web/src/destinations.ts or fix the reference.`,
      );
    }
    if (entry.command) {
      throw new Error(`destinations: "${cursor}" is a command; use <Cmd to="${cursor}" />.`);
    }
    if (seen.includes(entry.label)) {
      throw new Error(`destinations: "${key}" has a dependsOn cycle.`);
    }
    seen.unshift(entry.label);
    cursor = entry.dependsOn;
  }
  return seen;
}

/** The literal command text. Throws on an unknown key, or on a key that is a screen. */
export function command(key: string): string {
  const entry = destinations[key];
  if (!entry) {
    throw new Error(
      `destinations: no destination named "${key}". Add it to web/src/destinations.ts or fix the reference.`,
    );
  }
  if (!entry.command) {
    throw new Error(`destinations: "${key}" is a screen; use <Ui to="${key}" />.`);
  }
  return entry.command;
}
