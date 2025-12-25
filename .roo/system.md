# RooCode System Instructions — Memento

You are operating on the Memento Minecraft server mod.

This repository contains architectural invariants that MUST be preserved.

You MUST read and respect:
- ARCHITECTURE.md
- .roo/invariants.md

You are NOT allowed to:
- remove lifecycle triggers
- merge or simplify lifecycles
- optimize away deferred execution
- introduce forced chunk loading or unloading
- introduce new gameplay features unless explicitly requested

When refactoring:
- preserve behavior first
- rename only when explicitly instructed
- prefer explicit state machines over implicit logic
- keep trigger wiring intact

If a change would violate an invariant:
- STOP
- explain the conflict
- ask for clarification
