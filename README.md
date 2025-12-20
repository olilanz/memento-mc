## Memento: Natural Renewal

**Automatic world renewal guided by simple, named memory anchors.**

Memento is a lightweight, server-side Fabric mod for Minecraft 1.21.10+ that helps keep worlds healthy over long-term play. Server operators define *anchors* that either preserve or renew areas of the world. Over time, unused terrain can be safely regenerated while meaningful locations remain untouched.

Memento is entirely server-driven.
No client-side mod is required.

---

## Current State

Memento is under active development.
The current implementation focuses on **establishing a stable command API and anchor model**.

What exists today:

* Server-only Fabric mod (Kotlin)
* Named anchors with explicit semantics:

  * **REMEMBER** — protect an area from renewal
  * **FORGET** — mark an area as eligible for renewal
* Anchors are stored persistently on the server (JSON)
* No automatic renewal yet
* No player items or blocks yet

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

* Anchor names must be unique.
* Re-anchoring the same name replaces the existing anchor.
* Coordinates are optional; if omitted, the player’s current position is used.
* Defaults are applied for radius and days where applicable.

---

## Design Goals

* Server-side only
* Compatible with unmodded vanilla clients
* Minimal intrusion into normal gameplay
* Preserve player-built areas
* Enable controlled, intentional world renewal
* Be understandable and predictable for server operators

---

## Requirements

* **Java 21**
* **Fabric Loader**
* **Fabric API**
* **Minecraft 1.21.10 (Yarn mappings)**

The project uses the **Gradle Wrapper**.
No system-wide Gradle installation is required.

---

## Development Setup (Recommended)

Memento is developed using a **container-based development environment**.
This ensures consistent Java, Gradle, and Fabric behavior across machines.

### Option A — VS Code + Dev Container (Recommended)

This is the preferred setup and matches how the project is developed.

1. Install:

   * VS Code
   * Docker (local or remote, e.g. Unraid)
   * VS Code “Dev Containers” extension
2. Clone the repository
3. Open the repository in VS Code
4. Select **“Reopen in Dev Container”**
5. Run the server:

```
./gradlew runServer
```

6. Connect using a **vanilla Minecraft client** to the server address shown in the logs

This setup:

* Requires no local Java installation
* Uses Java 21 automatically
* Caches Gradle and Minecraft artifacts between runs
* Matches CI-style execution

---

### Option B — IntelliJ IDEA (Local)

IntelliJ is supported for inspection, refactoring, and local runs.

1. Clone the repository
2. Open in IntelliJ IDEA
3. Ensure **Java 21** is configured as the Project SDK
4. Use the Gradle Wrapper (do not rely on system Gradle)
5. Run:

```
./gradlew runServer
```

Note: IntelliJ may mask some Gradle strictness that is enforced in CI or containers.
The dev container setup is considered authoritative.

---

## License

MIT License
