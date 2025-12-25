# Memento – Architecture

This document describes the architectural foundations of **Memento**, a server-side Minecraft mod for **Fabric / Minecraft 1.21.10**, written in **Kotlin**.

It captures **concepts, invariants, and design decisions** that are not obvious from reading the code alone and must remain stable even as the codebase evolves.

This document intentionally avoids gameplay framing, implementation details, and user-facing instructions. Those belong in `README.md` and `STORY.md`.

---

## 1. Scope and Constraints

### 1.1 Technical Scope

- Memento is a **purely server-side** mod.
- It modifies **world mechanics**, not client gameplay.
- No client mod is required.
- Fabric is used as a loader, but no Fabric-specific assumptions are embedded in the core design.
- The architecture is intentionally portable to other loaders.

### 1.2 Execution Environment

- Minecraft version: **1.21.10**
- Language: **Kotlin**
- Loader: **Fabric**
- Execution model: Minecraft server tick loop, chunk ticketing, and chunk lifecycle events

---

## 2. Problem Framing

### 2.1 What Memento Is Trying to Achieve

The primary objective of Memento is:

> **Gradual, natural renewal of the world in the outskirts**, where chunks are least memorable and least interacted with.

Renewal must:
- preserve important, inhabited areas
- avoid disruptive or unsafe operations
- integrate with Minecraft’s existing chunk lifecycle

### 2.2 Secondary Objective: Operator-Controlled Renewal

A secondary objective exists to support **manual and controlled renewal**:

- Players or operators may explicitly mark areas for renewal
- This provides protection and control for important locations
- This path exists primarily to:
  - exercise and validate the core mechanics
  - support edge cases near inhabited areas

From an engineering perspective, **most development effort focuses on this secondary objective**, even though it is not the primary purpose of the mod.

This distinction is intentional and must not be confused.

---

## 3. Best-Effort Execution and Operator Control

### 3.1 Best-Effort as a Design Principle

Minecraft does not expose a safe or deterministic API for forcibly unloading chunks.

As a result:
- Chunk renewal cannot be performed eagerly or deterministically
- Renewal must be **deferred** until chunks naturally unload
- Execution timing is **non-deterministic** and **best-effort**

Correctness applies to:
- *which* chunks are renewed  
not:
- *when* they are renewed

This is a foundational architectural decision.

---

### 3.2 Levels of Operator Control

The architecture explicitly assumes increasing levels of operator intervention:

1. **Leave the Area**
   - Natural chunk unload
   - Expected to work in outskirts
   - Primary path for automatic renewal

2. **Log Users Out**
   - Forces player-held chunks to unload
   - Useful near inhabited areas
   - Operator-controlled

3. **Restart the Server**
   - Guarantees all chunks unload
   - Heavy-handed but safe
   - Always available as a last resort

Because these control levels exist, the system:
- must tolerate delayed execution
- must persist intent across restarts
- must never rely on forced unload logic

---

## 4. Core Concepts

### 4.1 Stones

A **Stone** is a persistent, in-world marker representing *intent*.

Stones do **not** directly perform chunk operations.

There are multiple stone types, each with distinct semantics.

#### Lorestone
- Protects an area from renewal
- Defines a radius of preservation
- Has no automated lifecycle

#### Witherstone
- Represents intent to renew an area
- Has an automated lifecycle
- Produces exactly one renewal operation

---

### 4.2 Renewal Batches

A **RenewalBatch** represents the execution of renewal for a set of chunks.

Characteristics:
- Derived from a single matured Witherstone
- Groups all chunks within the defined radius
- Progresses independently of the originating Witherstone
- Operates purely based on observed chunk lifecycle events

A RenewalBatch is the **unit of execution**.

---

## 5. Chunk Lifecycle and Renewal Strategy

### 5.1 Minecraft Chunk Model (Relevant Aspects)

- Chunks are loaded and unloaded based on ticketing
- Players, entities, and systems hold tickets
- Forced unload is unsafe and may cause race conditions
- Chunk unload events are observable and reliable

### 5.2 Chosen Renewal Strategy

Memento renews chunks only when:
- all chunks in a batch are fully unloaded
- the server naturally initiates a load afterward

At that point:
- the load is intercepted
- the chunk is regenerated instead of loaded from disk

This approach:
- avoids forced unload
- avoids race conditions
- aligns with Minecraft’s execution model

---

## 6. Lifecycle Model and Invariants

### 6.1 Witherstone Lifecycle (Intent Lifecycle)

Purpose:
- Represent **explicit intent** to renew an area

Characteristics:
- Independent of chunk load state
- Driven by time and operator action
- Produces exactly one RenewalBatch
- Consumed only after successful renewal

Typical states:
- PLACED
- MATURING
- MATURED
- CONSUMED

**Invariant:**  
> The Witherstone lifecycle governs intent, not execution.

---

### 6.2 RenewalBatch Lifecycle (Execution Lifecycle)

Purpose:
- Manage **safe and deferred execution** of renewal

Characteristics:
- Exists independently after derivation
- Driven solely by observed chunk events
- Can be delayed indefinitely without breaking correctness

Typical states:
- DERIVED
- BLOCKED (one or more chunks loaded)
- FREE (all chunks unloaded)
- RENEWING
- RENEWED

**Invariant:**  
> The RenewalBatch lifecycle governs execution, not intent.

---

### 6.3 Relationship Between the Two Lifecycles

- A matured Witherstone creates **exactly one** RenewalBatch
- After creation:
  - the Witherstone no longer drives execution
  - the RenewalBatch progresses autonomously
- Successful completion:
  - consumes the originating Witherstone
- Delays or failures:
  - do not roll back Witherstone state

**Architectural Rule:**  
> Intent and execution must never be coupled to the same lifecycle.

---

## 7. Triggers and Observability

Triggers in Memento are **observational**, not causal.

Examples:
- time crossing the nightly threshold
- server startup state reconciliation
- chunk unload events
- chunk load attempts

Triggers:
- may fire multiple times
- must be idempotent
- must never assume ordering or exclusivity

This design ensures:
- restart safety
- resilience to partial progress
- correctness under repeated evaluation

---

## 8. Persistence and Recovery

- Stones and RenewalBatches are persisted
- State is reconstructed on server startup
- Pending renewal is resumed naturally
- No special recovery mode is required

Persistence exists to:
- preserve intent
- tolerate restarts
- support best-effort execution

---

## 9. Architectural Stability

This document defines:
- concepts
- lifecycles
- invariants
- execution philosophy

It intentionally does **not** define:
- class structures
- method names
- threading strategies
- code-level optimizations

Any refactoring must preserve the invariants described here.
