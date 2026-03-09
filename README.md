# Memento -- Natural Renewal

**Intentional world renewal through remembering and forgetting.**

Memento is a lightweight, server-side Fabric mod for Minecraft that
helps long‑lived worlds evolve safely over time --- without wiping the
world or interrupting gameplay.

Vanilla clients are fully compatible.

------------------------------------------------------------------------

## The problem

Minecraft worlds never forget.

Over time, long‑running servers accumulate vast areas of old terrain
that no one uses anymore.\
New world‑generation features appear farther and farther away. World
upgrades rarely affect the places that matter.

Memento enables **deliberate renewal**.

Not by wiping the world.\
Not by forcing change.\
But by distinguishing between land that still matters --- and land that
has been left behind.

------------------------------------------------------------------------

## The core idea: remembering and forgetting

Not all land deserves to last forever.

Memento models a simple principle:

-   **Memorable land**\
    Areas where players live, return to, or spend meaningful time ---
    including their surroundings.

-   **Forgettable land**\
    Areas that were explored briefly, mined temporarily, or abandoned
    long ago.

Forgettable land is allowed to fade.
Only after that fading can renewal occur.

The result:

-   Stability where it matters
-   Fresh terrain where it doesn't

------------------------------------------------------------------------

## Natural Renewal uses two complementary mechanisms.

These mechanisms complement each other: Natural Renewal can operate autonomously in forgotten outskirts, while stones allow operators to guide renewal or protect areas closer to where players live.

Natural Renewal is one operator-facing model with two distinct mechanisms
working together.

### 1. Region-based renewal in forgettable outskirts

Projection identifies forgettable regions based on world observation and activity history, and proposes conservative
region-pruning actions.

It:

-   Works gradually from the outskirts inward
-   Never touches protected or memorable land
-   Suggests what *could* renew
-   Does nothing automatically

The operator remains in full control of when renewal happens.

------------------------------------------------------------------------

### 2. Stone-guided chunk renewal (explicit intent)

Stones allow operators to guide renewal near memorable places, and stones
can also be applied in outskirts when desired.

-   **Witherstone** --- marks land for renewal over time\
-   **Lorestone** --- protects land permanently

Stones express intent.

They influence the system but do not replace the autonomous model.
They never force immediate regeneration.
Renewal still occurs only when chunks unload naturally.

------------------------------------------------------------------------

## Nothing happens automatically

Memento does not run unattended world wipes.

It:

-   Suggests renewal candidates
-   Explains what it is waiting for
-   Executes renewal only when the operator explicitly triggers it

You remain in control of when change occurs.

------------------------------------------------------------------------

## What you see in game

Memento is designed to be explainable and visible.

-   Placing a stone briefly shows its area of influence with particle
    effects
-   Areas waiting for renewal glow subtly
-   Protected areas can be visualized again at any time

If you need clarity:

-   `/memento explain` shows system status
-   `/memento explain renewal` shows proposed renewal actions and
    waiting conditions
-   `/memento explain stones` shows stone state
-   `/memento visualize` replays visual area indicators

The system tells you what it is waiting for.

------------------------------------------------------------------------

# Practical Workflows

## Workflow A --- Deliberate area renewal with stones

1.  Protect what must never change\
    `/memento add lorestone <name> [radius]`

2.  Mark land for renewal\
    `/memento add witherstone <name> [radius] [daysToMaturity]`

3.  Let the Witherstone mature\
    (in‑game days pass)

4.  Inspect status\
    `/memento explain renewal`

5.  Execute renewal deliberately\
    `/memento do renew [N]`

6.  Leave the area so chunks can unload naturally\
    (move away, log out/in, or restart the server if needed)

Renewal completes when the world reloads the affected chunks.

------------------------------------------------------------------------

## Workflow B --- Gradual world pruning from the outskirts

1.  Scan the world\
    `/memento do scan`

    Active scan is single-pass and conservative.\
    Unresolved files are reported explicitly and can be reconciled by
    rerunning the command.

2.  Inspect world knowledge\
    `/memento explain world`

3.  Inspect renewal proposals\
    `/memento explain renewal`

4.  Execute controlled renewal\
    `/memento do renew [N]`

Natural Renewal in this workflow uses the region-based mechanism in
forgettable outskirts.\
The operator decides when to apply it.

------------------------------------------------------------------------

## Core Commands (Operator ≥ 2)

### Stones

-   `/memento add witherstone <name> [radius] [daysToMaturity]`
-   `/memento add lorestone <name> [radius]`
-   `/memento remove witherstone <name>`
-   `/memento remove lorestone <name>`
-   `/memento alter witherstone <name> radius <value>`
-   `/memento alter witherstone <name> daysToMaturity <value>`
-   `/memento alter lorestone <name> radius <value>`

### Inspection

-   `/memento explain`
-   `/memento explain world`
-   `/memento explain stones`
-   `/memento explain renewal`
-   `/memento visualize`

### Execution

-   `/memento do scan`
-   `/memento do renew [N]`

------------------------------------------------------------------------

## Safety and compatibility

-   Server-side only
-   Vanilla clients fully compatible
-   No forced chunk unloads
-   No partial regeneration
-   Deterministic and explainable behavior
-   Compatible with world-generation mods

Protection dominance is absolute.
Renewal only occurs on abandoned land.

------------------------------------------------------------------------

## Installation

1.  Install Fabric Loader for your Minecraft version
2.  Place the mod JAR in your server's `mods` folder
3.  Start the server

No client installation required.

------------------------------------------------------------------------

## Further documentation

-   `RENEWAL_MODEL.md` --- in-world explanation of memory and
    forgetting
-   `ARCHITECTURE.md` --- internal design and invariants
-   `DEVELOPMENT.md` --- build and development environment
