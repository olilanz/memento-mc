# DEVELOPMENT.md

## Memento — Development Guide

This document explains **how to work on the Memento codebase as a developer**.

It focuses on:

* daily development workflow
* tooling expectations
* IDE-specific behavior
* dev container–based development

It does **not** explain the system architecture itself.

For architecture, invariants, and design intent, refer to:

👉 **`ARCHITECTURE.md`** (authoritative)

---

## Development Philosophy

Memento is developed with a strong emphasis on:

* explicit lifecycles
* guarded state transitions
* low cognitive load
* conservative refactoring
* behavior preservation over cleverness

The codebase intentionally avoids “magic” tooling behavior.

If something happens, it should be obvious *why* it happens —
even if that means a small amount of manual interaction.

---

## Recommended Development Environment

### Primary path: Dev Containers (recommended)

Development is designed around **VS Code Dev Containers**.

The repository contains a preconfigured dev container that provides:

* JDK (matching Minecraft / Fabric requirements)
* Gradle
* Kotlin tooling
* Fabric Loom compatibility
* Debug support (JDWP)
* Stable, reproducible environment

**You are not expected to install runtime dependencies manually.**
That is the purpose of the dev container.

> If the dev container builds, the environment is correct.

---

## Bootstrapping (minimal)

1. Open the repository in VS Code
2. Reopen in Dev Container when prompted
3. Wait for the container to finish building
4. Verify with:

```bash
./gradlew build
```

If this succeeds, you are ready to work.

---

## Build, Run, Debug — Canonical Workflow

### Build (sanity check)

**Keyboard**

* `Ctrl + Shift + B`

**Command**

```bash
./gradlew build
```

This is the **primary integrity check** during refactoring.

A clean build does **not** prove correctness —
but a failing build means you should stop immediately.

---

### Run + Attach (default development flow)

During development, the **default action is “Run + Attach”**.

This means:

* the server is started via Gradle
* debugging is enabled
* the debugger attaches automatically

**Keyboard (recommended)**

* **`F5`**

This uses a VS Code *compound debug configuration* that:

1. Starts the Gradle task

   ```bash
   ./gradlew runServer --debug-jvm
   ```
2. Attaches the debugger to the JVM on port **5005**

---

### Expected VS Code behavior (“Debug Anyway”)

When pressing **F5**, VS Code will typically show a dialog:

> **Waiting for preLaunchTask ‘Gradle: runServer (debug)’…**
> **[ Debug Anyway ]**

This happens because:

* `runServer` is a **long-running task by design**
* VS Code expects preLaunchTasks to terminate
* Minecraft servers never do

👉 **Click “Debug Anyway”. This is expected and correct.**

We explicitly accept this behavior because:

* it is honest about process lifecycles
* it avoids fragile background-task heuristics
* it keeps Gradle in control of runtime
* it works reliably across containers and platforms

This dialog is **not an error** and does **not** indicate misconfiguration.

---

### Manual run (optional)

You may also start the server manually:

```bash
./gradlew runServer --debug-jvm
```

And then attach the debugger via:

* Run & Debug tab → **Attach to Fabric Server**
* or press **F5** (if attach is selected)

---

## Why Attach-Based Debugging Is Used

* Gradle + Fabric is not a simple Java `main()`
* Launch-based debugging is fragile with wrappers
* Attaching avoids IDE assumptions
* Long-running servers benefit from late attachment

Attach-based debugging:

* does not change runtime behavior
* does not require restarts
* is reliable across containers and platforms

---

## VS Code–Specific Notes

### Tasks

* **Build** is implemented as a VS Code task
* `Ctrl + Shift + B` is mapped to `./gradlew build`
* Server startup is owned by Gradle, not the debugger

This keeps responsibilities explicit:

* tasks run processes
* the debugger only attaches

---

### Debug Ports and Dev Containers

* Minecraft server: **25565**
* JVM debug (JDWP): **5005**

The dev container configuration:

* forwards Minecraft automatically
* suppresses browser prompts for the debug port
* allows debugger attachment without noise

If you see references to port 5005 in VS Code UI, that is normal.

---

## IntelliJ IDEA Notes (Optional)

The project also works in IntelliJ IDEA, but it is **not the primary target**.

Typical IntelliJ setup:

* Import as Gradle project
* Use the `runServer` Gradle task to start the server
* Enable **Debug JVM** for that task
* Attach debugger as usual

Be aware:

* IntelliJ tends to hide lifecycle details
* VS Code + tasks is more explicit and predictable
* The dev container workflow is VS Code–centric

If using IntelliJ, prefer **explicit Gradle tasks** over IDE-run configurations.

---

## Refactoring Discipline (Important)

When working on Memento:

* Do not mix refactoring with feature development
* Preserve behavior unless explicitly agreed otherwise
* Prefer explicit state and lifecycle over abstraction
* Avoid speculative “cleanup”
* When in doubt, assume existing complexity is intentional

During architectural refactoring:

* old and new implementations may coexist
* shadow implementations are allowed
* legacy code is removed **only after feature parity**

---

## When Things Feel “Too Manual”

That is intentional.

Manual steps:

* force awareness of lifecycle
* prevent hidden IDE behavior
* reduce accidental coupling
* make refactoring safer

If something feels awkward, question it —
but do not “automate it away” without understanding the cost.

---

## Final References

* **Architecture & invariants:** `ARCHITECTURE.md`
* **Development workflow:** this file
* **Build authority:** `./gradlew build`
* **Runtime authority:** Gradle tasks

---

### Final note

This setup favors **correctness and clarity over convenience**.

If you ever wonder *why* something behaves the way it does,
the answer should be visible — not hidden behind tooling magic.
