# Memento — Natural Renewal

Memento is a server-side Fabric mod for Minecraft that enables **controlled renewal of long-lived worlds**.

This repository contains the source code, architecture, and development setup for the mod.

Prebuilt releases and installation files are published on Modrinth:
https://modrinth.com/mod/memento-natural-renewal/

---

## What problem this solves

Minecraft worlds accumulate terrain outside the regions players meaningfully use.

* New world generation appears farther and farther away from active regions
* Old terrain remains unchanged indefinitely
* Servers either expand endlessly or require disruptive resets

Memento introduces a controlled alternative:

* Preserve areas that are still relevant
* Allow areas outside those regions to be renewed over time
* Keep the world evolving without wiping it

Renewal is conservative and operator-controlled.

---

## Installation (server)

1. Install Fabric Loader for Minecraft **1.21.10**
2. Ensure Fabric API is available
3. Place the Memento mod JAR in the server `mods` folder
4. Start the server

No client installation is required.

For downloads and version compatibility, use Modrinth:
https://modrinth.com/mod/memento-natural-renewal/

---

## Prerequisites

* Minecraft: **1.21.10**
* Mod loader: **Fabric**
* Fabric API
* Kotlin support (Fabric Kotlin loader)

---

## Repository contents

* [`doc/MODRINTH.md`](doc/MODRINTH.md)
  User-facing documentation and usage

* [`doc/RENEWAL_MODEL.md`](doc/RENEWAL_MODEL.md)
  Conceptual model (memory, forgettability, renewal)

* [`doc/RENEWAL_PIPELINE.md`](doc/RENEWAL_PIPELINE.md)
  How world data is processed into renewal decisions

* [`doc/ARCHITECTURE.md`](doc/ARCHITECTURE.md)
  System design, invariants, and responsibilities

* [`doc/DEVELOPMENT.md`](doc/DEVELOPMENT.md)
  Development environment and tooling

---

## Build and test

The project uses Gradle.

Build and run tests:

```id="b1a9k2"
./gradlew build
```

Run tests only:

```id="c8p4zm"
./gradlew test
```

Run a local development server:

```id="r7q2nd"
./gradlew runServer
```

For full setup, debugging, and environment details, see:
`doc/DEVELOPMENT.md`

---

## Notes

* Server-side only (vanilla clients supported)
* Does not override Minecraft chunk lifecycle
* Renewal occurs only when chunks unload naturally

---

## License

See repository for details.
