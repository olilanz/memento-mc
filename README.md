# Natural Renewal

**Intentional world renewal through remembering and forgetting.**

Natural Renewal is a lightweight, server-side Fabric mod for Minecraft that helps long-lived worlds evolve naturally over time — without wiping the world or interrupting gameplay.

Vanilla clients are fully compatible.

---

## The problem it solves

Minecraft worlds never forget.

Over time, long-lived worlds become surrounded by vast areas of old, unused terrain. New world features appear farther and farther away, and upgrades or world-generation changes rarely affect the places where players actually live.

Natural Renewal lets the world **move forward naturally**.

It distinguishes between land that is **memorable** — inhabited areas and their surroundings — and land that is **forgettable** — terrain that was explored briefly and then abandoned.

Forgettable areas gradually fade from memory and are **renewed using the current world generation**, starting in the outskirts and working inward.
Inhabited areas remain stable and untouched.

All of this happens **while the server is running**, without offline tools or manual chunk deletion.

---

## The core idea: remembering and forgetting

Not all land deserves to last forever.

Natural Renewal models a simple idea:

* **Memorable land**
  Areas where players live, return to, or spend time — including their immediate surroundings.

* **Forgettable land**
  Areas no player ever settled in, or terrain that has seen no player presence for a long time.

Forgettable land is not destroyed.
It is allowed to **fade from memory** — and only then can it renew.

Renewal happens gradually, beginning far from active gameplay and moving inward over time.

---

## What players and servers experience

Most of the time:

* nothing at all

When renewal does happen:

* it occurs away from inhabited areas
* it happens only when land is abandoned
* it never causes partial or visible tearing
* it does not interrupt gameplay

The world feels stable where it matters — and fresh where it doesn’t.

---

## Stones: guiding memory and renewal

Natural Renewal introduces two conceptual tools to express intent:

* **Witherstone**
  Allows land to become forgettable over time.

* **Lorestone**
  Protects land from being forgotten.

These stones do not force immediate change.
They guide how memory is treated, allowing renewal to happen safely and deliberately.

---

## Operator control

Natural Renewal is **conservative by default**.

The system avoids coming too close to active gameplay and prioritizes stability over speed. Operator control exists as a **protective and guiding mechanism**, not as the primary driver of renewal.

Operators can:

* explicitly protect important areas
* guide renewal toward places where change is acceptable
* take a more deliberate or aggressive stance when desired

Nothing is automatic.
Nothing is forced.

---

## Operator commands (OP ≥ 2)

### System

* `/naturalrenewal version`
  Show the installed mod version.

---

### Stones

* `/naturalrenewal add witherstone <name> <radius> <daysToMaturity>`
  Register a new Witherstone.

* `/naturalrenewal add lorestone <name> <radius>`
  Register a new Lorestone.

* `/naturalrenewal remove witherstone <name>`
  Remove a Witherstone.

* `/naturalrenewal remove lorestone <name>`
  Remove a Lorestone.

* `/naturalrenewal set witherstone <name> daysToMaturity <value>`
  Adjust the maturity time of a Witherstone.

* `/naturalrenewal set witherstone <name> radius <value>`
  Adjust the radius of a Witherstone.

---

### Inspection

* `/naturalrenewal list`
  List all registered stones.

* `/naturalrenewal list witherstone`
  List all Witherstones.

* `/naturalrenewal list lorestone`
  List all Lorestones.

* `/naturalrenewal inspect <name>`
  Explain the current state of a stone, including what is preventing renewal.

Inspection commands are designed to be **explainable** — they tell you *what the system is waiting for*.

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

Natural Renewal is a standard Fabric mod.

1. Install Fabric Loader for your Minecraft version
2. Drop the mod JAR into your server’s `mods` folder
3. Start the server

No client-side installation is required.

---

## Further documentation

* **RENEWAL_MODEL.md** — in-world explanation of memory and forgetting
* **ARCHITECTURE.md** — internal design and invariants
* **DEVELOPMENT.md** — build and development environment

---

Natural Renewal does not erase history.
It makes room for new stories.
