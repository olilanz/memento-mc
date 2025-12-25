# Refactoring Rules

Allowed:
- renaming for semantic clarity
- encapsulation without logic change
- adding explicit state enums
- adding comments explaining WHY code exists

Forbidden:
- deleting triggers “because unused”
- merging Witherstone and RenewalBatch lifecycles
- collapsing multi-step flows into single functions
- reordering event registration

Refactoring must be incremental and compile-clean at every step.
