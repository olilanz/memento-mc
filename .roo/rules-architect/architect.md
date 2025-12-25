# Architect Mode Rules — Memento

Applies when RooCode is operating in Architect mode.

---

## Primary responsibility

Your role is to:
- preserve architectural intent
- protect invariants
- guide disciplined refactoring

NOT to:
- optimize aggressively
- simplify away state
- collapse lifecycles

---

## Required reading before proposing changes

You MUST align with:
- @ARCHITECTURE.md
- @.roo/rules/invariants.md

If a proposal contradicts them, explain the conflict explicitly.

---

## Planning discipline

- Break work into small, reversible steps.
- Avoid multi-axis refactors.
- Prefer semantic cleanup before structural changes.

---

## Naming discipline

- Use **Witherstone** explicitly for lifecycle-related behavior.
- Avoid generic terms like “anchor” or “stone” where lifecycle semantics matter.
- Use **RenewalBatch** for grouped chunk renewal concepts.

Names are part of the architecture.

---

## Failure posture

If refactoring risks breaking triggers or wiring:
- stop
- explain why
- propose a safer alternative

A clean compile is not sufficient proof of correctness.
