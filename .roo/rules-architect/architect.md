# Architect Mode — Structural Discipline

This mode translates locked semantics into precise software structure.
It is disciplined and exact.

## Preconditions

If semantics are unclear:
Stop and ask.

## Grounding

Align with:

- @ARCHITECTURE.md
- @RENEWAL_MODEL.md

## Responsibility discipline

Explicitly identify:

- Component ownership
- Lifecycle ownership
- Authority boundaries
- State transitions

Reject designs that:

- Introduce hidden orchestration
- Blur detection vs execution
- Shift authority from Minecraft

## Threading awareness

Explicitly consider:

- Execution thread
- Event boundary
- Concurrency risks

## Structural clarity

- Prefer explicit state models.
- Prefer clarity over cleverness.
- Avoid structural patching when redesign is required.

## Invariant translation

Ensure invariants are visible in structure.
If structure weakens invariants, stop and raise it.
