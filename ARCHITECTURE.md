## Architecture Overview

Memento: Natural Renewal is a lightweight, server-side Fabric mod.
All logic runs on the server and must remain compatible with vanilla clients.

This document describes:

* the current architecture
* the guiding principles
* and the intended evolution path

---

## Architectural Constraints (Foundational)

These constraints are **non-negotiable**:

* Server-side only
* Vanilla client compatibility
* Deterministic, operator-driven behavior
* Explicit commands over implicit automation
* No reliance on client state or mods

---

## Development Environment Philosophy

Memento is developed in a **container-first, reproducible build environment**.

This is intentional.

### Key principles:

* The **Gradle Wrapper** is authoritative
* Builds must succeed via:

  * CLI
  * dev container
  * CI-style execution
* IDEs are treated as *assistive*, not *defining*
* Java version, Gradle version, and Fabric tooling are fixed and explicit

This avoids:

* IDE-specific behavior
* environment drift
* “works on my machine” issues

---

## Core Abstraction: Anchors

An **anchor** is a named, persistent marker in the world.

Each anchor has:

* A unique name
* A position and dimension
* A radius
* A kind:

  * **REMEMBER** — protects an area from renewal
  * **FORGET** — marks an area as eligible for renewal
* Optional expiry semantics (days), used only for FORGET anchors

Anchors are authoritative.
Future systems (scanning, renewal, automation) will consult anchors first.

---

## Current Components

### 1. Command System

The primary interaction surface.

Commands:

* `/memento anchor remember …`
* `/memento anchor forget …`
* `/memento release <name>`
* `/memento list`
* `/memento info`

The command layer is intentionally explicit and operator-focused.

---

### 2. Anchor Registry

* In-memory representation of anchors
* Backed by JSON persistence
* Name-based replacement semantics
* No world scanning required to read anchor data

---

### 3. Persistence Layer

* Simple JSON storage
* Loaded on server start
* Written on mutation
* Decoupled from chunk loading

This keeps anchor management cheap, deterministic, and inspectable.

---

## Planned (Not Yet Implemented)

### Chunk Metadata Analysis

* Read chunk metadata without loading chunks
* Track inhabited time, visit history, and player proximity
* Build forgettability scores

### Renewal Engine

* Validate safety constraints
* Respect simulation distance rules
* Delete chunks only when safe
* Allow regeneration on next load

### Player Interaction Layer

* Conceptual items (e.g. “lorestone”, “witherstone”)
* Still vanilla-compatible (NBT-based)
* Commands remain authoritative

---

## Development Phases

### Phase 1 — Foundations (Current)

* Stable command grammar
* Anchor model
* Persistence
* No world mutation

### Phase 2 — Observation

* Chunk metadata reading
* Forgettability computation
* Diagnostics via `/memento info`

### Phase 3 — Renewal

* Controlled deletion queue
* Safe chunk unloading
* Regeneration based on anchors

### Phase 4 — Optional UX

* Player-facing affordances
* Visual hints
* Optional client enhancements

---

## Explicitly Out of Scope (for now)

* Custom blocks
* Custom models or textures
* Client-required mods
* Automatic, opaque decision-making
* “AI-driven” renewal logic

---

## Why this matters for newcomers

A newcomer should understand:

* **Memento is intentional, not automatic**
* **Commands define truth**
* **Anchors are first-class**
* **Build correctness matters**
* **The dev container is the reference environment**

Once those are understood, the rest of the system is deliberately simple.

---

## Closing note

Memento values:

* clarity over cleverness
* explicit control over heuristics
* correctness over convenience

The architecture reflects this at every level.