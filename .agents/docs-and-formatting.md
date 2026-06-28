# Docs And Formatting

Load this file when changing documentation, public APIs, protocol specs, benchmarks, or compiler-generated artifacts.

## Source Documents

- `README.md`
- `CONTRIBUTING.md`
- `docs/DEVELOPMENT.md`
- language guides under `docs/guide/`
- `docs/specification/**`
- `docs/compiler/**`

## Rules

- Do not format Markdown under `tasks/`, including task design, plan, progress, state, history,
  and lessons files. These files are agent working state rather than repository documentation.
- Update the relevant docs under `docs/` when important public APIs change.
- Update `docs/specification/**` when protocol behavior changes.
- Keep examples working and aligned with the current API and protocol behavior.
- Do not use emoji in documentation, including headings, feature lists, status
  tables, callouts, or READMEs. Use plain words such as "Supported" or
  "Unsupported" instead.
- Provide or update working examples when adding new features or materially changing workflows.
- Add migration guidance when a change is breaking or materially changes workflow.
- `docs/DEVELOPMENT.md` plus updates under `docs/guide/` and `docs/benchmarks/` are synced to `apache/fory-site`; other website content should be changed there instead of this repo.
- When benchmark logic, scripts, config, or compared serializers change, rerun the relevant benchmarks and refresh the report and plots under `docs/benchmarks/**`.
- Never manually edit generated code for compiler or IDL outputs; regenerate from the source schema or IDL.
- Do not treat checked-in benchmark reports as canonical for current numbers. Run the active harness or a source-aligned size check, and match schema, config, and mode before comparing runtimes.
- Use portable repo-relative or web links in repository docs, not local filesystem paths. Package readmes rendered by external package managers should link to canonical published docs pages when that is the user-facing context.
- Published `docs/guide/**` pages should prioritize end-user installation and usage flows unless the file is explicitly a contributor or development guide.
- Keep `docs/guide/**` configuration text user-action focused. Do not put cache ownership,
  cold/hot path, publish ordering, exact-local schema bypass, or TypeDef/TypeMeta routing
  invariants there; put implementation invariants in `docs/specification/xlang_implementation_guide.md`,
  security classifications in `docs/security/deserialization.md`, and agent-only workflow rules in
  `.agents/**`.
- Documentation examples should use normal explicit imports, avoid unused imports, and keep common Fory types unqualified where that is the idiom.
- Generated Markdown under `docs/benchmarks/**` should satisfy markdownlint blank-line rules at generation time: no repeated blank lines and no extra blank line after final content.
- Keep default recommendations minimal. Put uncommon optimization or transport patterns in clearly labeled optional sections.
- Before every git commit, run the formatter that owns every changed tracked file. If any formatter rewrites a file, review and stage that formatter output before committing. Do not rely on `git diff --check` alone; CI `Code Style Check` also runs language formatters and fails when they would change code, such as Google Java Format wrapping a changed Java builder chain.

## Formatting Commands

- Markdown: `prettier --write <file>`
- Python code, including `compiler/`, `benchmarks/`, `integration_tests/`, and `python/`:
  `python -m ruff format <changed-python-files>` and
  `python -m ruff check --fix <changed-python-files>`
- JavaScript/TypeScript under `javascript/`: use the package's ESLint-owned formatting path
  (`npm run lint -- --fix` when fixing style, `npm run lint -- --quiet` when checking). Do not run
  Prettier on JavaScript or TypeScript files unless that package has an explicit Prettier config or
  script; otherwise it creates unrelated formatting churn.
- Repo-wide format and lint sweep: `bash ci/format.sh --all`

When code changes touch `compiler/` or `benchmarks/`, format those changed source files with the
same language formatter used by CI. These directories are not documentation-only surfaces; compiler
generators, compiler tests, benchmark harnesses, and benchmark scripts are CI formatter-owned code.
Do not fix only the first formatter diff from a truncated CI log. Extract the full changed-file
surface or run the formatter over every changed file of that language, then verify that no formatter
diff remains.

## Documentation Expectations

- Prefer precise, technically defensible claims over marketing language.
- Keep terminology consistent with the specs and language guides.
- If a protocol or behavior description differs between docs and code, treat that as an issue to resolve before finishing.
- For comparison prose, split broad claims into concrete technical dimensions, mention meaningful exceptions, and avoid absolute competitor or winner/loser framing unless the user explicitly requests it.
