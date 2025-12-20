# Memento: Natural Renewal — Architecture

This document describes the **current, implemented architecture** of Memento.

It focuses on:
- invariants
- lifecycles
- triggers
- and deliberate constraints

---

## Foundational Constraints

These are non-negotiable:

- Server-side only
- Vanilla client compatibility
- No forced chunk loading or unloading
- Deterministic behavior
- Explicit operator control

---

## Core Model: Two Lifecycles

Memento is built around **two distinct lifecycles**:

1. **Witherstone lifecycle** — time & intent
2. **Land (chunk group) lifecycle** — space & permission

They intersect, but they are not the same.

---

## Witherstone Lifecycle (Time-Based)

A Witherstone works slowly and invisibly.

The `days` parameter represents **time to maturity**.

### States

1. **Maturing**
   - Influence builds over world days
   - No world mutation occurs

2. **Matured**
   - Time to maturity has elapsed
   - The surrounding land is marked for forgetting
   - No chunk data is discarded yet

3. **Consumed**
   - Forgetting has completed
   - The Witherstone is removed

A matured Witherstone **permits forgetting**,
but does not perform it directly.

---

## Land / Chunk Group Lifecycle (Space-Based)

Chunk groups are **derived**, ephemeral structures.
They only exist meaningfully after a Witherstone matures.

### States

1. **Marked**
   - Land is subject to forgetting

2. **Blocked**
   - One or more affected chunks are still loaded

3. **Free**
   - All affected chunks are unloaded
   - Forgetting is now safe

4. **Forgetting**
   - Chunk data is discarded
   - Regeneration is executed atomically

5. **Renewed**
   - New world state generated
   - Group discarded

---

## Invariants

- Forgetting is atomic per group
- No partial regeneration
- No forced unloads
- No retries or polling
- Forgetting begins only on chunk unload
- Witherstone removal happens only after renewal

---

## Triggers

### World Day Change

- Detected via day index (`timeOfDay / 24000`)
- Advances Witherstone maturation
- Independent of sleep or `/time set`

### Chunk Unload

- Evaluates whether marked land is now free
- Triggers forgetting when safe

---

## Observability

High-signal lifecycle events are reported to:

- server logs
- operator chat (OP ≥ 2)

Messages describe **world facts**, not engine mechanics.

---

## Explicitly Out of Scope

- Client-side mods
- Heuristic or AI-driven decisions
- Forced chunk management
- Partial regeneration strategies

---

## Architectural Values

- Clarity over cleverness
- Intent over automation
- Safety over speed
- Semantics that survive time

The code is written to reflect these values directly.
