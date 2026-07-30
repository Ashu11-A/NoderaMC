// Material 3 dynamic colour — the actual algorithm, not a palette that looks like one.
//
// "Material You" means the whole interface is derived from one source colour: HCT tonal palettes
// are generated from it, and every role (primary, surface, outline, …) is a specific tone of a
// specific palette. That is what `@material/material-color-utilities` implements — it is Google's
// own reference code — so this file uses it rather than hand-picking hex values that approximate
// the result. A hand-picked palette drifts out of contrast compliance the moment anyone edits it;
// a generated one cannot, because the tones are what define the contrast.
//
// The source colour is the user's, which is the "You" part. On a phone the real Material You seed
// comes from the wallpaper, and a WebView cannot read it — so the app asks instead of pretending,
// and the chosen colour is persisted. Saying "pick your colour" is honest; claiming to have read
// the wallpaper would not be.
import {
  argbFromHex,
  hexFromArgb,
  Hct,
  TonalPalette,
  themeFromSourceColor,
} from "@material/material-color-utilities";

/** The default source colour: the Nodera wordmark's magenta. */
export const DEFAULT_SOURCE = "#ff4d8d";

/** Where the chosen source colour is kept between launches. */
const STORAGE_KEY = "nodera.m3.source";

export type Scheme = "light" | "dark";

/** Read the persisted source colour, falling back to the brand. */
export function storedSource(): string {
  try {
    const stored = window.localStorage?.getItem(STORAGE_KEY);
    return stored && /^#[0-9a-fA-F]{6}$/.test(stored) ? stored : DEFAULT_SOURCE;
  } catch {
    // Private mode, or a WebView with storage disabled. The brand colour is a fine answer.
    return DEFAULT_SOURCE;
  }
}

/** Persist the source colour. Failure is silent: it is a preference, not data. */
export function storeSource(hex: string): void {
  try {
    window.localStorage?.setItem(STORAGE_KEY, hex);
  } catch {
    /* not worth telling the user about */
  }
}

/**
 * Generate every `--md-sys-color-*` role from one source colour and write them onto the document.
 *
 * One write repaints the whole tree, so no component takes a colour prop and nothing can end up
 * half-themed.
 *
 * @param sourceHex the seed colour.
 * @param scheme    light or dark.
 */
export function applyDynamicColour(sourceHex: string, scheme: Scheme): void {
  const source = argbFromHex(sourceHex);
  const generated = themeFromSourceColor(source);
  const roles = generated.schemes[scheme].toJSON() as Record<string, number>;
  const root = document.documentElement;

  for (const [role, argb] of Object.entries(roles)) {
    root.style.setProperty(`--md-sys-color-${kebab(role)}`, hexFromArgb(argb));
  }

  // The surface-container family arrived after the scheme object this library exposes, and the M3
  // spec defines each one as a fixed tone of the neutral palette. Deriving them here keeps elevated
  // surfaces tinted by the source colour — which is the visible difference between a Material You
  // surface and a grey one.
  const neutral = TonalPalette.fromHct(Hct.fromInt(source));
  const neutralVariant = generated.palettes.neutralVariant;
  const tones: Record<string, [number, number]> = {
    // role: [light tone, dark tone]
    "surface-dim": [87, 6],
    "surface-bright": [98, 24],
    "surface-container-lowest": [100, 4],
    "surface-container-low": [96, 10],
    "surface-container": [94, 12],
    "surface-container-high": [92, 17],
    "surface-container-highest": [90, 22],
  };
  const neutralPalette = generated.palettes.neutral ?? neutral;
  for (const [role, [light, dark]] of Object.entries(tones)) {
    const tone = scheme === "light" ? light : dark;
    root.style.setProperty(`--md-sys-color-${role}`, hexFromArgb(neutralPalette.tone(tone)));
  }
  root.style.setProperty(
    "--md-sys-color-outline-variant",
    hexFromArgb(neutralVariant.tone(scheme === "light" ? 80 : 30)),
  );
  root.style.colorScheme = scheme;
}

/** `primaryContainer` → `primary-container`, which is what the CSS custom properties are named. */
function kebab(role: string): string {
  return role.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

/**
 * Five source colours offered on the theme picker.
 *
 * A short list rather than a colour wheel: the point is that the interface follows a colour you
 * chose, and five distinct hues demonstrate that in one tap where a wheel asks for a decision
 * nobody wants to make on a phone.
 */
export const SOURCE_CHOICES: { name: string; hex: string }[] = [
  { name: "Nodera", hex: DEFAULT_SOURCE },
  { name: "Violet", hex: "#a855f7" },
  { name: "Cyan", hex: "#22d3ee" },
  { name: "Green", hex: "#34d399" },
  { name: "Amber", hex: "#f59e0b" },
];
