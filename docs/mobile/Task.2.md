# Mobile Task 2 — The interface: Material You, and what a phone may be asked

<!-- AI-AGENT-INSTRUCTION: The rules here are about honesty on a small screen. If a screen starts
     explaining itself in paragraphs, it has become documentation and belongs here instead. -->

**Status:** ✅ COMPLETED
**Category:** mobile · **Owns:** `rust/nodera-app/ui/src/m3/`, `rust/nodera-app/ui/src/mobile/`
**Last audit:** 2026-07-26
**Depends on:** [mobile 1](Task.1.md), [app 6](../app/Task.6.md)

---

## Dynamic colour, generated

`m3/theme.ts` derives every `--md-sys-color-*` role from one source colour using
`@material/material-color-utilities` — Google's own reference implementation of the HCT tonal
palettes Material You is defined by. Surfaces, containers and outlines are tones of the neutral
palette seeded from the same colour, which is why an elevated card is tinted rather than grey.

Material You normally seeds from the wallpaper. A WebView cannot read the wallpaper, so the app
**asks** instead of pretending: five source colours, persisted. Saying "pick your colour" is honest;
claiming to have read the wallpaper would not be.

## Two layouts, two different questions

| Hook | Question | Consequence |
|---|---|---|
| `useIsCompact()` | Is the **window** under 600 dp? | Density: bottom bar and cards instead of a rail and tables |
| `useIsMobileBuild()` | Is this the **Android binary**? | Which features exist at all |

Keeping them apart is what stops a narrow desktop window from losing the LAN lane and the mod
installer, which it genuinely has.

## Navigation is the system's

The interface pushes its own levels into the WebView's history with `pushState`, and
`MainActivity` re-enables `handleBackNavigation` (Tauri disables it), so the Android back gesture
walks those levels. At the root there is nothing left to pop and the app closes — which is what a
phone user expects. The navigation bar hides inside a sub-screen: one destination at a time.

## Settings are a list of places, not a wall of tabs

Six rows — Appearance, Storage, Network, Battery, Privacy, About — each opening its own screen, each
with its **current value** underneath rather than a description of itself.

## The two questions a default cannot answer

First run asks them, once, and does not close until both are answered:

1. **Where world data goes.** The peer stores other people's worlds; an app that picks silently is
   an app that fills your phone without asking.
2. **Whether anything may be reported.** Consent that was not given is not consent.

A third screen appears **only if** Android is currently restricting the app: battery optimisation,
with the vendor's page on <https://dontkillmyapp.com> and a route to the system setting. Optional,
and skippable, but not invisible — a node the OS may stop is a node other people cannot rely on.

## Motion

`m3/motion.tsx`: a fade-through between peer destinations, a push for entering a sub-screen, a
staggered list entrance, and a dialog that grows in — M3's own easing curves and durations. All of
it collapses to zero under `prefers-reduced-motion`, because the movement is a hint about hierarchy
and for someone who asked their device to stop moving things it is only nausea.
