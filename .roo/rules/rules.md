## Institutional Memory Policy

The project authority is anchored in:

- @README.md
- @ARCHITECTURE.md
- @DEVELOPMENT.md
- @RENEWAL_MODEL.md

These files are institutional memory and must be treated as the canonical source
of truth for semantics, architecture, and development constraints.

Local code comments are also institutional memory when they encode design intent,
invariants, lifecycle assumptions, authority boundaries, or thread/event
constraints. They must be kept explicit and synchronized with behavior.

Comment-memory discipline:

- Do not remove intent-bearing comments without replacing the intent elsewhere.
- Do not leave stale comments after behavior changes.
- Prefer comments that explain why a boundary or invariant exists, not just what
  code does.
- If comments and implementation diverge, update one immediately and make the
  divergence explicit in the change.

Mode expectations:

- Sensemaker mode is used to interview intent, challenge assumptions, and
  refine institutional memory before lock.
- Architect mode is used to apply controlled, explicit updates to institutional
  memory.
- Code mode is used to implement changes while preserving and maintaining local
  comment-level institutional memory.

Hierarchy rule:

- If architectural invariant interpretation conflicts across documents,
  ARCHITECTURE.md prevails.

## Stage-Gated Workflow (Lock-In Process)

Default workflow path:

1. Sensemaker
2. Architect (with optional return to Sensemaker)
3. Orchestrator
4. Code
5. Orchestrator

### Gate 1 — Idea lock (Sensemaker)

- Required outputs:
  - Intent summary
  - Candidate invariants
  - Open questions and risks
  - Recommended next mode (Architect or Orchestrator)
- Lock trigger must be explicit user approval.

### Gate 2 — Architecture lock (Architect)

- Required outputs:
  - Component boundaries and ownership
  - Lifecycle/state model
  - Authority/thread/event boundaries
  - Invariant fit against ARCHITECTURE.md
  - Re-open conditions and explicit recommendation (stay Architect or return to Sensemaker)
- Lock trigger must be explicit user approval.

### Gate 3 — Delivery plan lock (Orchestrator)

- Required outputs:
  - Work breakdown into small, controlled chunks
  - Invariant verification checklist per chunk
  - Agreed test strategy (what to run, when, expected signals)
  - Clear handoff package for Code mode
- Lock trigger must be explicit user approval.

### Gate 4 — Build lock (Code)

- Required outputs:
  - Implementation matching locked architecture and plan
  - Clean build/compile result
  - Implementation notes and deviations (if any)
  - Recommendation to switch back to Orchestrator for validation

### Gate 5 — Validation & acceptance (Orchestrator)

- Orchestrator asks user to run agreed tests.
- Orchestrator asks explicit permission before requesting/reviewing logs.
- Orchestrator and user decide together:
  - Accept as complete, or
  - Start another iteration at Sensemaker/Architect/Orchestrator as appropriate.

## Mode-switch recommendation policy

- At each lock gate, the active mode must explicitly recommend the next mode.
- If a gate is not satisfied, explicitly recommend the mode needed to resolve it.
- Do not proceed silently across gates.

## Project Runbook Signals (Operational Defaults)

Use these defaults unless explicitly overridden by user instruction:

- Build/compile command: `./gradlew build`
- Local test server command: `./gradlew runServer`
- Primary runtime log file: `run/logs/debug.log`
- Runtime logs directory: `run/logs`
- Mod runtime world/config/output working directory: `run/world`

Operational expectations:

- Treat a successful `./gradlew build` as the default clean build signal.
- Use `./gradlew runServer` for interactive validation sessions.
- When log review is approved, prioritize `run/logs/debug.log` first.
- When inspecting mod runtime artifacts, check under `run/world`.

## Runtime Log Semantics (Memento)

- Use `run/logs/debug.log` as the primary runtime verification source.
- Treat lines containing `(memento)` as authoritative signals from this mod.
- Treat bracketed component markers (for example `[STONE]`, `[DRIVER]`,
  `[SCANNER]`) as component-level provenance and map them to architectural
  responsibilities defined in @ARCHITECTURE.md.
- During analysis, distinguish mod signals from non-mod framework noise.
