# Lesson-Derived Red Flags

## Protocol Drift

- One runtime changed, but the same xlang behavior exists in other runtimes.
- Helper names were unified, but signatures still hard-code mode-specific types.
- The patch adds explicit `if (xlang)` branching where the protocol is shared.
- Compatible-mode or type-mapping semantics are described from memory instead of current spec text.

## Benchmark Methodology Problems

- Benchmark-only config flags or shortcuts bypass real runtime work.
- Serializer-specific model conversion happens inside timed loops without being called out.
- Before/after results were gathered from parallel benchmark runs on one host.
- Reports or plots were not regenerated after benchmark logic changed.
- Conclusions are drawn from smoke settings or noisy single-shot runs.

## API And Architecture Drift

- Removed APIs come back as aliases or shims.
- Thin wrappers and pass-through helpers accumulate instead of being deleted.
- Public API grows to fix an internal bug.
- Language-local wrapper types, pending-state stacks, or side caches appear without matching reference-runtime concepts.
- Wrapper layers retain ownership that should live in resolver/context/buffer types.

## Streaming And Buffer Risks

- Two objects appear to own one reader/writer index.
- Stream bind or rebind paths do not detach stale backlinks.
- Flush behavior depends on implicit side effects instead of one explicit owner.
- Review comments assume `(0, nil)` from a socket reader must be fatal immediately.

## Tests And Verification Gaps

- Tests live in the wrong package or language suite for the behavior under review.
- Protocol or xlang changes lack the required cross-language test coverage.
- Performance-sensitive changes have no benchmark or regression evidence.
- Claimed cleanup is not backed by a full-tree search or exact command output.

## Docs And Claim Precision

- Language counts or support coverage are phrased as a fixed closed set.
- Broad competitive claims are not split into concrete technical dimensions.
- Titles or summaries overstate what the body proves.
- Package-facing docs point to the wrong canonical destination.
