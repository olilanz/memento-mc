# Memento: Natural Renewal — Design Intention

Minecraft worlds accumulate history.

Players explore, build, abandon, return, and move on. Over time, terrain that no longer matters remains loaded, stored, and carried forward indefinitely. Memento is built around a simple idea:

**Worlds should remember what matters — and gently let go of what doesn’t.**

Memento introduces *anchors*: explicit markers defined by server operators that describe intent.

- Some places are meant to be **remembered**.
- Some places are safe to **forget**.
- Everything else is allowed to age naturally.

This is not destruction.  
It is maintenance.

Memento does not attempt to guess player intent.  
It does not scan builds, analyze structures, or make subjective decisions.

Instead, it provides a clear language:

- *Anchor this place.*
- *Release that place.*
- *Let the rest evolve.*

All mechanics are server-side.  
Players using vanilla clients remain fully compatible.

Memento aims to be invisible when nothing is happening —  
and explicit, deliberate, and understandable when it is.
