# Bottleneck Playbook

## 1) Dispatch And Runtime Bookkeeping

Symptoms:

- High samples in runtime access/exclusivity or witness dispatch bookkeeping.

Actions:

- Reduce repeated mutable accesses in tight loops.
- Collapse helper layering on hot paths.
- Move costly work from per-field/per-element paths to one-time setup.
- Prefer concrete/local cursor mutation in critical loops.

Avoid:

- API splits that add extra existential/cross-protocol dispatch in hottest generic paths.

## 2) Buffer Growth And Materialization

Symptoms:

- High time in allocation, copy, or final materialization to output buffers.

Actions:

- Grow once for max possible bytes when encoding variable-width fields.
- Use local write cursor and commit once.
- Keep copy boundaries explicit and minimize conversion churn.

Avoid:

- Rewrites that increase allocation count or add copy steps despite lower-level pointer usage.

## 3) Varint Encode/Decode Overhead

Symptoms:

- Repeated size prepass plus repeated encode work for the same value.
- Slow varint branches dominating primitive-heavy structs.

Actions:

- Remove value-dependent prepass when safe by reserving maximum bytes.
- Use packed/loop-based slow paths where appropriate.
- Keep exact writer-index commit after block write.

Avoid:

- Double-checking varint widths per field when one max-size reservation can cover the block.

## 4) Type Resolver And Metadata Path

Symptoms:

- Heavy cost in compatible type-info lookup, parsing, or temporary wrappers.

Actions:

- Keep canonical type info ownership in resolver/context aligned with reference runtimes.
- Cache by stable protocol keys (for example, headers), not benchmark payload identity.
- Reduce redundant wrappers and duplicated metadata ownership.

Avoid:

- Side caches that leak abstractions to callsites (`push/pop/clear` bookkeeping in user-facing flow).

## 5) Context Reset And Map/Array Maintenance

Symptoms:

- Noticeable time in context reset, map clear, array churn, or cache maintenance.

Actions:

- Use O(1) reset for reusable containers.
- Keep data structures cache-local and simple for hot-path operations.
- Remove dead fields/methods quickly after refactors.

Avoid:

- Over-engineered multi-path caches unless proven necessary and mirrored by reference runtimes.

## 6) Compatible Schema Read/Write Flow

Symptoms:

- Large compatible-path overhead or regressions after cleanup.

Actions:

- Keep flow aligned with C++/Rust ownership and dispatch model.
- Move expensive matching/validation to type-info parse stage when possible.
- Keep typed scoping of pending compatible metadata to avoid nested decode corruption.

Avoid:

- Untyped global compatible slots.
- Broad helper-shaped replacement paths that bypass established protocol flow.

## 7) Cleanup-Driven Regressions

Symptoms:

- API/abstraction cleanup causes throughput drop.

Actions:

- Keep cleanup only if in benchmark noise band or user explicitly accepts tradeoff.
- Redesign inside the cleaned architecture to recover performance.

Avoid:

- Reverting to banned legacy shapes.
- Preserving cleanup that harms hot paths without follow-up recovery plan.

## 8) Cross-Language Porting

Actions:

- Identify the exact structure in the reference runtime (owner, cache key, lifetime, loop shape).
- Port behavior and data-flow model, not language syntax.
- Verify xlang semantics after porting.

Avoid:

- Language-specific shortcuts that diverge from shared protocol/runtime concepts.

## Keep/Revert Rubric

Keep when:

- Improvement is repeatable and non-trivial.
- Correctness/lint/tests remain green.
- Complexity increase is justified by measured gain.

Revert when:

- Regression is clear or gain is noise.
- Change introduces benchmark-only behavior.
- Change violates explicit user constraints.
