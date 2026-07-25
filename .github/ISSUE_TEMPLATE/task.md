---
name: Task
about: A planned unit of work (one docs/<category>/Task.<n>.md, or a sub-task of one)
title: "Task N — <short title>"
labels: task
assignees: ''
---

## Owning spec
<!-- Link the task file, e.g. docs/minecraft/Task.2.md. For sub-tasks, name the parent. -->
`docs/<category>/Task.<n>.md` — index: [docs/ROADMAP.md](../docs/ROADMAP.md)

## Phase
<!-- phase-0 … phase-8, plus mvp-gate if it blocks the first playable milestone. -->

## Depends on
<!-- Issue numbers this blocks on, or "—". -->

## Goal
<!-- One paragraph, copied/condensed from the task file's "## Goal". -->

## Deliverables
<!-- The concrete files/modules to produce. -->
-

## Acceptance criteria (from the task file)
- [ ] criterion 1
- [ ] criterion 2
- [ ] `./gradlew check` green
- [ ] `README.md` progress bar + module table updated
- [ ] `docs/<category>/TESTING.md` updated
- [ ] `LIMITATIONS.md` updated if a §B row is staged/retired
- [ ] Closes via `Closes #N`

## Debugger scenarios (if the task adds a gameplay lane)
<!-- Which scenarios this task contributes to issue #17 (the debugger harness). -->
