# Memento — Architecture

This document describes the architectural foundations of **Memento**, a server-side Minecraft mod for controlled, gradual world renewal.

It captures the **axioms, invariants, and conceptual structure** that govern the design.  
It intentionally avoids implementation detail, gameplay narrative, and user-facing documentation.

The purpose of this document is to make the architecture *understandable, durable, and safe to evolve*.

---

## 1. Technical Context

- **Minecraft version**: 1.21.10  
- **Loader**: Fabric  
- **Language**: Kotlin  
- **Execution model**: Dedicated server, server-side only  

Memento is implemented as a **pure server-side enhancement** to world mechanics.  
No client mod is required.

Although Fabric is used as the loader, the architecture does not rely on Fabric-specific client features and can be ported to other loaders if needed.

---

## 2. Problem Framing

### 2.1 Desired Outcome

Minecraft worlds naturally accumulate history:

- abandoned structures  
- obsolete terrain  
- regions shaped by outdated world-generation logic  

Memento introduces **gradual, selective renewal** of world regions so that:

- rarely visited areas slowly revert to fresh terrain  
- important or meaningful areas remain protected  
- renewal happens incrementally, not destructively  

The goal is **long-term world sustainability**, not immediate transformation.

---

### 2.2 Constraints and Reality

Memento operates within the constraints of the Minecraft server:

- Chunk loading and unloading are controlled by the server engine  
- Chunk unloads cannot be safely forced  
- Chunk loads are demand-driven and ticket-based  
- World activity is inherently non-deterministic  

These constraints fundamentally shape the architecture.

---

## 3. Renewal Paths

Memento supports **two renewal paths**:

1. **Automatic renewal**  
   Gradual renewal of rarely visited regions (outskirts).  
   This is the **primary objective** of the mod.

2. **Player / operator-controlled renewal**  
   Explicit placement of stones to control renewal or protection.  
   This is a **secondary objective**, used to validate and exercise core mechanics.

From an architectural perspective, the secondary path is where most complexity is explored and stabilized first, even though it is not the primary purpose of the mod.

---

## 4. Core World Interaction Model

### 4.1 Chunk Detection vs Chunk Renewal

Memento strictly separates:

- **Detection** of what *should* be renewed  
- **Execution** of renewal *when the world allows it*  

Detection is independent of chunk load state.  
Renewal is **deferred** until the server naturally unloads and reloads chunks.

Detection operates on chunk-level influence derived from **world-space intent**; it does not assume grid-aligned or square regions.

---

### 4.2 Deferred Execution

Memento does not force chunk unloads or loads.

Instead, it observes server events and reacts when conditions are met.

This results in:

- non-deterministic timing  
- incremental progress  
- eventual consistency rather than immediate effect  

This behavior is intentional and fundamental.

---

## 5. Stones

### 5.1 Witherstone

The **Witherstone** is the only stone with an automated lifecycle.

It represents an **intent to renew** an area.

A Witherstone:

- matures over time  
- derives exactly one renewal operation  
- is consumed once renewal completes  

The Witherstone lifecycle exists to *initiate* renewal — not to perform it.

---

### 5.2 Lorestone

The **Lorestone** represents **protection**, not renewal.

A Lorestone:

- has no automated lifecycle  
- does not mature  
- is never consumed automatically  
- protects an area within a defined radius  

Lorestones allow players or operators to explicitly mark regions as important and exclude them from renewal logic.

---

### 5.3 Spatial Influence Model

Memento models areas of influence in **world space**, not as chunk-aligned squares.

An area of influence is derived from a source (e.g. a stone) and evaluated per chunk using the following principles:

- Influence is computed based on the **distance from the source position to the center of a chunk**
- Distance is evaluated using a **circular (Euclidean) model**
- A fixed margin is applied to ensure that the chunk containing the source is always included, regardless of exact placement within the chunk

As a consequence:

- Areas of influence may appear **slightly irregular at small radii**
- Larger radii converge toward visibly circular shapes
- Shapes are deterministic and derived from world-space intent, not grid alignment  

This behavior is **intentional** and avoids artificial square boundaries caused by chunk geometry.

Chunks are the smallest unit of influence, but influence itself is derived from continuous world-space positioning.

---

### 5.4 Influence Resolution

A chunk may be subject to multiple sources of influence simultaneously.

Memento resolves influence **per chunk** using a strict priority order:

1. **Lore influence**  
   Protection. No renewal may occur while lore influence is present.

2. **Wither influence**  
   Explicit, stone-driven intent to renew.

3. **Natural influence**  
   Implicit, system-driven renewal of rarely visited areas (future).

4. **None**  
   No influence applied.

Influence resolution is **authoritative and global**.  
All systems — renewal execution, batching, and visualization — must rely on the same resolved influence outcome.

---

## 6. Lifecycles

Memento contains **two distinct lifecycles**.

They are linked, but independent.

---

### 6.1 Witherstone Lifecycle

The Witherstone lifecycle governs *when* renewal is initiated.

States:

- `PLACED`  
- `MATURING`  
- `MATURED`  
- `CONSUMED`  

Triggers for maturation:

- nightly checkpoint (03:00)  
- administrative time adjustment  
- server startup when persisted state indicates maturity  

Maturity is derived solely from a decrementing day counter; time-of-day is only used to detect day boundaries.

Once a Witherstone reaches `MATURED`, it produces exactly one **RenewalBatch** and plays no further role.

---

### 6.2 RenewalBatch Lifecycle

A **RenewalBatch** represents a group of chunks derived from a matured Witherstone.

It encapsulates *how* renewal is executed.

States:

- `DERIVED`  
- `BLOCKED` (some chunks still loaded)  
- `FREE` (all chunks unloaded)  
- `RENEWING`  
- `RENEWED`  

Progression through these states depends entirely on observed server events.

---

## 7. Operator Control and System Settling

Because renewal depends on chunk unloads, progress can be influenced — but not forced — by operators.

Available control levels:

1. **Leave the area**  
   Sufficient for outskirts and rarely visited regions.

2. **Log players out**  
   Reduces active chunk tickets near inhabited areas.

3. **Restart the server**  
   Clears residual tickets and guarantees unload.

These controls are not required for normal operation but exist as **escape hatches** when renewal must proceed in dense or important regions.

---

## 8. Architectural Invariants

The following invariants are **non-negotiable**.

They exist to protect correctness and stability.

> **Invariant 1 — No Forced Unloads**  
> Memento must never forcibly unload chunks.

> **Invariant 2 — Deferred Renewal**  
> Chunk renewal is executed only in response to naturally observed unload and load events.

> **Invariant 3 — One Renewal per Witherstone**  
> Each Witherstone produces exactly one RenewalBatch.

> **Invariant 4 — Server Authority**  
> The Minecraft server remains the sole authority over chunk lifecycle decisions.

Violating these invariants leads to race conditions, instability, or world corruption.

---

## 9. Persistence and Continuity

All lifecycles are persisted so that:

- server restarts do not reset progress  
- matured Witherstones are detected on startup  
- RenewalBatches resume in a consistent state  

Persistence enables renewal to span real-world time without requiring continuous uptime.

---

## 10. Visualization

Visualization is treated as a **first-class application concern**, separate from domain logic.

Visual effects exist to:

- provide in-world confirmation of intent  
- communicate state transitions (e.g. creation, maturity)  
- aid operators in understanding system behavior  

Key architectural principles:

- Visualization is **event-driven**, not tick-driven  
- Visual effects **react to domain events** but do not affect domain state  
- All visualization is:
  - server-side  
  - vanilla-compatible  
  - ephemeral  

Visual representation is expected to evolve over time.  
The architecture intentionally separates **what happens** from **how it is shown**.
