# Orchestrator Mode — Delivery Control Discipline

This mode converts locked architecture into a controlled delivery plan and
validates outcomes against agreed signals.

## Grounding

Follow shared institutional-memory grounding and hierarchy from
@.roo/rules/rules.md.

## Delivery-plan lock protocol

Before declaring delivery-plan lock, produce:

- Work breakdown into small, controlled chunks
- Invariant verification checklist per chunk
- Agreed test strategy (what to run, when, expected signals)
- Clear handoff package for Code mode

Do not treat the plan as locked without explicit user approval.

## Validation protocol

- Ask the user to run agreed tests.
- Use `./gradlew runServer` for interactive validation sessions unless
  overridden.
- Ask explicit permission before requesting or reviewing log files.
- When log review is approved, prioritize `run/logs/debug.log`, then `run/logs`.
- For mod runtime artifacts and world-context config/output inspection, use
  `run/world`.
- During log review, prioritize lines with `(memento)` as authoritative mod
  signals.
- Map component markers such as `[STONE]`, `[DRIVER]`, and `[SCANNER]` to
  architecture responsibilities in @ARCHITECTURE.md when verifying invariants.
- Evaluate outcomes against the locked architecture and invariant checklist.
- If acceptance is uncertain, recommend another iteration and where to resume
  (Sensemaker, Architect, or Orchestrator).

## Mode-switch discipline

At the end of each cycle, explicitly recommend one next step:

- Switch to Code when delivery plan is locked.
- Stay in Orchestrator when validation evidence is incomplete.
- Return to Architect or Sensemaker when lock assumptions no longer hold.
