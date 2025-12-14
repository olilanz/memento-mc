# Architecture Overview

Memento: Natural Renewal is a lightweight, server-side Fabric mod.  
All logic runs on the server and must remain compatible with vanilla clients.

This document describes the current architecture and the intended evolution path.

---

## Core Abstraction: Anchors

An **anchor** is a named, persistent marker in the world.

Each anchor has:
- A unique name
- A position and dimension
- A radius
- A kind:
    - **REMEMBER** — protects an area from renewal
    - **FORGET** — marks an area as eligible for renewal
- Optional expiry semantics (days), used only for FORGET anchors

Anchors are authoritative.  
Future systems (scanning, renewal, automation) will consult anchors first.

---

## Current Components

### **1. Command System**
The primary interaction surface.

Commands:
- `/memento anchor remember …`
- `/memento anchor forget …`
- `/memento release <name>`
- `/memento list`
- `/memento info`

The command layer is intentionally explicit and operator-focused.

---

### **2. Anchor Registry**
- In-memory representation of anchors
- Backed by JSON persistence
- Name-based replacement semantics
- No world scanning required to read anchor data

---

### **3. Persistence Layer**
- Simple JSON storage
- Loaded on server start
- Written on mutation
- Decoupled from chunk loading

This keeps anchor management cheap and deterministic.

---

## Planned (Not Yet Implemented)

### **Chunk Metadata Analysis**
- Read chunk metadata without loading chunks
- Track inhabited time, visit history, and player proximity
- Build forgettability scores

### **Renewal Engine**
- Validate safety constraints
- Respect simulation distance rules
- Delete chunks only when safe
- Allow regeneration on next load

### **Player Interaction Layer**
- Introduce conceptual items (e.g. “lorestone”, “witherstone”)
- Still vanilla-compatible (NBT-based)
- Command system remains authoritative

---

## Development Phases (Revised)

### Phase 1 — Foundations (Current)
- Stable command grammar
- Anchor model
- Persistence
- No world mutation

### Phase 2 — Observation
- Chunk metadata reading
- Forgettability computation
- Diagnostics via `/memento info`

### Phase 3 — Renewal
- Controlled deletion queue
- Safe chunk unloading
- Regeneration based on anchors

### Phase 4 — Optional UX
- Player-facing items
- Visual hints
- Optional client enhancements

---

## Explicitly Out of Scope (for now)

- Custom blocks
- Custom models or textures
- Client-required mods
- Automatic, opaque decision-making
