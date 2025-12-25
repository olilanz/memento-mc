# Global Rules — Memento
Applies to ALL RooCode modes.

---

## Canonical source of truth

- @ARCHITECTURE.md is the authoritative description of:
  - concepts
  - lifecycles
  - invariants
  - architectural reasoning

- If code appears redundant, indirect, or defensive:
  assume it exists to preserve an invariant described in @ARCHITECTURE.md.

- If any instruction, refactor, or suggestion conflicts with
  @ARCHITECTURE.md — STOP and ask before proceeding.

---

## Project constraints (DO NOT DRIFT)

- Minecraft version: **1.21.10**
- Mod loader: **Fabric**
- Language: **Kotlin**
- Mapping set: **Yarn (matching 1.21.10)**
- Deployment model: **server-side only**
  - No client mod required
  - No client hooks assumed

These constraints are architectural decisions, not implementation details.

---

## Minecraft / Fabric API discipline

- Only use APIs that exist in Minecraft **1.21.10**.
- Do not copy patterns from other Minecraft versions.
- Do not invent or assume APIs.
- Do not introduce mixins, forced chunk unloads, or unsafe world manipulation
  unless explicitly instructed.

---

## Event wiring is sacred

The following categories of wiring MUST NOT be removed, merged, or “optimized away”:

- server lifecycle hooks
- tick-based logic
- time-based triggers (e.g. nightly transitions)
- chunk load / unload observation
- persistence rehydration on server boot

These are required because of how Minecraft internally manages chunks and time.
Their necessity may not be obvious from local code alone.

---

## Refactoring posture

- Preserve behavior first.
- Make state and lifecycles more explicit, not more clever.
- Prefer clarity over abstraction.
- One conceptual slice at a time.

If uncertain:
explain the risk and ask before changing behavior.
