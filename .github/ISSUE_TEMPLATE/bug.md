---
name: Bug report
about: Something is broken or a test is red — open this BEFORE fixing-forward
title: "bug: <one-line symptom>"
labels: bug
assignees: ''
---

## Symptom
<!-- One paragraph: what goes wrong, where, when. -->

## Affected module(s)
<!-- e.g. core/crypto, simulation/engine, transport-api -->

## Reproduce
```bash
# commands / test names that trigger it
```

## Expected vs actual
- **Expected:**
- **Actual:**

## Evidence
<!-- Failing test name, assertion message, stack trace excerpt, fixture id, StateRoot mismatch, etc. -->

## Root cause (if known)
<!-- Leave blank if unknown. -->

## Acceptance (definition of fixed)
- [ ] Reproduction now passes / red test is green
- [ ] Regression test added so this cannot return
- [ ] `./gradlew check` green
- [ ] `Tested.md` + README updated
- [ ] Closes via `fixes #N`
