# Review Checklist

## 1. Scope And Diff Setup

- Identify the exact review target: `apache/main...HEAD`, PR branch, commit range, or file subset.
- Run `git diff --stat` before deep inspection.
- If the review baseline is `apache/main`, refresh it first with `git fetch apache main`.

## 2. Protocol And Xlang

- Did the patch touch `docs/specification/**`, `TypeMeta`, `TypeInfo`, type IDs, ref flags, schema evolution, or xlang header/state?
- Did the author update every affected runtime, or explicitly prove why the other runtimes are already aligned?
- Does the change preserve the intended boundary: xlang changed only where required, native unchanged unless explicitly requested?
- Are cross-language tests required but missing?
- Does the implementation follow current spec text instead of a remembered older behavior?

## 3. Runtime Ownership And API Shape

- Does the patch move ownership to the right layer (`TypeResolver`, `ReadContext`, `WriteContext`, buffer/stream owner) instead of bloating `Fory` facades?
- Does it introduce wrappers, carrier objects, pending-state stacks, or side caches that do not exist in the reference runtimes without clear evidence?
- Does it reintroduce removed APIs, aliases, or compatibility shims against explicit direction?
- Are there new one-line forwarding helpers that should be inlined?
- Are mode booleans or mode-specific parameter types leaking into hot shared paths?

## 4. Performance And Benchmarks

- Does the patch optimize the runtime rather than the benchmark harness?
- Does it depend on repeated payload identity, root-only shortcuts, or fixture-specific behavior?
- Were benchmark comparisons run sequentially, not concurrently?
- If benchmark logic/reporting changed, were markdown reports and plots under `docs/benchmarks/**` refreshed?
- Are throughput and size comparisons apples-to-apples across languages and serializer modes?

## 5. Tests And Verification

- Are tests in the right subsystem instead of whichever suite was convenient?
- Are there targeted regressions tests for the changed behavior?
- If protocol or xlang behavior changed, is the relevant xlang matrix called out?
- If performance-sensitive code changed, is there benchmark or regression evidence?
- Do the reported commands match the touched language/module?

## 6. Docs And Public Claims

- Are README, guides, specs, or benchmark docs required by the code change?
- Do the docs use precise terminology and current semantics?
- Are tables, captions, and summaries consistent with the body text?
- Are canonical links and references present where the patch depends on prior docs or published articles?

## 7. Review Output

- Findings first.
- Order by severity.
- Include file/line references.
- If no findings, still state residual risk or testing gaps.
