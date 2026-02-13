# Architecture Lock Draft — Driver / Scanner / WorldMap Refactor

## Scope

- Refactor responsibility constellation across driver, scanner, and world map.
- Adopt ambient chunk load model.
- Keep existing component names for now; revisit naming after behavioral refactor lands.

## User-confirmed decisions

1. Explicit invariant override is accepted for this refactor.
2. Driver serves renewal demand and ambient chunk load handling.
3. Driver and scanner emit metadata facts; they do not propagate chunk objects to world map.
4. Domain-owned world map lifecycle is owned by a dedicated domain service.
5. Scanner active run ends at completion, then remains passive for ambient metadata enrichment.
6. Expiry without fully loaded chunk is not emitted to world map.
7. Component rename is postponed until after behavior is stabilized.

## Component boundaries and ownership

### Domain: WorldMapService (new)

- Owns authoritative world map instance lifecycle from server start to server stop.
- Owns ingestion contract for metadata facts from infrastructure publishers.
- Owns map mutation authority and map read APIs for domain computations.
- Owns subscription surfaces for downstream domain consumers.

### Domain: WorldMementoMap

- Remains authoritative memory substrate.
- Stores chunk metadata facts and scan provenance.
- Does not depend on infrastructure runtime types.

### Infrastructure: ChunkLoadDriver

- Always active from server start.
- Owns engine chunk lifecycle subscriptions and renewal load demand execution.
- Tracks load lifecycle until fully loaded state.
- Emits metadata facts only after full-load availability.
- Handles ambient chunk loads as an input stream and emits metadata facts.

### Infrastructure: WorldScanner

- Operator-initiated active file scan orchestration only.
- During active scan, emits metadata facts from file/runtime extraction.
- After completion, no active file orchestration; remains passive ambient metadata listener behavior.
- No pressure management responsibility.

## Lifecycle and state model

### Driver lifecycle

- Start: server started.
- Active states:
  - observing engine signals
  - handling renewal demand loads
  - handling ambient chunk load observations
- Emits metadata fact only when chunk reaches fully loaded and metadata extraction succeeds.
- Stop: server stopping, subscriptions detached.

### Scanner lifecycle

- Idle passive from server start.
- Active run starts by operator command.
- Active run performs file-primary scan and emits metadata facts.
- Completion closes active run lifecycle.
- Returns to passive ambient enrichment mode.

### WorldMapService lifecycle

- Instantiate once at server start.
- Subscribe to driver and scanner metadata streams.
- Apply metadata facts on engine tick thread.
- Expose authoritative read model continuously.
- Dispose at server stop.

## Authority, thread, and event boundaries

- Minecraft remains authority for chunk lifecycle.
- Driver remains sole authority for engine chunk event subscription.
- Scanner authority is limited to scan-run orchestration and metadata extraction.
- WorldMap mutation authority is domain-only via WorldMapService.
- Metadata propagation contract is event/fact-based, never chunk-object propagation across boundary.
- Ingestion and map mutation occur on server tick thread.
- Off-thread file reads remain scanner infrastructure internals; only facts cross boundaries.

## Metadata propagation contract

- Fact includes:
  - chunk key
  - provenance
  - scan tick
  - metadata payload
  - unresolved reason optional
- Driver fact emission precondition: fully loaded chunk available.
- Scanner fact emission: file-primary or runtime-derived metadata during active run; passive ambient enrichment after run.
- Expiry events without full load are not mapped into world map facts.

## Dependency graph and sequencing constraints

### Dependency graph

- Driver -> MetadataFactPublisher -> Domain WorldMapService -> WorldMementoMap
- Scanner -> MetadataFactPublisher -> Domain WorldMapService -> WorldMementoMap
- Renewal providers -> Driver
- Renewal domain consumers -> WorldMapService read surfaces

### Structural ordering constraints

1. Introduce domain WorldMapService and ingestion contract first.
2. Route scanner metadata writes through WorldMapService.
3. Route driver propagation through metadata facts instead of chunk object callbacks.
4. Remove direct scanner ownership of map lifecycle.
5. Finalize startup wiring in bootstrap.
6. Update architecture document and ADR entries after behavior matches.

## Invariant fit against architecture memory

This refactor intentionally overrides current invariant statements in architecture memory where they currently state:

- driver serves renewal demand only
- scanner exclusively owns scan orchestration semantics currently including convergence ownership details

Preserved invariants:

- Minecraft lifecycle authority remains intact.
- No forced unload.
- Detection vs execution separation remains explicit.
- No hidden scheduler introduced.
- WorldMap remains authoritative memory.
- Partial knowledge remains valid.

Changed invariants to encode explicitly:

- Driver handles renewal demand plus ambient load observation-to-metadata emission.
- Scanner owns operator-triggered scan-run orchestration and metadata emission; pressure management removed.
- Domain WorldMapService owns authoritative map lifecycle and ingestion authority.

## Architectural smell acknowledgement

Accepted smell:

- Driver extracts and emits metadata instead of propagating chunk object.

Why accepted now:

- Symmetric publisher model between driver and scanner.
- Simplified domain ingestion boundary.
- Stronger domain ownership of authoritative world memory.

Smell containment:

- Keep extraction scope minimal and explicit.
- Keep driver metadata extraction contract stable and testable.
- Defer naming and deeper decomposition until post-refactor stabilization.

## Observability surfaces

- Driver lifecycle transitions to fully loaded metadata emission path.
- Driver ambient-vs-renewal source attribution for emitted facts.
- Scanner active run start/progress/complete and passive mode state.
- WorldMapService ingestion throughput and rejected/duplicate fact signals.
- Integration boundary counters for fact publication and application.

## Re-open conditions

Re-open architecture lock if any of the following occurs:

1. Need to emit expiry outcomes into world map despite current decision.
2. Need scanner to remain perpetually active post-completion.
3. Need driver to expose chunk object downstream again.
4. WorldMap mutation is required outside domain service authority.
5. Thread-affinity guarantees cannot be maintained on tick thread for ingestion.

## Rename direction status

- Decision: keep current names for now.
- Revisit after behavioral refactor and stabilization.

## Next mode recommendation

- Architecture is lock-ready pending explicit user approval of this draft.
- Recommended next step after approval: switch to Orchestrator mode for delivery-plan lock and chunked implementation sequencing.
