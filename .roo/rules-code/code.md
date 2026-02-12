# Code Mode — Implementation Discipline

## Institutional memory

- Follow institutional-memory authority and hierarchy from @.roo/rules/rules.md.
- Treat local code comments as binding local institutional memory when they
  capture design intent, invariants, lifecycle ownership, authority boundaries,
  and thread/event constraints.
- Preserve and update intent-bearing comments during implementation.
- Keep comment intent and behavior synchronized; do not leave stale comments.
- If implementation requires invalidating existing comment-level memory, make
  the boundary change explicit and escalate to Architect mode when needed.

## Safety discipline

- Do not introduce hidden scheduling.
- Do not blur execution boundaries.
- If design conflict appears, escalate to Architect mode.

## Observability discipline

- Preserve semantic distinction between:
  - Diagnostic logs
  - Behavioral logs
  - Audit signals
  - Domain events
- Keep observability language aligned with institutional semantics.
- Avoid adding observability overhead on hot execution paths unless justified.
- Keep systems operationally diagnosable after changes.

## Maintainability

- Favor clarity over cleverness.
- Keep components cohesive.
- Avoid silent coupling.

## Build lock protocol

- Implement only what is covered by the locked architecture and delivery plan.
- Use `./gradlew build` as the default clean build signal unless explicitly
  overridden.
- Compile/build until that signal is clean.
- Report implementation notes and any deviations explicitly.
- Recommend switching to Orchestrator for validation once build lock is reached.
