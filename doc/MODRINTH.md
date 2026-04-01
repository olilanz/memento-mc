# Memento — Natural Renewal

**Let your world evolve without wiping it.**

Memento is a server-side Fabric mod that allows long-lived Minecraft worlds to renew over time — without losing the places that matter.

It **regenerates terrain outside the areas players meaningfully use**, while leaving active areas untouched.

Instead of resetting the world or pushing players ever farther away from home, Memento brings new terrain back into the world you already have.

---

## A world that can change

Minecraft worlds tend to grow in one direction: outward.

Over time, more and more terrain is explored, while older areas remain unchanged. New world generation appears farther and farther away, even though large parts of the existing world are no longer part of everyday play.

Memento changes that.

It observes where players spend meaningful time and uses that as a memory signal for what should remain stable. Terrain outside those areas can be replaced with freshly generated terrain, while preserving the places players still care about.

---

## How it works

Memento observes the world over time.

It tracks where players spend meaningful time and uses that as a memory signal.

* Places where players live, build, and return are preserved
* Places outside those areas can become candidates for regeneration

Regeneration means that chunks are reset and generated again using the **current world generation**, including any new terrain features.

Nothing happens abruptly. Changes appear naturally as the world is explored again.

---

## Two ways renewal happens

Natural Renewal is one model with two complementary mechanisms working together.

### Renewal from the outside in

In the outskirts — far from active play — Memento identifies regions that lie outside the areas players meaningfully use.

These regions are **regenerated using the current world generator**.
Old terrain is replaced with newly generated terrain when the area is revisited.

This allows new terrain to appear **within your existing world**, instead of only in unexplored directions.

---

### Renewal guided by you

Sometimes you want more control.

Memento provides simple in-world tools:

* **Witherstone** marks land for renewal
  → chunks in the area will be regenerated once conditions are met

* **Lorestone** protects land
  → chunks in the area will never be regenerated

This lets you deliberately refresh areas, protect builds, or shape how your world evolves.

---

## Understanding what will happen

Memento is designed to be visible and explainable.

At any time, you can inspect:

* what the system knows about the world
* which areas are candidates for regeneration
* what is currently waiting to happen

Use:

* `/memento do scan`
* `/memento explain world`
* `/memento explain renewal`

When you are ready, you can apply regeneration directly:

* `/memento do renew [N]`

Or allow the system to apply regeneration gradually over time:

* `/memento accept`

---

## Getting started

1. Install the mod on your server
2. Run a scan to build initial world knowledge
3. Use `/memento explain renewal` to see which areas can be regenerated
4. Apply regeneration where it makes sense

From there, the system becomes part of how your world evolves.

---

## When this becomes valuable

Memento is most useful when a world has been running for a while.

You start to notice:

* large areas outside where players spend meaningful time
* new terrain appearing far away
* old terrain that no longer reflects current world generation

Memento allows those low-memory areas to be refreshed, without affecting the places players actively use.

---

## In short

Memento keeps your world evolving.

* It regenerates terrain outside the areas players meaningfully use
* It preserves what players care about
* It lets you decide where change happens

---

## Installation

* Minecraft **1.21.10**
* Fabric Loader
* Fabric API
* Kotlin (Fabric Kotlin loader)

Place the mod in your server’s `mods` folder.

No client installation required.

---

## Links

* Source code and releases: [GitHub](../README.md)
* Conceptual model: [RENEWAL_MODEL.md](RENEWAL_MODEL.md)
* Derivation flow: [RENEWAL_PIPELINE.md](RENEWAL_PIPELINE.md)
* Architecture and invariants: [ARCHITECTURE.md](ARCHITECTURE.md)
* Development setup: [DEVELOPMENT.md](DEVELOPMENT.md)
