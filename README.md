# Memento – Natural Renewal

**Intentional world renewal through remembering and forgetting.**

Memento is a lightweight, server-side Fabric mod for Minecraft that helps long-lived worlds evolve naturally over time — without wiping the world or interrupting gameplay.

Vanilla clients are fully compatible.

---

## The problem it solves

Minecraft worlds never forget.

Over time, long-lived worlds become surrounded by vast areas of old, unused terrain. New world features appear farther and farther away, and upgrades or world-generation changes rarely affect the places where players actually live.

Memento enables **natural renewal**.

It distinguishes between land that is **memorable** — inhabited areas and their surroundings — and land that is **forgettable** — terrain that was explored briefly and then abandoned.

Forgettable land is not destroyed.  
It fades from memory — and only then can it renew.

Renewal begins far from active gameplay and gradually moves inward over time. Inhabited areas remain stable and untouched.

All of this happens while the server is running. No offline tools. No manual region deletion.

---

## The core idea: remembering and forgetting

Not all land deserves to last forever.

Memento models a simple idea:

* **Memorable land**  
  Areas where players live, return to, or spend time — including their surroundings.

* **Forgettable land**  
  Areas that were explored but never settled, or that have seen no meaningful activity for a long time.

Forgettable land is allowed to fade.  
Renewal only happens after that fading has occurred.

The world feels stable where it matters — and fresh where it doesn’t.

---

## What players and servers experience

Most of the time:

* nothing at all

When renewal happens:

* it occurs away from inhabited areas
* it only affects abandoned land
* it never causes partial regeneration or visible tearing
* it does not interrupt gameplay

Memento prioritizes stability over speed.

---

## Stones: guiding memory and renewal

Memento introduces two tools to express operator intent:

* **Witherstone**  
  Marks land for renewal over time. Once mature, it schedules its area for renewal when chunks naturally unload.

* **Lorestone**  
  Protects land permanently from renewal.

Stones do not force immediate change.  
Renewal only occurs when chunks can unload naturally, keeping the world stable and predictable.

---

## Operator workflow (OP ≥ 2)

Memento is conservative by default.

Operators typically work in two stages:

### 1) Define intent with stones

The primary workflow today is zone-based renewal.

* `/memento add witherstone <name> [radius] [daysToMaturity]`
* `/memento add lorestone <name> [radius]`
* `/memento remove witherstone <name>`
* `/memento remove lorestone <name>`
* `/memento alter witherstone <name> radius <value>`
* `/memento alter witherstone <name> daysToMaturity <value>`
* `/memento alter lorestone <name> radius <value>`

Witherstones mature over in-game days.  
Lorestones prevent renewal in protected areas.

This is the primary entry point for new operators.

---

### 2) Observe and act deliberately

Operators can inspect system state:

* `/memento explain`  
  Operator dashboard summary.

* `/memento explain world`  
  World knowledge completeness and renewal health.

* `/memento explain stones`  
  Overview of registered stones.

* `/memento explain renewal`  
  Current renewal state, including:
  1. Top renewal candidates  
  2. Stones waiting to mature  
  3. Stones waiting for consumption  
  4. Blocking conditions  

Operators may also act deliberately:

* `/memento do scan`  
  Trigger an active world scan.

* `/memento do renew [N]`  
  Immediately submit up to `N` renewal actions (default `N = 1`).

These commands are explainable by design.  
They describe what the system is waiting for and allow controlled intervention without hidden lifecycle state.

---

## Safety and compatibility

* Server-side only  
* Vanilla clients fully compatible  
* No forced chunk unloads  
* No partial regeneration  
* Deterministic and explainable behavior  
* Compatible with world-generation mods  

Renewal only occurs when land is fully abandoned.

---

## Installation

Memento is a standard Fabric mod.

1. Install Fabric Loader for your Minecraft version  
2. Place the mod JAR in your server’s `mods` folder  
3. Start the server  

No client installation required.

---

## Further documentation

* **RENEWAL_MODEL.md** — in-world explanation of memory and forgetting  
* **ARCHITECTURE.md** — internal design and invariants  
* **DEVELOPMENT.md** — build and development environment  

---

## Open Issues Before General Availability

Memento is approaching general availability.  
The following architectural gaps are known and will be resolved before GA.

### 1. Renewal Loop Prevention (Chunks & Regions)

Renewed chunks and pruned regions must be marked as “recently renewed” so they do not immediately become eligible again.

Without durable exemption markers, the system may:

* renew the same chunk repeatedly, or  
* oscillate between region pruning and chunk renewal near region borders.

This applies to both region-level pruning and chunk-level renewal.

---

### 2. Renewal Candidate Freshness vs. World Activity

The renewal candidate set is derived from a settled projection snapshot.  
On very busy servers, the projection may not settle quickly, causing candidate information to become stale.

Renewal must never affect newly inhabited areas, even when candidate information is old.  
A stable combination of candidate stability and live safety validation is required.

---

### 3. Full Automation Scheduling

Natural renewal (scan → projection → renew) is not yet fully automated on a nightly cadence.

Automation will only be enabled once loop-prevention and queue-freshness safeguards are fully validated.
