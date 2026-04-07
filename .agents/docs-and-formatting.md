# Docs And Formatting

Load this file when changing documentation, public APIs, protocol specs, benchmarks, or compiler-generated artifacts.

## Source Documents

- `README.md`
- `CONTRIBUTING.md`
- `docs/guide/DEVELOPMENT.md`
- language guides under `docs/guide/`
- `docs/specification/**`
- `docs/compiler/**`

## Rules

- Update the relevant docs under `docs/` when important public APIs change.
- Update `docs/specification/**` when protocol behavior changes.
- Keep examples working and aligned with the current API and protocol behavior.
- Provide or update working examples when adding new features or materially changing workflows.
- Add migration guidance when a change is breaking or materially changes workflow.
- Updates under `docs/guide/` and `docs/benchmarks/` are synced to `apache/fory-site`; other website content should be changed there instead of this repo.
- When benchmark logic, scripts, config, or compared serializers change, rerun the relevant benchmarks and refresh the report and plots under `docs/benchmarks/**`.
- Never manually edit generated code for compiler or IDL outputs; regenerate from the source schema or IDL.

## Formatting Commands

- Markdown: `prettier --write <file>`
- Repo-wide format and lint sweep: `bash ci/format.sh --all`

## Documentation Expectations

- Prefer precise, technically defensible claims over marketing language.
- Keep terminology consistent with the specs and language guides.
- If a protocol or behavior description differs between docs and code, treat that as an issue to resolve before finishing.
