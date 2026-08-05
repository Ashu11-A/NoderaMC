// The Theme screen: define an appearance, edit its CSS, keep it under a name.
//
// # The one rule that shapes this whole file
//
// Nothing is written to disk until it is kept. Applying a theme paints it immediately and starts a
// countdown; if the user does not press **Keep this theme** the window puts the previous appearance
// back. That is not a nicety — a theme can make the app unusable, and a theme that can make the app
// unusable *and* survives a restart takes the install with it. So `saveSettings` is called from one
// place, the keep handler, and from nowhere else. `theme-screen.test.mjs` pins that shape.
//
// # Two editing surfaces, one of which cannot go wrong
//
// **Tokens** is a structured editor over a fixed allowlist (`themetokens.ts`). A theme authored
// there is a `Record<name, value>` and cannot contain a selector, a property name or an at-rule —
// the app emits the declarations itself, from names it chose. It is provably safe and it is what
// almost everyone will use.
//
// **CSS** is free-form, and everything written there goes through `themecss.ts`, which does not
// filter it — it parses it with the platform's own parser and rebuilds it. What comes back is
// wrapped in `@layer nodera.user`, scoped to this theme's own selector, and can be uninstalled
// completely by deselecting the theme.
import { useEffect, useMemo, useRef, useState } from "react";
import { FiCode, FiCopy, FiDownload, FiDroplet, FiPlus, FiRotateCcw, FiSliders, FiTrash2 } from "react-icons/fi";
import {
  Banner,
  Button,
  Card,
  Empty,
  Modal,
  PageGrid,
  PageHeader,
  Tabs,
  cx,
} from "./components";
import { applyCustomTheme } from "./customtheme";
import { sanitise } from "./themecss";
import { THEME_TOKENS, TOKEN_GROUPS, isColour, isValid } from "./themetokens";
import { saveSettings, type CustomTheme, type Settings as SettingsDoc, type Themes } from "./ipc";

/** How long an applied appearance has to be confirmed before the window puts the old one back. */
const KEEP_SECONDS = 15;

const EMPTY_THEMES: Themes = { selected: "", custom: [] };

/** Ids are selector fragments, so they are minted here and never taken from anything typed. */
function mintId(): string {
  return `t${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
}

export function ThemeScreen(props: {
  settings: SettingsDoc | null;
  onChange: (next: SettingsDoc) => void;
  resolved: "dark" | "light";
}) {
  const stored = props.settings?.appearance.themes ?? EMPTY_THEMES;
  // The desired state of `appearance.themes`, painted but not yet written to disk. `null` means
  // what is on screen is what is stored.
  const [pending, setPending] = useState<Themes | null>(null);
  const [editing, setEditing] = useState<string>(stored.selected);
  const [tab, setTab] = useState<"tokens" | "css">("tokens");
  const [remaining, setRemaining] = useState(KEEP_SECONDS);
  const [saveError, setSaveError] = useState("");
  const [share, setShare] = useState<"export" | "import" | null>(null);
  const [importText, setImportText] = useState("");

  const effective = pending ?? stored;
  const draft = effective.custom.find((t) => t.id === editing);
  const previewing = effective.custom.find((t) => t.id === effective.selected);

  // Paint whatever is pending, and put the stored appearance back when this screen goes away
  // without a keep. The shell owns the applied theme in the ordinary case; this only overrides it
  // while an unsaved edit is on screen.
  useEffect(() => {
    if (!pending) return;
    applyCustomTheme(previewing);
  }, [pending, previewing]);

  const revert = useRef(() => {});
  revert.current = () => {
    setPending(null);
    applyCustomTheme(stored.custom.find((t) => t.id === stored.selected));
  };
  useEffect(() => () => revert.current(), []);

  // The countdown. Restarted on every change, because each edit is a fresh thing to confirm.
  useEffect(() => {
    if (!pending) return;
    setRemaining(KEEP_SECONDS);
    const timer = window.setInterval(() => {
      setRemaining((left) => {
        if (left <= 1) {
          window.clearInterval(timer);
          revert.current();
          return KEEP_SECONDS;
        }
        return left - 1;
      });
    }, 1000);
    return () => window.clearInterval(timer);
  }, [pending]);

  const propose = (next: Themes) => {
    setSaveError("");
    setPending(next);
  };

  const onKeep = () => {
    const settings = props.settings;
    if (!settings || !pending) return;
    const next: SettingsDoc = JSON.parse(JSON.stringify(settings));
    next.appearance.themes = pending;
    saveSettings(next)
      .then(() => {
        props.onChange(next);
        setPending(null);
      })
      .catch((failure: unknown) => setSaveError(String(failure)));
  };

  const editDraft = (change: (theme: CustomTheme) => void) => {
    if (!draft) return;
    const copy: CustomTheme = JSON.parse(JSON.stringify(draft));
    change(copy);
    copy.updated_at = Math.floor(Date.now() / 1000);
    propose({
      selected: copy.id,
      custom: effective.custom.map((t) => (t.id === copy.id ? copy : t)),
    });
  };

  const create = (from?: CustomTheme) => {
    const theme: CustomTheme = {
      id: mintId(),
      name: from ? `${from.name} copy` : "New appearance",
      base: from?.base ?? props.resolved,
      tokens: from ? { ...from.tokens } : {},
      css: from?.css ?? "",
      updated_at: Math.floor(Date.now() / 1000),
    };
    setEditing(theme.id);
    setTab("tokens");
    propose({ selected: theme.id, custom: [...effective.custom, theme] });
  };

  // Measured **after** the paint, not during it.
  //
  // `contrastReport` reads `getComputedStyle`, and the `<style>` element carrying this appearance is
  // written by an effect — so a report computed during render reflects the *previous* edit. That is
  // a report that says a theme passes when the change that broke it is already on screen, which is
  // the one failure mode a contrast report exists to prevent. In an effect it reads what the window
  // is actually rendering.
  const [report, setReport] = useState<ContrastRow[]>([]);
  useEffect(() => {
    setReport(contrastReport());
  }, [pending, effective.selected, draft?.tokens, draft?.css, draft?.base]);
  const rewritten = useMemo(
    () => (draft ? sanitise(draft.css, draft.id, draft.base) : null),
    [draft?.css, draft?.id, draft?.base],
  );

  return (
    <PageGrid className="gap-y-5">
      <PageHeader
        eyebrow="Appearance"
        title="Themes"
        description="Change the palette this window renders with, or write CSS against it. An appearance is a patch on Dark or Light — anything you do not set keeps working."
      />

      {!props.settings && (
        <div className="col-span-12">
          <Banner tone="warn">
            The settings document has not loaded yet, so nothing can be kept. Nothing here is lost —
            it simply cannot be written until the app has read the file it would write to.
          </Banner>
        </div>
      )}

      {pending && (
        <div className="col-span-12">
          <Banner
            tone="info"
            action={
              <span className="flex items-center gap-2">
                <Button variant="ghost" shape="pill" onClick={() => revert.current()}>
                  <FiRotateCcw aria-hidden /> Revert
                </Button>
                <Button variant="primary" shape="pill" disabled={!props.settings} onClick={onKeep}>
                  Keep this theme
                </Button>
              </span>
            }
          >
            This appearance is being previewed, not saved. Reverting in {remaining}s.
          </Banner>
        </div>
      )}

      {saveError && (
        <div className="col-span-12">
          <Banner tone="danger">Could not save: {saveError.replace(/^Error:\s*/, "")}</Banner>
        </div>
      )}

      {/* ------------------------------------------------------------------ the list */}
      <div className="col-span-12 wide:col-span-3">
        <Card title="Appearances">
          <p className="mt-2 mb-1 text-2xs font-medium tracking-[0.16em] text-faint uppercase">Built in</p>
          <ThemeRow
            label="Dark or Light, as chosen in Settings"
            active={effective.selected === ""}
            onSelect={() => propose({ ...effective, selected: "" })}
          />

          <p className="mt-4 mb-1 text-2xs font-medium tracking-[0.16em] text-faint uppercase">Yours</p>
          {effective.custom.length === 0 ? (
            <p className="py-2 text-xs text-faint">Nothing yet.</p>
          ) : (
            effective.custom.map((theme) => (
              <ThemeRow
                key={theme.id}
                label={theme.name || "Untitled"}
                hint={theme.base === "light" ? "on Light" : "on Dark"}
                active={effective.selected === theme.id}
                editing={editing === theme.id}
                onSelect={() => {
                  setEditing(theme.id);
                  propose({ ...effective, selected: theme.id });
                }}
              />
            ))
          )}

          <div className="mt-4 flex flex-wrap gap-2 border-t border-line-soft pt-3">
            <Button shape="pill" onClick={() => create(draft)}>
              <FiPlus aria-hidden /> {draft ? "Duplicate" : "New"}
            </Button>
            <Button shape="pill" onClick={() => setShare("import")}>
              <FiDownload aria-hidden /> Import
            </Button>
            {draft && (
              <Button shape="pill" onClick={() => setShare("export")}>
                <FiCopy aria-hidden /> Export
              </Button>
            )}
            {draft && (
              <Button
                variant="danger"
                shape="pill"
                onClick={() => {
                  const rest = effective.custom.filter((t) => t.id !== draft.id);
                  setEditing(rest[0]?.id ?? "");
                  propose({
                    selected: effective.selected === draft.id ? "" : effective.selected,
                    custom: rest,
                  });
                }}
              >
                <FiTrash2 aria-hidden /> Delete
              </Button>
            )}
          </div>

          <p className="mt-3 text-xs text-faint">
            Custom themes apply to this desktop app only — the phone renders in Compose against the
            system palette and cannot read CSS.
          </p>
          {/* Said here because this is the screen where the lockout gets made, and because the
            * hatch itself withdraws to a handle after twenty seconds — a route nobody has read
            * about is not a route. `customtheme.ts` owns the chord; this sentence and that constant
            * have to agree. */}
          <p className="mt-2 text-xs text-faint">
            If an appearance makes this window unusable, press{" "}
            <kbd className="rounded-sm border border-line px-1 font-mono text-2xs text-dim">
              Ctrl
            </kbd>
            +<kbd className="rounded-sm border border-line px-1 font-mono text-2xs text-dim">Alt</kbd>
            +<kbd className="rounded-sm border border-line px-1 font-mono text-2xs text-dim">R</kbd>{" "}
            — or Shift+Tab from the top of the page — to bring back{" "}
            <span className="text-dim">Reset appearance</span>. It is always in the bottom-right
            corner while a custom appearance is in force.
          </p>
        </Card>
      </div>

      {/* ------------------------------------------------------------------ the editor */}
      <div className="col-span-12 wide:col-span-5">
        <Card title={draft ? draft.name || "Untitled" : "Editor"}>
          {!draft ? (
            <Empty icon={<FiDroplet aria-hidden />} title="Nothing selected">
              Pick one of yours, or press New to start from the scheme this window is using.
            </Empty>
          ) : (
            <>
              <label className="mt-2 mb-3 flex items-center gap-3 text-sm">
                <span className="w-20 flex-none text-dim">Name</span>
                <input
                  className="min-w-0 flex-1 rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] focus:border-brand-1 focus:outline-none"
                  value={draft.name}
                  onChange={(e) => {
                    const name = e.currentTarget.value.slice(0, 60);
                    editDraft((t) => (t.name = name));
                  }}
                />
              </label>
              <label className="mb-4 flex items-center gap-3 text-sm">
                <span className="w-20 flex-none text-dim">Based on</span>
                <select
                  aria-label="Base scheme"
                  className="rounded-sm border border-line bg-surface-2 px-2.5 py-[7px] focus:border-brand-1 focus:outline-none"
                  value={draft.base}
                  onChange={(e) => {
                    const base = e.currentTarget.value === "light" ? "light" : "dark";
                    editDraft((t) => (t.base = base));
                  }}
                >
                  <option value="dark">Dark</option>
                  <option value="light">Light</option>
                </select>
              </label>

              <Tabs
                tabs={[
                  { id: "tokens" as const, label: "Tokens", icon: <FiSliders /> },
                  { id: "css" as const, label: "CSS", icon: <FiCode /> },
                ]}
                active={tab}
                onSelect={setTab}
              />

              {tab === "tokens" ? (
                <div className="pt-3">
                  {TOKEN_GROUPS.map((group) => (
                    <section key={group} className="mb-4">
                      <p className="mb-1 text-2xs font-medium tracking-[0.16em] text-faint uppercase">
                        {group}
                      </p>
                      {THEME_TOKENS.filter((spec) => spec.group === group).map((spec) => (
                        <TokenRow
                          key={spec.name}
                          name={spec.name}
                          label={spec.label}
                          kind={spec.kind}
                          value={draft.tokens[spec.name] ?? ""}
                          onChange={(value) =>
                            editDraft((t) => {
                              if (value) t.tokens[spec.name] = value;
                              else delete t.tokens[spec.name];
                            })
                          }
                        />
                      ))}
                    </section>
                  ))}
                </div>
              ) : (
                <div className="pt-3">
                  <textarea
                    aria-label="Theme CSS"
                    spellCheck={false}
                    className="h-[340px] w-full resize-y rounded-sm border border-line bg-surface-2 px-3 py-2 font-mono text-[12px] leading-relaxed focus:border-brand-1 focus:outline-none"
                    value={draft.css}
                    onChange={(e) => {
                      const css = e.currentTarget.value;
                      editDraft((t) => (t.css = css));
                    }}
                    placeholder={":root {\n  --brand-1: #7d67cb;\n}\n\n.world-art {\n  filter: saturate(1.4);\n}"}
                  />
                  <p className="mt-2 text-xs text-faint">
                    Rewritten by this window's own CSS parser before it is applied: every rule is
                    scoped to this appearance, so deselecting it removes all of it.
                  </p>
                  {rewritten?.error && (
                    <div className="mt-2">
                      <Banner tone="danger">{rewritten.error}</Banner>
                    </div>
                  )}
                  {rewritten && rewritten.dropped.length > 0 && (
                    <div className="mt-2">
                      <Banner tone="warn">
                        {rewritten.dropped.length} rule
                        {rewritten.dropped.length === 1 ? "" : "s"} removed:{" "}
                        {rewritten.dropped.slice(0, 4).join(", ")}
                        {rewritten.dropped.length > 4 ? " …" : ""}
                      </Banner>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </Card>
      </div>

      {/* ------------------------------------------------------------------ the report */}
      <div className="col-span-12 wide:col-span-4">
        <Card
          title="What this looks like"
          hint="The window is already rendering it — this is the part a screenshot would not tell you."
        >
          <div className="my-3 flex flex-wrap gap-2">
            <Button variant="primary" shape="pill">
              Primary
            </Button>
            <Button shape="pill">Secondary</Button>
            <Button variant="ghost" shape="pill">
              Ghost
            </Button>
            <Button variant="danger" shape="pill">
              Danger
            </Button>
          </div>
          <div className="mb-3 flex gap-2">
            <span className="h-10 flex-1 rounded-sm bg-bg" title="--bg" />
            <span className="h-10 flex-1 rounded-sm bg-surface" title="--surface" />
            <span className="h-10 flex-1 rounded-sm bg-surface-2" title="--surface-2" />
            <span className="h-10 flex-1 rounded-sm bg-rail" title="--bg-rail" />
            <span className="h-10 flex-1 rounded-sm bg-brand" title="--brand-gradient" />
          </div>

          <p className="mt-4 mb-1 text-2xs font-medium tracking-[0.16em] text-faint uppercase">
            Contrast
          </p>
          <dl className="text-xs">
            {report.map((row) => (
              <div key={row.label} className="flex items-baseline justify-between gap-3 border-b border-line-soft py-1.5 last:border-b-0">
                <dt className="text-dim">{row.label}</dt>
                <dd className={cx("font-mono tabular-nums", row.ratio >= row.floor ? "text-up" : "text-warn")}>
                  {row.ratio.toFixed(2)}:1 {row.ratio >= row.floor ? "✓" : `needs ${row.floor}`}
                </dd>
              </div>
            ))}
          </dl>
          <p className="mt-2 text-xs text-faint">
            Measured off the window as it is rendering, not off the values you typed — so it accounts
            for whatever your CSS did on top of them.
          </p>
        </Card>
      </div>

      {share === "export" && draft && (
        <Modal title="Export this appearance" onClose={() => setShare(null)}>
          <p className="mb-3 text-sm text-dim">
            Anyone you send this to will see what it removes before it is added, the same way you
            will when you import one.
          </p>
          <textarea
            readOnly
            aria-label="Theme as JSON"
            className="h-[220px] w-full resize-y rounded-sm border border-line bg-surface-2 px-3 py-2 font-mono text-[12px]"
            value={JSON.stringify(draft, null, 2)}
          />
          <div className="mt-3">
            <Button
              shape="pill"
              onClick={() => {
                navigator.clipboard?.writeText(JSON.stringify(draft, null, 2)).catch(() => {});
              }}
            >
              <FiCopy aria-hidden /> Copy
            </Button>
          </div>
        </Modal>
      )}

      {share === "import" && (
        <ImportModal
          text={importText}
          onText={setImportText}
          onClose={() => setShare(null)}
          onAdd={(theme) => {
            setEditing(theme.id);
            propose({ selected: theme.id, custom: [...effective.custom, theme] });
            setShare(null);
            setImportText("");
          }}
        />
      )}
    </PageGrid>
  );
}

/* ------------------------------------------------------------------------------------ the list */

function ThemeRow(props: {
  label: string;
  hint?: string;
  active: boolean;
  editing?: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      onClick={props.onSelect}
      aria-current={props.active ? "true" : undefined}
      className={cx(
        "flex w-full items-center justify-between gap-2 rounded-sm border px-3 py-2 text-left text-sm",
        "transition-colors duration-[var(--motion-fast)]",
        "focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus",
        props.active
          ? "border-brand-edge bg-brand-soft font-medium text-text"
          : props.editing
            ? "border-line text-text"
            : "border-transparent text-dim hover:bg-surface-hover hover:text-text",
      )}
    >
      <span className="truncate">{props.label}</span>
      {props.hint && <span className="flex-none text-xs text-faint">{props.hint}</span>}
    </button>
  );
}

/* ---------------------------------------------------------------------------------- one token */

function TokenRow(props: {
  name: string;
  label: string;
  kind: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const bad = props.value.length > 0 && !isValid(props.name, props.value);
  return (
    <div className="flex items-center gap-2 py-1">
      <span className="w-[124px] flex-none truncate text-xs text-dim" title={props.name}>
        {props.label}
      </span>
      {props.kind === "colour" && (
        <input
          type="color"
          aria-label={`${props.label} colour`}
          className="size-6 flex-none rounded-sm border border-line bg-surface-2"
          value={isColour(props.value) && /^#[0-9a-f]{6}$/i.test(props.value) ? props.value : "#000000"}
          onChange={(e) => props.onChange(e.currentTarget.value)}
        />
      )}
      <input
        aria-label={props.label}
        className={cx(
          "min-w-0 flex-1 rounded-sm border bg-surface-2 px-2 py-1 font-mono text-[11px] focus:outline-none",
          bad ? "border-danger" : "border-line focus:border-brand-1",
        )}
        value={props.value}
        placeholder="inherit"
        onChange={(e) => props.onChange(e.currentTarget.value)}
      />
      <button
        onClick={() => props.onChange("")}
        aria-label={`Reset ${props.label}`}
        title="Back to the base scheme's value"
        className="flex-none rounded-sm px-1.5 py-1 text-xs text-faint hover:text-text focus-visible:outline-2 focus-visible:outline-focus"
      >
        <FiRotateCcw aria-hidden />
      </button>
    </div>
  );
}

/* --------------------------------------------------------------------------------- the import */

function ImportModal(props: {
  text: string;
  onText: (value: string) => void;
  onClose: () => void;
  onAdd: (theme: CustomTheme) => void;
}) {
  const parsed = useMemo(() => parseTheme(props.text), [props.text]);
  const preview = useMemo(
    () => (parsed.theme ? sanitise(parsed.theme.css, parsed.theme.id, parsed.theme.base) : null),
    [parsed.theme],
  );

  return (
    <Modal
      title="Import an appearance"
      onClose={props.onClose}
      footer={
        <>
          <Button variant="ghost" shape="pill" onClick={props.onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            shape="pill"
            disabled={!parsed.theme}
            onClick={() => parsed.theme && props.onAdd({ ...parsed.theme, id: mintId() })}
          >
            Add
          </Button>
        </>
      }
    >
      <p className="mb-3 text-sm text-dim">
        Paste an exported appearance. What it would install is shown below before anything is added —
        a theme file from somebody else is the whole reason the rewriter exists.
      </p>
      <textarea
        aria-label="Appearance to import"
        spellCheck={false}
        className="h-[180px] w-full resize-y rounded-sm border border-line bg-surface-2 px-3 py-2 font-mono text-[12px] focus:border-brand-1 focus:outline-none"
        value={props.text}
        onChange={(e) => props.onText(e.currentTarget.value)}
      />
      {parsed.error && (
        <div className="mt-3">
          <Banner tone="danger">{parsed.error}</Banner>
        </div>
      )}
      {parsed.theme && (
        <dl className="mt-3 text-xs">
          <div className="flex justify-between gap-3 border-b border-line-soft py-1.5">
            <dt className="text-dim">Name</dt>
            <dd>{parsed.theme.name || "Untitled"}</dd>
          </div>
          <div className="flex justify-between gap-3 border-b border-line-soft py-1.5">
            <dt className="text-dim">Sets</dt>
            <dd>{Object.keys(parsed.theme.tokens).length} tokens</dd>
          </div>
          <div className="flex justify-between gap-3 py-1.5">
            <dt className="text-dim">Removed by this window</dt>
            <dd className={preview && preview.dropped.length > 0 ? "text-warn" : "text-faint"}>
              {preview && preview.dropped.length > 0 ? preview.dropped.join(", ") : "nothing"}
            </dd>
          </div>
        </dl>
      )}
    </Modal>
  );
}

/** Read an exported appearance, keeping only the fields this app defines. */
function parseTheme(text: string): { theme?: CustomTheme; error: string } {
  if (!text.trim()) return { error: "" };
  try {
    const raw = JSON.parse(text) as Record<string, unknown>;
    const tokens: Record<string, string> = {};
    const given = (raw.tokens ?? {}) as Record<string, unknown>;
    for (const spec of THEME_TOKENS) {
      const value = given[spec.name];
      if (typeof value === "string" && isValid(spec.name, value)) tokens[spec.name] = value;
    }
    return {
      error: "",
      theme: {
        id: mintId(),
        name: typeof raw.name === "string" ? raw.name.slice(0, 60) : "Imported appearance",
        base: raw.base === "light" ? "light" : "dark",
        tokens,
        css: typeof raw.css === "string" ? raw.css.slice(0, 200_000) : "",
        updated_at: Math.floor(Date.now() / 1000),
      },
    };
  } catch {
    return { error: "That is not an exported appearance — it is not valid JSON." };
  }
}

/* -------------------------------------------------------------------------------- the contrast */

interface ContrastRow {
  label: string;
  ratio: number;
  floor: number;
}

/**
 * Measured off the window as it is rendering, never off the values in the editor.
 *
 * A theme can change a colour through CSS mode without ever touching the token that names it, so a
 * report computed from `tokens` would confidently pass a theme whose text is invisible.
 */
function contrastReport(): ContrastRow[] {
  const styles = getComputedStyle(document.documentElement);
  const read = (name: string) => styles.getPropertyValue(name).trim();
  const pairs: [string, string, string, number][] = [
    ["Text on the page", "--text", "--bg", 4.5],
    ["Secondary text", "--text-dim", "--bg", 4.5],
    ["Faint text", "--text-faint", "--bg", 3],
    ["Text on the accent", "--on-play", "--brand-1", 4.5],
    ["Focus ring", "--focus-ring", "--bg", 3],
  ];
  return pairs.map(([label, front, back, floor]) => ({
    label,
    floor,
    ratio: contrast(read(front), read(back)),
  }));
}

function contrast(front: string, back: string): number {
  const a = luminance(front);
  const b = luminance(back);
  if (a === null || b === null) return 0;
  const [light, dark] = a > b ? [a, b] : [b, a];
  return (light + 0.05) / (dark + 0.05);
}

/** WCAG 2.x relative luminance, for the notations a computed style can hand back. */
function luminance(colour: string): number | null {
  const rgb = toRgb(colour);
  if (!rgb) return null;
  const channel = (v: number) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2]);
}

function toRgb(colour: string): [number, number, number] | null {
  const value = colour.trim();
  const hex = /^#([0-9a-f]{3,8})$/i.exec(value);
  if (hex) {
    const digits = hex[1];
    const expand = digits.length <= 4 ? digits.slice(0, 3).split("").map((d) => d + d) : [digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 6)];
    return expand.map((pair) => parseInt(pair, 16)) as [number, number, number];
  }
  const fn = /^rgba?\(([^)]+)\)$/i.exec(value);
  if (fn) {
    const parts = fn[1].split(/[,/\s]+/).filter(Boolean).map(Number);
    if (parts.length >= 3 && parts.slice(0, 3).every((n) => Number.isFinite(n))) {
      return [parts[0], parts[1], parts[2]];
    }
  }
  return null;
}
