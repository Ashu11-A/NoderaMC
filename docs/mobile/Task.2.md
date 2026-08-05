# Mobile Task 2 — The interface: Material You, and what a phone may be asked

<!-- AI-AGENT-INSTRUCTION: The rules here are about honesty on a small screen. If a screen starts
     explaining itself in paragraphs, it has become documentation and belongs here instead. -->

**Status:** ✅ COMPLETED
**Category:** mobile · **Owns:** `app/android/kotlin/ui/`
**Last audit:** 2026-08-01
**Depends on:** [mobile 1](Task.1.md), [app 6](../app/Task.6.md)

---

## Dynamic colour, native

`MainActivity` is a native Jetpack Compose `ComponentActivity`. Android 12+ uses
`dynamicLightColorScheme` / `dynamicDarkColorScheme`, so Material You comes from the system's real
wallpaper palette. Android 8–11 use the declared Nodera fallback scheme. React's former WebView HCT
emulation is no longer Android presentation code.

## Two layouts, two different questions

| Boundary | Question | Consequence |
|---|---|---|
| `< 600 dp` | Is this a phone-width window? | Material 3 bottom navigation and one-column content |
| `≥ 600 dp` | Is there room for persistent navigation? | Navigation rail and adaptive grids |
| content-specific width | Is there room after rail/settings panes? | World grids and peer list-detail split only when their own pane is wide enough |

Android remains a node companion at every width; a tablet does not grow a fake Java-Minecraft Play
button. Compact desktop React remains desktop functionality and is a separate shell.

## Navigation is the system's

Compose state owns Home / Worlds / Activity / Settings. `BackHandler` walks Licences → About →
Settings root → Home; only root lets Android finish the activity. Phone uses `NavigationBar`, tablet
uses `NavigationRail`.

## Settings are a list of places, not a wall of tabs

Appearance, Network, Tracker stores, Storage, Battery, Peers, Privacy, Diagnostics, About and
Licences are native destinations. `OutlinedTextField`, `Switch`, `Slider`, `FilterChip`, cards,
dialogs and list-detail layouts call existing core verbs. Unknown battery/network reads render
checking or unavailable, never a guessed success. Numeric settings round-trip exact seconds and the
full connection-limit domain, including zero-as-unlimited.

## The two questions a default cannot answer

Native first run asks them, once, in a scrollable layout safe for landscape and large fonts:

1. **Where world data goes.** The peer stores other people's worlds; an app that picks silently is
   an app that fills your phone without asking.
2. **Whether anything may be reported.** Consent that was not given is not consent.

A third screen appears **only if** Android is currently restricting the app: battery optimisation,
with the vendor's page on <https://dontkillmyapp.com> and a route to the system setting. Optional,
and skippable, but not invisible — a node the OS may stop is a node other people cannot rely on.

## Tracker-store trust boundary

An incoming deep link records one offered URL. Preview binds the exact reviewed URL immutably;
another link or an edit invalidates that preview before Add can run. This is the native equivalent of
desktop's preview-before-trust rule, not a shortcut around it.
