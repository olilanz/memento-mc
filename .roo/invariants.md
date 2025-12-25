# Architectural Invariants (Non-Negotiable)

Invariant 1 — No Forced Chunk Unloads  
Invariant 2 — Deferred Renewal Only  
Invariant 3 — One RenewalBatch per Witherstone  
Invariant 4 — Server Controls Chunk Lifecycle  

These invariants must NEVER be removed, simplified, or merged.

If refactoring obscures these invariants, it is incorrect.
