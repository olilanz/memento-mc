# Memento: Natural Renewal

**Intentional world renewal, guided by memory and forgetting.**

Memento is a lightweight, server-side Fabric mod for Minecraft 1.21.10+.
It helps long-lived worlds stay healthy by allowing unused terrain to
be *forgotten* and naturally regenerated — without affecting places that matter.

Memento is entirely server-driven.
Vanilla clients are fully compatible.

---

## What Memento Does

Memento introduces the idea of **memory anchors**:

- Some places should be **remembered**.
- Some places are safe to **forget**.
- Forgetting results in **natural regeneration**, not destruction.

Forgetting is:
- explicit
- delayed
- safe
- and fully explainable

Nothing is automatic. Nothing is forced.

---

## Current Gameplay Mechanics

### Witherstones (Forgetting)

A **Witherstone** marks an area that should eventually be forgotten.

- The stone **matures over time** (configured in days)
- Once matured, the surrounding land is **marked for forgetting**
- Forgetting only begins when **all affected chunks are unloaded**
- The land is then regenerated atomically
- The Witherstone is consumed

This ensures:
- no partial regeneration
- no player-visible tearing
- no forced chunk unloads

---

## Commands

´´´bash
/memento anchor forget <name> [radius] [x y z] [days]
/memento anchor remember <name> [radius] [x y z]
/memento release <name>
/memento list
/memento info
´´´

Notes:

- Anchor names are unique
- Reusing a name replaces the existing anchor
- Coordinates default to the player position
- All commands require operator permissions

---

## Design Principles

- Server-side only
- Vanilla client compatible
- Deterministic and explainable behavior
- Explicit operator intent
- No hidden automation

---

## Getting Started (Development)

Memento is developed in a **container-based environment** to avoid
toolchain drift.

### Recommended: VS Code + Dev Container

1. Install Docker and VS Code
2. Install the “Dev Containers” extension
3. Open the repository
4. Choose **Reopen in Dev Container**
5. Run:

´´´bash
./gradlew runServer
´´´

Connect using a vanilla Minecraft client.

---

## Documentation

- **ARCHITECTURE.md** — technical design and invariants
- **STORY.md** — in-world explanation of memory and forgetting

---

## License

MIT
