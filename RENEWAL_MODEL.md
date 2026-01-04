# Renewal Model

This document describes how Natural Renewal decides **where renewal may occur**,
independent of implementation or technology.

It defines the balance between remembering and forgetting, and the role of
operator guidance within that balance.

---

## A world that never forgets

By default, Minecraft worlds remember everything.

Every explored area is kept indefinitely, regardless of whether it remains
relevant to gameplay. Over time, this causes long-lived worlds to stagnate,
with large regions of unused terrain frozen in an old state.

Natural Renewal introduces the idea that **memory can fade**.

---

## What creates memory

A chunk’s memory is influenced by three factors:

### Presence

Player presence is the strongest signal of memory.

Chunks that players inhabit, return to, or meaningfully interact with are
considered memorable.

---

### Time since last altered

Memory weakens with inactivity.

Chunks that have not been altered for a long time gradually lose significance,
even if they were once visited.

---

### Proximity to memorable areas

Memory has spatial continuity.

Chunks close to memorable areas inherit stability, while distant chunks are more
likely to fade. This ensures that renewal does not approach inhabited regions
abruptly.

Spatial continuity is evaluated in **world space** and then mapped onto chunks.
The model does not assume square or grid-aligned regions.

---

## Forgettability and thresholds

The combined influence of presence, time, and proximity determines a chunk’s
**forgettability**.

- Chunks below the forgettability threshold are **candidates for natural renewal**
- Chunks above the threshold are **never renewed automatically**

Natural Renewal only acts on chunks that have already faded from memory.

This makes renewal conservative by default.

---

## Renewal follows absence

Natural renewal progresses where memory is weakest:

- far from inhabited areas
- in regions with long inactivity
- where player presence has been minimal or absent

Renewal does not jump across space or bypass memorable land.
It advances gradually, maintaining spatial coherence.

Candidate areas are derived from continuous world-space evaluation and resolved
at chunk granularity before renewal is considered.

---

## Influence resolution and eligibility

A chunk may be subject to multiple forms of influence simultaneously.

Before renewal is considered, all influences affecting a chunk are resolved.
Renewal proceeds **only if the resolved influence permits it**.

Influence resolution follows a strict priority:

- **Protection** (e.g. Lore influence) always prevents renewal
- **Explicit renewal intent** (e.g. Wither influence) overrides the natural model
- **Natural renewal** applies only when no higher-priority influence is present
- **No influence** results in no renewal

Natural Renewal therefore operates only on chunks whose resolved influence
explicitly allows it.

---

## The role of the operator

Operator actions do not replace the renewal model.
They **override it intentionally**.

An operator may:

- **Force renewal**  
  Even for chunks that are not considered forgettable by the model.

- **Protect chunks**  
  Preventing renewal even when the model considers them forgettable.

Operator actions change **eligibility**, not the underlying definition of memory.
This allows deliberate intervention without weakening the conservative nature of
natural renewal.

---

## Balance over automation

Natural Renewal is not a cleanup system.

It is designed to balance:

- preservation and change
- safety and progress
- passivity and intent

The model defines what is safe.
The operator decides what is desired.

Renewal happens only where memory has already faded — unless explicitly guided
otherwise.
