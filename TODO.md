# TODO

## Deferred edge cases

1. Freshness vs scan timing
- Inhabitance may change after scan read.
- Investigate re-reading loaded chunk metadata before commit/export.

2. Inhabitance field / density-based buffering
- Explore replacing binary memorability with:
  - density of inhabited chunks
  - amplitude of inhabitance
  - spatial radiation
- Goal: larger buffers around settlements, smaller around transient paths.