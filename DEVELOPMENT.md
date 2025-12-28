# Development Environment Guide

## 1. Purpose of This Document

This document explains **how to set up and run a development environment** for the Natural Renewal mod.

It covers:
- the technical stack the project is built on
- the supported development environments
- how to build, run, and debug the mod locally

It does **not** describe system architecture, design intent, or coding practices.
Those are documented separately.

---

## 2. Tech Stack (Authoritative)

Natural Renewal is developed against the following technical baseline:

- Minecraft: 1.21.10
- Mod Loader: Fabric
- Mappings: Yarn
- Language: Kotlin
- Java: As defined by the Gradle toolchain (container-provided)
- Build Tool: Gradle (via Gradle Wrapper)

All versions used during development and validation are defined by the repository
and enforced by the canonical development environment.

---

## 3. Requirements

To work on this project, you need:

- Git
- Docker (required for the canonical setup)
- One of the following IDEs:
  - VS Code (recommended)
  - IntelliJ IDEA (supported)

No local installation of Java, Gradle, Fabric, or Minecraft tooling is required
when using the canonical setup.

---

## 4. Canonical Development Environment (Recommended)

The canonical development environment for this project is:

- VS Code
- Dev Containers
- Docker-based toolchain

This setup:

- Works on Windows, macOS, and Linux
- Requires no local Java or build tool installation
- Ensures consistent toolchain versions across platforms
- Is used for development and validation

For best compatibility and developer experience, this setup is recommended.

---

## 5. Bootstrapping the Canonical Environment

1. Install Docker
2. Install VS Code
3. Open the repository in VS Code
4. When prompted, choose **Reopen in Dev Container**
5. Wait for the container to build
6. Verify the setup by running the Gradle build
7. Run the development server using the Gradle run task

If the build succeeds and the server starts, the environment is correctly configured.

---

## 6. Running and Debugging

### Build

Run the Gradle build task to compile and validate the project.

---

### Run

Start a local Fabric server using the Gradle run task.

---

### Debug

Start the server with JVM debugging enabled and attach a debugger to port 5005.

In VS Code, this is typically done via an attach configuration.
You may see a “Debug Anyway” prompt when attaching to a long-running task.
This is expected.

---

## 7. Alternative Development Setups

The following setups are supported but are not the canonical reference.

### VS Code (without Dev Container)

- Requires manual installation of Java and build tooling
- Toolchain versions must match the Tech Stack section
- Behavior may differ from the canonical environment

---

### IntelliJ IDEA

- Import the project as a Gradle project
- Use Gradle tasks to build and run the server
- Debug by enabling JVM debug and attaching as needed

Manual toolchain alignment is required.

---

## 8. Ports and Networking

The development environment uses the following ports:

- Minecraft server: 25565
- JVM debug (JDWP): 5005

The dev container forwards required ports automatically.

---

## 9. Related Documentation

- README.md — user-facing overview
- ARCHITECTURE.md — system design and invariants

