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

- Update the relevant docs under `docs/` when important public APIs change.
- Update `docs/specification/**` when protocol behavior changes.
- Keep examples working and aligned with the current API and protocol behavior.
- Provide or update working examples when adding new features or materially changing workflows.
- Add migration guidance when a change is breaking or materially changes workflow.
- `docs/DEVELOPMENT.md` plus updates under `docs/guide/` and `docs/benchmarks/` are synced to `apache/fory-site`; other website content should be changed there instead of this repo.
- When benchmark logic, scripts, config, or compared serializers change, rerun the relevant benchmarks and refresh the report and plots under `docs/benchmarks/**`.
- Never manually edit generated code for compiler or IDL outputs; regenerate from the source schema or IDL.
- Do not treat checked-in benchmark reports as canonical for current numbers. Run the active harness or a source-aligned size check, and match schema, config, and mode before comparing runtimes.
- Use portable repo-relative or web links in repository docs, not local filesystem paths. Package readmes rendered by external package managers should link to canonical published docs pages when that is the user-facing context.
- Published `docs/guide/**` pages should prioritize end-user installation and usage flows unless the file is explicitly a contributor or development guide.
- Documentation examples should use normal explicit imports, avoid unused imports, and keep common Fory types unqualified where that is the idiom.
- Generated Markdown under `docs/benchmarks/**` should satisfy markdownlint blank-line rules at generation time: no repeated blank lines and no extra blank line after final content.
- Keep default recommendations minimal. Put uncommon optimization or transport patterns in clearly labeled optional sections.

## Formatting Commands

- Markdown: `prettier --write <file>`
- Repo-wide format and lint sweep: `bash ci/format.sh --all`

## Documentation Expectations

- Prefer precise, technically defensible claims over marketing language.
- Keep terminology consistent with the specs and language guides.
- If a protocol or behavior description differs between docs and code, treat that as an issue to resolve before finishing.
- For comparison prose, split broad claims into concrete technical dimensions, mention meaningful exceptions, and avoid absolute competitor or winner/loser framing unless the user explicitly requests it.
