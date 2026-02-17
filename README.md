# Memento – Natural Renewal

**Intentional world renewal through remembering and forgetting.**

Memento is a lightweight, server-side Fabric mod for Minecraft that helps long-lived worlds evolve naturally over time — without wiping the world or interrupting gameplay.

Vanilla clients are fully compatible.

---

## The problem it solves

Minecraft worlds never forget.

Over time, long-lived worlds become surrounded by vast areas of old, unused terrain. New world features appear farther and farther away, and upgrades or world-generation changes rarely affect the places where players actually live.

Memento lets the world **move forward naturally**.

It distinguishes between land that is **memorable** — inhabited areas and their surroundings — and land that is **forgettable** — terrain that was explored briefly and then abandoned.

Forgettable areas gradually fade from memory and are **renewed using the current world generation**, starting in the outskirts and working inward.
Inhabited areas remain stable and untouched.

All of this happens **while the server is running**, without offline tools or manual chunk deletion.

---

## The core idea: remembering and forgetting

Not all land deserves to last forever.

Memento models a simple idea:

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

Memento introduces two conceptual tools to express intent:

### Witherstone

Marks land as *allowed to renew*.

When it matures, chunks inside its area become eligible for renewal as soon as the server naturally unloads them.

### Lorestone

Protects land from renewal.

Any chunks covered by a Lorestone are guaranteed to never be renewed by Memento, even if they overlap a Witherstone.

Stones do not force immediate change.
Renewal only happens when chunks unload naturally (for example, when players leave an area), keeping the world stable and predictable.

Stones can be visualized in-world using particle effects to clearly show their area of influence.

---

## Quick operator flow (OP ≥ 2)

You do not need to memorize command options — tab-completion guides you.

### Allow renewal

Stand in the area and run:

```
/memento add witherstone mystone
```

This marks the surrounding area as allowed to renew once inactive.

To adjust maturity for testing:

```
/memento alter witherstone mystone daysToMaturity 0
```

---

### Inspect why nothing happens

```
/memento inspect mystone
```

Explains the current state of the stone and what prevents renewal from progressing
(for example: player presence or loaded chunks).

---

### Let the area unload

Renewal only occurs when chunks unload naturally.

To allow that:

* Leave the area
* Ask players to move away
* Log out
* Restart the server (optional)

Nothing is forced.

---

### Protect important areas

```
/memento add lorestone mybase
```

Remove protection later if needed:

```
/memento remove mybase
```

Lorestones override Witherstones inside their area.

---

### Visualize influence

```
/memento visualize mystone
```

Shows temporary particle effects marking the stone’s exact area of influence.
Visualization is informational only and does not modify terrain.

---

## Command overview

All commands require OP level 2 or higher.

Common commands:

```
/memento add witherstone <name> [...]
/memento add lorestone <name> [...]
/memento alter witherstone <name> ...
/memento remove <name>
/memento list
/memento inspect <name>
/memento visualize <name>
```

Use tab-completion to explore available parameters.

---

## Safety and compatibility

* Server-side only
* Vanilla clients fully compatible
* No forced chunk unloads
* No partial regeneration
* Deterministic and explainable behavior
* Compatible with world-generation mods

Renewal only occurs when land is fully abandoned.
The Minecraft server always remains authoritative over chunk lifecycle.

---

## Installation

Memento is a standard Fabric mod.

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

Memento does not erase history.
It makes room for new stories.
