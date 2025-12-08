# Memento: Natural Renewal

**Automatic world renewal guided by simple player-defined memory anchors.**

Memento is a lightweight Fabric server-side mod for Minecraft 1.21.10+ that keeps worlds healthy by selectively regenerating unused or forgotten terrain while preserving areas that players actively care about. Players define “anchors” (places to preserve) and “forgetters” (places to renew), using simple vanilla items with custom NBT. No client mod is required.

---

## Key Features (Current and Planned)

### **Phase 1 (Initial Development)**
- Server-only Fabric mod written in Kotlin.
- Basic `/memento` commands for:
    - Inspecting the system (`/memento info`)
    - Triggering manual forget/keep actions
    - Simulating forgettability scoring
- Logging-based validation of chunk scanning and renewal logic.

### **Phase 2**
- Introduces player-usable **Memento Stones**:
    - *Keeper Stones* (anchors): protect a radius from renewal
    - *Forgetter Stones*: mark a buried location for natural regeneration
- Uses vanilla items with custom NBT (no client-side mod required).
- Stones activate based on placement rules (e.g., pedestal vs. underground).

### **Phase 3**
- Craftable Memento Stones.
- Controlled automated scanning for forgettable chunks.
- Natural world renewal based on player behavior and anchor placement.

### **Phase 4 (Optional)**
- Subtle, server-driven particle hints for areas preparing for renewal.
- Minor client-side enhancements (optional mod).

---

## Goals

- Entirely server-driven mechanics.
- Zero requirement for client mods.
- Minimal visual intrusion.
- Respect player-built structures while allowing natural world regeneration.
- Keep worlds stable and healthy over long-term play.

---

## Requirements

- Java 21 (LTS)
- Kotlin (via Fabric Loom)
- Fabric Loader + Fabric API
- Minecraft 1.21.10 mappings (Yarn)

---

## Development Setup

1. Clone the repository.
2. Open in IntelliJ IDEA.
3. Ensure the project JDK is set to **Java 21**.
4. Run: `./gradlew runServer`
5. Connect with a regular Minecraft client to `localhost`.

---

## License

MIT License. See `LICENSE` for details.

