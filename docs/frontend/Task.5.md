# Frontend Task 5 — Telemetry Consent: The First-Run Modal and the Privacy Screen

<!-- AI-AGENT-INSTRUCTION: This task owns the only place a person is ASKED. Three rules, and a change
     that breaks any of them must be refused: (1) neither button is pre-selected, styled as
     preferred, or larger than the other; (2) declining is final — the modal never appears again;
     (3) the app never sends telemetry itself, it tells the worker. The privacy model is
     ../plans/Plan.6.md. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** frontend · **Owns:** — (L-78 RETIRED 2026-07-26) · **Last audit:** 2026-07-28
**Depends on:** [worker 5](../peer/Task.5.md), [frontend 2](Task.2.md)
**Consumed by:** every player; [telemetry 3](../telemetry/Task.3.md)

---

## Goal

Ask once, in plain language, at a moment nobody is blocked; record the answer in the worker; and give
the person a Privacy screen where they can see exactly what is collected, change their mind at any
time, and watch the change take effect.

## Status detail

Landed and green (`cargo test` in `app`: **61 tests**, 5 of them telemetry).

`src/telemetry.rs` reads and writes the decision through the worker's verb and models the three
states the UI must tell apart — granted, denied, and *the worker cannot* (an older worker answering
`NODERA-ERR unsupported`). `ui/src/Consent.tsx` carries the first-run modal and the Privacy card;
`Settings.tsx` gained a Privacy section; `App.tsx` renders the modal above everything when this
installation has never answered. Three Tauri commands (`get_telemetry_status`,
`set_telemetry_consent`, `get_collected_schema`) join the handler list.

Remaining: a **component test** proving the two buttons stay structurally equal (there is no React
test runner in this crate yet — today the property is held by review and by the markup), and the
live pass of the modal in a packaged build.

## Dependencies

- [worker 5](../peer/Task.5.md) — the verb the decision is written to.
- [frontend 2](Task.2.md) — the dashboard and settings surfaces this extends.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | First-run consent modal — shown once, never on an upgrade path that already has an answer | ✅ |
| 2 | Two equally weighted choices: **Share telemetry** / **Don't share** | ✅ |
| 3 | "What is collected" disclosure, from the ingest service where reachable, labelled when not | ✅ |
| 4 | Settings → **Privacy**: the toggle, the current state badge, the last-send status | ✅ |
| 5 | Consent pushed to the worker; badge reflects what the worker *confirmed*, never the local intent | ✅ |
| 6 | Revoke flow: one click, with what it does stated (stops collection, clears queue, drops the id) | ✅ |
| 7 | Local persistence of "the question has been answered", independent of the answer | ✅ |
| 8 | Rust tests for the state machine (5) ✅; component tests for the modal | 🚧 |

## Design

**One question, once, in plain words.** "Help improve NoderaMC by sharing anonymous telemetry?"
followed by what that means in two sentences and a link to the details. Not a wall of policy, not a
checkbox in an installer, not a banner that reappears.

**Neither answer is the default.** No pre-selection, no primary/secondary styling, no "Not now" that
is really "ask me again next week". A dialog engineered to make yes easier produces consent that is
worth nothing, and the project's pitch is precisely that players are not a central operator's
product.

**Declining is final.** Answering no records that the question is answered. The modal never returns.
Someone who changes their mind finds the toggle where they would look for it — under Privacy.

**"What is collected" is read from the service, not written in the app.** The disclosure asks the
configured ingest endpoint for `--print-schema` and renders it; where the endpoint is unreachable, it
falls back to the bundled copy of the registry, clearly labelled as such. A privacy notice
maintained separately from the enforcement drifts from it, and the drift is always in the
uncomfortable direction.

**The badge shows what the worker confirmed.** The same discipline the config lane already
established (`docs/frontend/PROGRESS.md`, 2026-07-25): a setting the node has not acknowledged is not
badged as live. A consent toggle that says "on" while the worker never received it would be the
worst possible version of this bug.

**The app never sends telemetry.** It has events worth reporting — which screens are used, whether
the tray is used — and it hands them to the worker like everything else. One emitter, one consent
check, one spool.

**Revocation says what it does.** "Stops collecting, clears anything queued, and forgets this
installation's identifier." A revoke flow that only stops uploads, without saying so, is a lie of
omission.

## Files

- `app/src/telemetry.rs` — consent state, worker push, schema fetch
- `app/src/settings.rs` — the `privacy` section and its enforcement badge
- `app/ui/src/Consent.tsx` — the modal
- `app/ui/src/Settings.tsx` — the Privacy card
- `app/ui/src/ipc.ts` — the commands and events

## Testing

- `consent_is_unanswered_until_the_user_answers` — a fresh profile reports "unanswered", and the
  worker is told nothing.
- `declining_records_an_answer_and_never_reopens_the_modal` — the shown-once flag is set by *both*
  answers.
- `the_badge_never_claims_live_without_a_worker_acknowledgement` — a push that fails leaves the
  toggle reflecting the worker's last known state, with the error surfaced as soft status.
- `revoking_clears_local_state_and_pushes_denied` — the app's copy is cleared even if the push fails,
  so a worker that comes back later is told denied on the next reconnect.
- `the_disclosure_falls_back_to_the_bundled_registry_when_the_service_is_unreachable` — labelled as
  a fallback, not presented as live.
- Component test: neither button carries a primary style or larger hit area.

## Acceptance criteria

1. ✅ A fresh install collects nothing until the modal is answered yes (the worker reports
   `unanswered`, and `scripts/e2e-telemetry.sh` T1 proves that state collects nothing).
2. ✅ The modal appears exactly once — the marker is written by **both** answers, and by a failed
   push too, so a busy node cannot cause a re-ask.
3. 🚧 Both choices are structurally equal in the markup; no component test enforces it yet.
4. ✅ The Privacy screen shows what is collected, from the service where possible and labelled as a
   bundled fallback when not.
5. ✅ Revoking stops collection at the worker, not just transmission (T5).
6. ✅ No telemetry ever leaves the app process directly — the app has no sender.

## Limitations

**L-78 is RETIRED** (2026-07-26). Reading the code while retiring it turned up something worse than
the row claimed: there was no fallback at all — an unreachable collector returned an error, blanking
the disclosure mid-decision. The registry now lives in the `nodera-telemetry` **library** (one source
for the service's live answer and the app's bundled copy, because a privacy notice maintained twice
drifts and the stale copy is the one people read), and every copy carries `registry_version` +
`disclosure_source`, so a fallback is labelled and a stale one is visibly stale. Evidence in
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
