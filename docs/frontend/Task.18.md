# Frontend Task 18 — The website

<!-- AI-AGENT-INSTRUCTION: Four rules this task exists to hold, and a change that breaks any of them
     must be refused. (1) THE SITE IS STATIC. It has no backend, and nothing on the network may ever
     depend on it being up — noderamc.org is a signpost and a convenience, never authority.
     (2) `/add-store` KEEPS ITS BEHAVIOUR EXACTLY: a non-https index is refused before it is offered,
     the address is shown as received and never repaired, and the `nodera://` scheme is invoked only
     from a real click — never on load, because a page that redirects on load is a drive-by intent.
     (3) REACT, NOT VUE. The point of rebuilding is that the site and the app share a tree; a
     VitePress site shares nothing with a React launcher. (4) The documentation on the site is
     GENERATED FROM docs/, never a second copy — a hand-maintained duplicate drifts, and the stale
     copy is always the one people read. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** frontend · **Owns:** — · **Last audit:** 2026-08-05
**Depends on:** [frontend 9](Task.9.md)
**Consumed by:** [frontend 19](Task.19.md), [frontend 20](Task.20.md)

---

## Goal

A real website at <https://noderamc.org>: a landing page that says what NoderaMC is, a download
route, and a documentation section with a sidebar, search, and pages that read like documentation
rather than like a README pasted into a browser. Modelled on
[`mihonapp/website`](https://github.com/mihonapp/website) — its structure, its navigation shape and
its documentation format — and built in **React**, not Vue.

It subsumes today's `web/index.html`, and it must not break the one thing on that domain that
something else already depends on: the `/add-store` hop.

## Status detail

**Opened 2026-08-05, and the implementation lands in this same pull request.** Nothing below is
claimed as built, published, or measured.

What exists today, and is the whole of the current site:

| File | What it is |
|---|---|
| [`web/index.html`](../../web/index.html) | 52 lines. A deliberate signpost: one card, two buttons, no documentation. Its own comment says duplicating the docs here "would give it a second copy to go stale" |
| [`web/add-store.html`](../../web/add-store.html) | 202 lines. The `https` hop that makes the tracker-store deep link linkable from a page whose sanitiser strips non-`http(s)` schemes ([Task 9](Task.9.md) § The https hop) |
| [`web/noderamc.caddy`](../../web/noderamc.caddy) | The site block: `root`, `encode`, `try_files {path} {path}.html`, a `default-src 'none'` CSP, HSTS, and a log |

Live since 2026-07-30 on `noderamc.org` and `www.noderamc.org`, with Let's Encrypt certificates
issued through the host's existing `acme_dns cloudflare`. Published by
[`scripts/deploy-site.sh`](../../scripts/deploy-site.sh) — which [task 20](Task.20.md) replaces.

## Dependencies

- [Task 9](Task.9.md) — owns `/add-store` and the rule it holds. This task inherits that page's
  behaviour as a constraint, not as something to redesign.
- [Task 20](Task.20.md) is the other half of shipping and is **not** a prerequisite: the site can be
  built and published by the existing script while the image lane is being built.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | A React site, statically exported — no server, no runtime data fetching | 🚧 |
| 2 | A landing page: what NoderaMC is, in the words the root README already uses | 🚧 |
| 3 | A download route pointing at the release artefacts | 🚧 |
| 4 | A documentation section with a sidebar, per-page navigation and search | 🚧 |
| 5 | The docs section sourced from `docs/`, not re-typed | 🚧 |
| 6 | `/add-store` preserved byte-for-byte in behaviour, with its own tests | 🚧 |
| 7 | The Caddy block updated for whatever routing shape the generator emits | 🚧 |
| 8 | The whole site self-contained: no external CSS, fonts, analytics or requests | 🚧 |

## Design

**Mihon's structure, because it is the right shape for this audience.** A landing page that answers
"what is this", a prominent download, and then a documentation tree — getting started, guides, FAQ,
troubleshooting — with one sidebar and a search. It is the shape a person arriving from a link
expects, and it is the shape that survives having more pages added to it.

**React, and this is a constraint rather than a preference.** Mihon's site is VitePress, which is
Vue. Copying the stack would mean this repository maintained a Vue toolchain, a React toolchain, and
a Compose UI for one product's three surfaces, sharing nothing between the two web ones.
[Task 19](Task.19.md) exists to make `web/` and `app/ui/` one tree; a Vue site makes that task
impossible before it starts. The generator is therefore chosen from the React static-site family —
Docusaurus, Astro with React islands, Next.js in export mode, Rspress — and the deciding criterion is
**which one emits a directory of static files that Caddy can serve with no Node runtime and no
external request**, because that is what the CSP and [task 20](Task.20.md) both require.

**The documentation is generated, never copied.** `docs/` is the binding tree and this project's own
maintenance discipline says a notice maintained in two places drifts, with the stale copy being the
one people read. The site renders from those files; it does not paraphrase them. Where a page needs
site-specific framing, that framing lives in the site and the substance is still transcluded.

**`/add-store` is a constraint, not a page to redesign.** Its three rules are load-bearing and are
already written into the file's own header: nothing fires on load, the URL is shown exactly as
received, and not-installed is the common case and must leave the visitor with the address and a
route to the app rather than a dead button. Whatever the site is built with, that page's behaviour is
unchanged, its URL stays `/add-store`, and the extensionless mapping in the Caddy block stays — the
address people paste into a page must never grow a file extension.

**Nothing on the network may depend on this domain.** The site describes the project and hands out a
link; the tracker and relay that happen to run on the same host are one entry in a service list a
user can delete. A website that becomes load-bearing is a central point of failure in a project whose
entire argument is that it has none.

**Static, and self-contained.** The current CSP is `default-src 'none'` with inline style and script
only. A site that needs a font from a CDN, an analytics beacon, or a client-side search index fetched
at runtime is a site that has quietly started telling somebody else who reads it.

## Files

| Path | Role |
|---|---|
| `web/` | the site source, replacing the two hand-written pages |
| `web/add-store.html` | preserved behaviour; its new home in the generated tree is part of deliverable 6 |
| `web/noderamc.caddy` | routing and headers for the emitted tree |
| `scripts/deploy-site.sh` | still the publisher until [task 20](Task.20.md) lands |
| `docs/` | the source of the documentation section — read, never duplicated |

## Testing

The tests this task will be **closed by**. None exists today; `web/` has no gate at all, which is
recorded as such in [`TESTING.md`](TESTING.md) Part C:

- **The `/add-store` contract**, as executable assertions rather than as a comment: a plaintext index
  is refused before any request; the address renders exactly as received including a malformed
  escape; the scheme is invoked from a click handler and from nothing else; with no app installed the
  page still yields the address and a copy control.
- **A production build emits static files only** — no server entry point, and no request to a host
  that is not this one, asserted over the emitted output rather than over the source.
- **Every internal link in the emitted site resolves**, which is the site-side analogue of
  `scripts/check-docs.sh`.
- **The documentation section matches `docs/`** — a page whose source file has been deleted or
  renamed fails the build rather than 404-ing for a reader.
- The site builds in CI on every change to `web/`, which is what stops it from only ever building on
  the machine that last touched it.

## Acceptance criteria

1. ⬜ `noderamc.org` serves a landing page, a download route, and a documentation section.
2. ⬜ The documentation section is generated from `docs/` and no page is a second copy.
3. ⬜ `/add-store?url=…` behaves exactly as it does today, proven by tests rather than by reading it.
4. ⬜ The emitted site makes no external request and needs no runtime.
5. ⬜ Nothing in the site is written in Vue.
6. ⬜ CI builds the site.

## Limitations

Owns none yet. Two candidates are being watched rather than pre-registered: a client-side search that
cannot be built without a runtime fetch (which would collide with the CSP), and the possibility that
transcluding `docs/` verbatim produces pages that read as internal engineering notes to an outside
visitor. Either becomes a row here with an exit test if it turns out to be real.
