# Memento: Natural Renewal

**Automatic world renewal guided by simple, named memory anchors.**

Memento is a lightweight, server-side Fabric mod for Minecraft 1.21.10+ that helps keep worlds healthy over long-term play. Server operators define *anchors* that either preserve or renew areas of the world. Over time, unused terrain can be safely regenerated while meaningful locations remain untouched.

Memento is entirely server-driven.  
No client-side mod is required.

---

## Current State

Memento is under active development.  
The current implementation focuses on **establishing a stable command API and anchor model**.

What exists today:
- Server-only Fabric mod (Kotlin)
- Named anchors with explicit semantics:
  - **REMEMBER** — protect an area from renewal
  - **FORGET** — mark an area as eligible for renewal
- Anchors are stored persistently on the server (JSON)
- No automatic renewal yet
- No player items or blocks yet

---

## Command Overview

```
/memento anchor remember <name> [radius] [x y z]
/memento anchor forget <name> [radius] [x y z] [days]
/memento release <name>
/memento list
/memento info
```


Notes:
- Anchor names must be unique.
- Re-anchoring the same name replaces the existing anchor.
- Coordinates are optional; if omitted, the player’s current position is used.
- Defaults are applied for radius and days where applicable.

---

## Design Goals

- Server-side only
- Compatible with unmodded vanilla clients
- Minimal intrusion into normal gameplay
- Preserve player-built areas
- Enable controlled, intentional world renewal
- Be understandable and predictable for server operators

---

## Requirements

- Java 21
- Fabric Loader
- Fabric API
- Minecraft 1.21.10 (Yarn mappings)

---

## Development Setup

1. Clone the repository
2. Open in IntelliJ IDEA
3. Set Project SDK to **Java 21**
4. Run the server:

```
./gradlew runServer
```

5. Connect using a vanilla Minecraft client

---

## License

MIT License


