# Architecture Overview

Memento: Natural Renewal is designed as a lightweight server-side Fabric mod.  
All core logic runs entirely on the server, and all functionality must remain compatible with unmodded vanilla clients.

This document defines the architecture, internal components, rules, and development phases.

---

## Core Concepts

### **Anchors (Keeper Stones)**
A placed artifact that prevents natural regeneration within a radius.  
Placed *above ground* on a simple pedestal block.

### **Forgetters (Forgetter Stones)**
A placed artifact that marks an area as eligible for renewal.  
Activated when **buried underground**.

### **Natural Renewal**
The process of:
1. Identifying low-value or unused chunks.
2. Validating they are safe to regenerate.
3. Deleting their region/chunk data.
4. Allowing Minecraft to regenerate them naturally on next load.

---

## High-Level Rules

1. **Server-side only.** No client mod is required.
2. **Vanilla items only** (with custom NBT) until later phases.
3. **No custom blocks in early phases.**
4. Renewal must **never** affect player bases or significant structures.
5. Anchors take priority over forgetters.
6. Forgetters only activate when:
    - the stone is buried, and
    - no players are present in the target chunk radius.
7. Renewal must happen in **small steps**, not all at once.

---

## Component Breakdown

### **1. ChunkScanner**
- Iterates over known chunks.
- Collects metadata needed for forgettability.
- Flags potential forget candidates.

### **2. Forgettability Scoring**
Inputs may include:
- Time since last player visit
- Distance from player hotspots
- Presence of anchors or forgetters
- Cluster scoring (grouping desertion regions)

Output:  
A sorted list of candidate chunks for renewal.

### **3. RenewalEngine**
- Validates whether a chunk is safe to forget.
- Deletes chunk files or marks them for removal.
- Logs actions.
- Ensures anchors override forgetters.

### **4. AnchorManager**
- Detects placement of Keeper Stones on pedestals.
- Tracks radii of protected areas.
- Exposes queries for “is this chunk protected?”

### **5. ForgetterManager**
- Detects burial of Forgetter Stones.
- Schedules renewal operations.
- Exposes queries for “is this chunk marked for forgetting?”

### **6. Command System**
Initial commands:
- `/memento info`
- `/memento forgethere <radius>`
- `/memento anchorhere <radius>`
- `/memento give <stone>`

### **7. Data Storage**
- Simple JSON or NBT for storing:
    - Anchor positions
    - Forgetter positions
    - Cooldowns
    - Renewal state

---

## Development Phases

### **Phase 1 — Infrastructure**
- Kotlin Fabric mod scaffold.
- Commands only.
- Chunk scanning + renewal logic in logs.
- No player items yet.

### **Phase 2 — Player Interaction**
- Introduce Keeper and Forgetter Stones via NBT-tagged vanilla items.
- Pedestal and burial activation rules.
- Basic radius logic for protection/renewal.

### **Phase 3 — Automated Renewal**
- Periodic scanning.
- Forgettability scoring.
- Configurable renewal cadence.
- Crafting recipes for stones.

### **Phase 4 — Optional Enhancements**
- Particle hints.
- Client-only effects.
- Additional stone types.

---

## Out of Scope (Early Phases)

- Custom blocks
- Custom models or textures
- Complex worldgen overrides
- Heavy client-side UI

These may be revisited after core mechanics are stable.
