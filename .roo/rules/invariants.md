# Architectural Invariants — Memento

These invariants are defined and explained in @ARCHITECTURE.md.
They MUST NOT be violated.

---

## Invariant 1 — Renewal happens on UNLOAD, not on demand

- Memento does NOT force chunk unloads.
- Chunk renewal is triggered only after the server unloads chunks naturally.
- Detection of *what should be renewed* is independent of load state.
- Execution of renewal is deferred and best-effort.

---

## Invariant 2 — Best-effort, non-deterministic execution

- Renewal timing is inherently non-deterministic.
- The system must tolerate:
  - players nearby
  - server pauses
  - delayed unloads
- Lack of immediate progress is not a bug.

---

## Invariant 3 — Two independent lifecycles

There are exactly two lifecycles:

1) **WitherstoneLifecycle**
   - governs stone maturation and consumption

2) **RenewalBatch lifecycle**
   - governs grouped chunk renewal

They are linked, but not the same.
Neither lifecycle may subsume the other.

---

## Invariant 4 — Server-side authority only

- All decisions are made server-side.
- No client cooperation is assumed.
- The mod enhances world mechanics, not gameplay flow.

---

## Invariant 5 — Observability over control

- The system observes Minecraft behavior.
- It does not attempt to override or outsmart it.
- Logging and state transitions exist to explain behavior, not to enforce it.
