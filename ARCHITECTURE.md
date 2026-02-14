# Memento — Architecture

This document is **institutional memory** for Memento’s architecture.

It records architectural intent, responsibility boundaries, and hard‑won decisions so that future maintainers do not have to rediscover them through failure. The focus is on **what must remain true**, not on explaining the code line‑by‑line.

This document is not user‑facing. Gameplay concepts, player experience, and operator semantics are covered in [README.md](README.md) and [RENEWAL_MODEL.md](RENEWAL_MODEL.md). Development setup and workflows are covered in [DEVELOPMENT.md](DEVELOPMENT.md). Where those documents already define concepts, this document references them instead of repeating them.

Architectural decisions are referenced by **ADR number**. ADRs are part of the contract: they explain *why* the architecture looks the way it does.

---

## 1. Mental model: two interacting mechanisms

Memento is built around **two distinct but interacting mechanisms**:

1. **Player‑driven, time‑based in‑game mechanics**
2. **Autonomous, conservative world renewal**

These mechanisms serve different purposes and have different guarantees, but they operate on the same physical substrate: **Minecraft chunks**.

The player‑driven side exists to express *intent*. Stones allow players and operators to guide renewal or explicitly protect land. These mechanics are visible, explainable, and grounded in gameplay.

The autonomous side exists to preserve long‑term world health. It observes the world gradually, tolerates incomplete information, and acts conservatively. Its goal is not speed or completeness, but **eventual, safe progress**.

Neither mechanism fully controls the other. Player intent influences autonomous renewal, but does not force it. Autonomous renewal remains cautious even in the presence of explicit guidance. This separation is foundational and is reinforced throughout the architecture (ADR‑001).

The **scanner** exists to bridge these mechanisms. It observes the world over time and builds durable knowledge that enables renewal decisions and later forgettability computation. Observation is explicitly decoupled from execution.

```mermaid
flowchart LR
    Player[Player actions]
    StoneAuthority[Stone authority]
    StoneMap[Stone map]
    Scanner[World scanner]
    WorldMap[WorldMap]
    Renewal[Autonomous renewal]

    Player --> StoneAuthority
    StoneAuthority -->|lifecycle events| Renewal
    StoneAuthority -->|projection input| StoneMap
    StoneMap -->|dominant influence| Renewal
    Scanner -->|observations| WorldMap
    WorldMap --> Renewal
```

---

## 2. Player‑driven mechanics (in‑game layer)

The player‑driven layer is responsible for **expressing intent**, not for executing change.

Stones are designed to be understandable in‑world artifacts. They mature over time, produce visible effects, and can be inspected by operators. Their role is to say *what should eventually happen*, not *when or how it happens*.

### Witherstone — intent to renew

A Witherstone expresses explicit intent that an area may renew. It matures over time and, once mature, produces exactly one `RenewalBatch`. That batch has a clear lifecycle and ownership and is never reused (ADR‑005).

The Witherstone itself does not load chunks, does not trigger regeneration, and does not bypass engine constraints. It merely establishes eligibility.

### Lorestone — intent to protect

A Lorestone expresses protection. It marks land as non‑renewable regardless of natural forgettability or Witherstone influence.

Lorestones have no automatic lifecycle and are never consumed implicitly. Their purpose is to give operators a durable, conservative override without weakening the autonomous model.

Player‑driven mechanics deliberately **do not**:

* scan chunks
* load chunks
* schedule renewal

Those responsibilities belong to the autonomous layer.

---

## 3. Autonomous, conservative renewal (system layer)

Autonomous renewal is designed to operate safely in long‑running servers with incomplete information and unpredictable player behavior.

It prefers inaction over action. It tolerates missing data. It avoids holding unnecessary runtime state. These properties are not optimizations; they are safeguards derived from repeated failure modes.

### Detection versus execution

Memento strictly separates **detection** from **execution** (ADR‑002).

Detection answers questions such as:

* Which chunks are known?
* Which chunks are eligible for renewal?

Execution answers a different question:

* When does the world allow change without disruption?

Detection does not depend on chunk load state. Execution is deferred until chunks unload and reload naturally. Renewal never forces chunk unloads or reloads.

### Server authority

Minecraft remains authoritative over chunk lifecycle, scheduling, and world generation (ADR‑003).

Memento does not implement its own world generation and does not override engine lifecycle rules. All renewal is opportunistic and engine‑mediated.

---

## 4. Shared core: chunks, observation, and state

Both mechanisms operate on chunks, but **chunk loading is a shared, scarce resource** in a modded ecosystem.

Other mods may load chunks for their own purposes. Memento must coexist with them rather than compete. For this reason, scanning deliberately piggybacks on unsolicited chunk loads and avoids aggressive scheduling (ADR‑009).

### Events as the domain boundary

All interaction between runtime boundaries and domain logic happens through **typed domain events and typed metadata facts** (ADR‑006, ADR‑013).

Boundary doctrine is explicit:

* Engine callbacks are treated as boundary signals, not as direct mutation points.
* Scanner and Driver publish metadata facts into domain-owned ingestion.
* Stone lifecycle transitions are emitted as typed domain events.
* Renewal-side recomputation is event-driven and consumes dominance from `StoneMapService`.

No semantic decisions are made inside engine threads.

```mermaid
sequenceDiagram
    Engine ->> Driver: chunk load observed
    Driver ->> Queue: record event
    Queue ->> Domain: process on server tick
```

This decoupling prevents concurrency bugs and makes progress observable and explainable.

---

## 5. Core components and responsibilities

### Stone authority (`StoneAuthority`)

Stone authority owns stone register lifecycle semantics:

* placement and removal
* maturity progression and lifecycle transitions
* persistence of stone definitions

It does not own factual world memory and does not own scanner orchestration.

### Stone map (`StoneMapService`)

`StoneMapService` is the sole influence-projection authority for dominant stone resolution at chunk granularity (ADR‑014).

It projects from stone authority state and provides read surfaces for consumers such as renewal and world-map overlays. No other component may infer dominance independently.

### Renewal engine

Renewal consumes world facts from `WorldMapService` and stone influence from `StoneMapService`.

Renewal eligibility derivation is owned on the renewal side and must use `StoneMapService` for dominant-stone lookup rather than re-deriving stone influence internally.

### WorldMap

`WorldMap` is Memento’s **institutional memory**. It records what the system has observed about the world.

It is monotonic in meaning: once a chunk is known or observed, that knowledge is not forgotten. Missing or partial metadata is a valid state, not an error.

There is no separate scan plan. **The map is the plan** (ADR‑009).

### WorldMapService

`WorldMapService` is the **domain lifecycle and mutation authority** for `WorldMap`.

It owns:

* startup and shutdown lifecycle of the authoritative map instance
* ingestion of metadata facts from infrastructure publishers
* tick-thread application of metadata facts into `WorldMap`

No infrastructure component mutates `WorldMap` directly.

### World scanner

The scanner is an infrastructure component that owns **filesystem discovery reconciliation and completion** for `/memento scan`.

Active scan is file-primary and two-pass. Chunk metadata is read from region/NBT files off-thread and emitted as metadata facts into `WorldMapService` for tick-thread application. The scanner does not emit proactive engine demand.

A chunk is considered scanned once file observation has occurred, regardless of metadata completeness. Chunks unresolved after the two-pass file scan remain recorded as unresolved in the WorldMap and completion aggregates; active scan still completes.

After completion, scanner behavior is passive/reactive only: unsolicited engine observations can still enrich map metadata over time through metadata-fact ingestion, but no active demand path is opened.

### Chunk Load Driver

The Chunk Load Driver encapsulates all interaction with the Minecraft engine’s chunk lifecycle (ADR‑008).

It executes renewal load requests, observes engine signals including ambient load activity, and emits metadata facts when chunks are safely accessible. It does not decide *what* to load.

There is no internal scheduler. Load pacing relies on Minecraft’s own scheduling and on observed latency (ADR‑010).

For scanning doctrine, the driver has no scanner-demand responsibility. It serves renewal demand and ambient observation pathways, and it does not emit map facts for expiry outcomes that never reached full-load accessibility.

```mermaid
stateDiagram-v2
    [*] --> Requested
    Requested --> Loading
    Loading --> Observed
    Observed --> FullyLoaded
    FullyLoaded --> Propagated
```

### Visualization

Visualization is server‑side, vanilla, and observational only (ADR‑018, ADR‑019).

Visual effects explain what the system is doing, but they never influence decisions or control flow.

---

## 6. Illustrative flows

```mermaid
flowchart TB
    FileSystem --> Scanner
    Scanner --> WorldMapService
    StoneAuthority --> StoneMap
    StoneMap --> Renewal
    Driver --> Engine
    Engine --> Driver
    Driver --> WorldMapService
    WorldMapService --> WorldMap
    WorldMap --> Renewal
```

The important aspect is directionality: intent flows downward, observations flow upward, and no component shortcuts these paths.

---

## 7. Architectural invariants (locks)

The following properties must remain true:

* Minecraft owns chunk lifecycle authority
* No forced chunk unloads
* Detection is separated from execution
* Scanner owns filesystem scan orchestration and reconciliation for active runs
* Driver owns engine execution for renewal demand and ambient load observation handling
* Scanner does not generate proactive engine demand
* No central orchestrator
* No internal load scheduler
* Piggyback unsolicited loads
* WorldMap is authoritative memory
* WorldMapService is the sole world map lifecycle and mutation authority
* Stone authority owns stone placement lifecycle and persistence
* StoneMapService is the sole dominant-stone projection authority
* Renewal and overlays consume StoneMapService for dominance lookup
* Scanner and Driver publish boundary-safe metadata facts, not chunk runtime objects
* Driver emits world map metadata facts only when full-load accessibility is reached
* Expiry outcomes without full-load accessibility do not mutate WorldMap
* Partial knowledge is valid
* Active scan may complete with unresolved leftovers recorded explicitly
* Observability must explain stalling and progress

Violating these invariants requires an explicit architectural decision.

---

## 8. Notes on extensions

The scanner and WorldMap form the foundation for later forgettability computation and analysis. Algorithmic details are intentionally excluded from this document.

---

## 9. Architectural Decision Records (ADRs)

### ADR‑001: Two renewal mechanisms coexist

Two renewal mechanisms exist: player‑driven stones and autonomous renewal. Separating them stabilizes semantics and preserves player agency.
→ Collapsing them caused renewal to become reactive and brittle.

### ADR‑002: Detection vs execution

Detection observes eligibility; execution applies change when the world allows it.
→ Coupling them caused cascading side effects and irrecoverable partial progress.

### ADR‑003: Server authority over chunk lifecycle

Minecraft owns chunk lifecycle and scheduling.
→ Overriding this caused instability and engine conflicts.

### ADR‑004: StoneTopology is sole influence authority (superseded by ADR‑014)

All stone influence resolution flows through StoneTopology.
→ Duplicate logic drifted and produced conflicting outcomes.

### ADR‑014: Split stone lifecycle authority and stone influence projection authority (superseded by ADR‑015)

Stone lifecycle ownership remains in stone authority (implementation name at decision time: `StoneTopology`) for placement, removal, maturity transitions, and persistence.

Dominant-stone projection at chunk granularity is owned solely by `StoneMapService`.

Renewal-side derivation consumes `StoneMapService` dominance and must not re-derive influence independently.

Class renaming to `StoneAuthority` is deferred until functional parity validation is complete.
→ This split preserves lifecycle clarity while removing projection duplication risk across renewal and overlays.

### ADR‑015: Naming alignment — lifecycle authority uses `StoneAuthority`

After parity verification, the lifecycle authority naming was updated from `StoneTopology` to `StoneAuthority` across code references and integration hooks.

This ADR changes naming only. Ownership boundaries, lifecycle behavior, dominance semantics, and renewal derivation rules remain unchanged.

### ADR‑005: One Witherstone → one RenewalBatch

Each Witherstone produces exactly one RenewalBatch.
→ Reuse or multiplexing destroyed lifecycle clarity.

### ADR‑006: Typed domain events as boundary

Engine interaction is mediated through typed events.
→ Direct callbacks leaked threading assumptions into the domain.

### ADR‑007: Engine‑mediated scanning (superseded by ADR‑012)

Scanning reacts to engine availability.
→ Aggressive scheduling caused load storms.

### ADR‑008: Shared Chunk Load Driver

All engine interaction is encapsulated in the driver.
→ Bypassing it reintroduced concurrency bugs.

### ADR‑009: WorldMap replaces scan plans

The map itself is the plan.
→ Separate plans drifted from reality.

### ADR‑010: Adaptive pacing over fixed modes

Pacing is based on observed latency.
→ Static modes failed under real load.

### ADR‑011: Scan completion allows unresolved file leftovers

Active scan completion is defined by completion of file-primary two-pass processing and queue drain, not by full metadata success for every chunk. Unresolved leftovers remain first-class WorldMap state and completion telemetry.
→ Treating unresolved leftovers as non-terminal blocked scan closure despite explicit best-effort semantics.

### ADR‑012: Filesystem-primary scanning with no scanner demand

Active scanning is driven by filesystem region/NBT reads, not by scanner-issued engine demand. The engine remains a passive observation source only for unsolicited loads.
→ Scanner demand paths coupled detection to engine pressure and increased orchestration complexity.

### ADR‑013: Domain WorldMapService and metadata-fact ingestion boundary

World map lifecycle and mutation authority are centralized in domain `WorldMapService`. Scanner and Driver are infrastructure publishers that emit boundary-safe metadata facts; they do not propagate chunk runtime objects across the boundary.

Driver serves renewal demand plus ambient load observation handling, and emits world map facts only when full-load accessibility is reached. Expiry outcomes without full-load accessibility remain lifecycle signals and do not mutate world map state.

→ Direct scanner-owned map lifecycle and chunk-object propagation blurred authority and thread boundaries, increasing coupling and reducing explainability.
