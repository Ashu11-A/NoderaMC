# COMPATIBILITY.md — the mod-compatibility contract (Task 11)

This file is **normative**: it fixes what other mods (and modpacks) can rely on when running
alongside NoderaMC, and what they must not do. Referenced from `docs/Plan.md`; owned by Task 11.
The enforcement mechanism is the interference guard (`MutationGuard` — the single
`setBlockState` choke point on delegated chunks) plus the delegability policy.

## 1. Event ordering

Nodera captures/cancels player actions at `EventPriority.LOW` with `receiveCanceled = false`.
**Protection mods at any earlier priority always win**: if a claims/protection mod cancels a
block event before Nodera sees it, Nodera never captures it and the validated lane never learns
it existed.

## 2. Fake players

Fake players (machine-operated block breakers/placers) never become Nodera actors and never join
committees. Their effects on delegated regions are foreign writes: converted into certified
external deltas (`ServerAuthorityCertificate` reason `EXTERNAL_MUTATION`) in CONVERT mode. A
region with recurring fake-player activity is demoted (`FAKE_PLAYER_ACTIVE`) and runs pure
vanilla — the machines keep working; the region simply is not validated.

## 3. Unknown blocks

Any block outside the Nodera palette makes its region **and its neighbor ring**
(`DELEGABLE_NEIGHBOR_RING`) non-delegable (`UNSUPPORTED_PALETTE` / `NEIGHBOR_UNSUPPORTED`).
Other mods' machines simply keep vanilla semantics — Nodera never simulates a block it does not
understand, and never delegates a region whose boundary a foreign mechanic could bleed across.

## 4. Redstone

Redstone is excluded from validated lanes by palette until Task 13. Contraptions run pure
vanilla; a redstone component next to a delegated region demotes that region via the neighbor
ring (see §3).

## 5. Chunk tickets

Nodera holds delegated chunks loaded with its **own** ticket type (`NODERA_DELEGATED`), added on
lease issue and released on revoke. Foreign tickets (ender pearls, portals, other mods' chunk
loaders) are **never cancelled** — Nodera only adds/removes its own type. Loader-held chunks with
no nearby session player are vanilla lane by policy: the guard does not touch them.

## 6. What other mods must NOT do

1. **Mutate delegated chunks from async threads.** Async world writes are undefined behaviour
   even in vanilla; the guard converts main-thread writes only. Async writes into delegated
   chunks are logged as errors and are outside every compatibility guarantee.
2. **Depend on same-tick visibility of block changes in delegated regions.** Committed effects
   appear 1–2 ticks after their cause (batch + quorum latency — normative, see
   `docs/LIMITATIONS.md` L-16). A mod that reads a delegated block the same tick it caused a
   change may see the pre-commit state.

## 7. What other mods CAN rely on

- Main-thread writes into delegated regions are never silently dropped in CONVERT mode (the
  default): they land, and Nodera folds them into its version chain as certified external
  deltas. STRICT mode (debug/CI: `interference.mode=STRICT`) blocks them loudly.
- Vanilla-lane regions (anything non-delegated) are untouched: no guard, no capture, no
  behavioural change.
- Region demotion is graceful: an in-flight validated batch resolves before the region returns
  to vanilla execution, and re-delegation waits out `DELEGABILITY_COOLDOWN_TICKS`.
