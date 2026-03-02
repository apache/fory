# AI Contribution Policy

Apache Fory is a performance-critical foundational serialization framework with cross-language compatibility requirements.
AI tools are welcome as assistants, but project quality, legal safety, and maintainability standards are unchanged.

The key words MUST, MUST NOT, REQUIRED, SHOULD, and MAY are interpreted as described in RFC 2119.

## 1. Core Principle

- AI tools MAY assist contribution work.
- AI tools MUST NOT replace contributor accountability.
- The human submitter is responsible for correctness, safety, performance, and maintainability of all submitted changes.
- License/provenance confirmation: contributors MUST confirm submitted material is legally compatible and traceable, and MUST comply with [ASF Generative Tooling Guidance](https://www.apache.org/legal/generative-tooling.html).
- AI-assisted code MUST be reviewed carefully by the contributor line by line before submission.
- Contributors MUST be able to explain and defend design and implementation details during review.

## 2. Disclosure (Privacy-Safe)

For substantial AI assistance, PR descriptions MUST include a short `AI Usage Disclosure` section.
For minor or narrow AI assistance, full disclosure is not required.

Definition of substantial AI assistance:

- Substantial means AI materially influenced technical content, not only writing style.
- Contributors MUST mark AI assistance as substantial (`yes`) if any of the following apply:
  - AI generated or rewrote non-trivial code/test logic (even for a small change or a single function).
  - AI-generated or AI-refactored content is about 20 or more added/changed lines in aggregate.
  - AI materially influenced API, protocol, type mapping, performance, memory, or architecture decisions.
  - AI produced substantive technical text used in PR rationale (beyond grammar/translation cleanup).
- Contributors MAY mark substantial AI assistance as `no` for minor or narrow assistance only, such as spelling/grammar fixes, formatting, trivial comment wording edits, or other non-technical edits with no behavior impact.

Required disclosure fields:

- Whether substantial AI assistance was used (`yes` or `no`)
- Scope of assistance (for example: design drafting, code drafting, refactor suggestions, tests, docs)
- Affected files or subsystems (high-level)
- Human verification performed (checks run locally or in CI, and results reviewed by the contributor)
- Provenance and license confirmation (see Section 6)

Standard disclosure template (recommended):

```text
AI Usage Disclosure
- substantial_ai_assistance: yes
- scope: <design drafting | code drafting | refactor suggestions | tests | docs | other>
- affected_files_or_subsystems: <high-level paths/modules>
- human_verification: <checks run locally or in CI + pass/fail summary + contributor reviewed results>
- performance_verification: <N/A or benchmark/regression evidence summary>
- provenance_license_confirmation: <Apache-2.0-compatible provenance confirmed; no incompatible third-party code introduced>
```

To protect privacy and enterprise security:

- Contributors are NOT required to disclose model names, provider names, private prompts, or internal workflow details.
- Maintainers MAY request additional clarification only when necessary for legal or technical review.

## 3. Human Communication Requirements

The following MUST be human-authored (translation and grammar correction tools are acceptable):

- Review responses
- Design rationale and tradeoff discussion
- Risk analysis and production impact explanation

Generated filler text, evasive responses, or content that does not reflect contributor understanding may result in PR closure.

## 4. Scope Alignment Before Implementation

For non-trivial changes (especially architecture/protocol/performance-sensitive work), contributors SHOULD align scope in an issue or discussion before implementation.

This expectation applies to all contributors, whether AI-assisted or not.
For AI-assisted non-trivial work without prior alignment, maintainers MAY request scope alignment before continuing review.

## 5. Verification Requirements

Every AI-assisted PR MUST provide verifiable evidence of local or CI validation:

Definition of adequate human verification:

- The contributor personally runs the relevant checks locally or in project CI and reviews the results.
- Verification includes concrete evidence (exact commands and pass/fail outcomes), not only claims.
- Verification covers the changed behavior with targeted tests where applicable.
- For protocol or performance-sensitive changes, verification includes the required compatibility tests and/or benchmark/regression evidence.

- Confirmation that contributor performed line-by-line self-review of AI-assisted code changes
- Build/lint/test checks run locally or in CI
- Targeted tests for changed behavior
- Results summary (pass/fail and relevant environment context)

Additional REQUIRED checks for Fory-critical paths:

- Protocol, type mapping, or wire-format changes:
  - Update relevant docs under `docs/specification/**`
  - Add or update cross-language compatibility tests where applicable
- Performance-sensitive changes (serialization/deserialization hot paths, memory allocation behavior, codegen, buffer logic):
  - Provide benchmark or regression evidence
  - Justify any measurable performance or allocation impact

Claims without evidence may be treated as incomplete.

## 6. Licensing, Copyright, and Provenance

Contributors MUST follow ASF legal guidance and project licensing policy, including:

- [ASF Generative Tooling Guidance](https://www.apache.org/legal/generative-tooling.html)
- ASF third-party licensing requirements
- Apache-2.0 compatibility obligations

Contributors MUST ensure:

- No incompatible third-party code is introduced
- Reused material has compatible licensing and required attribution
- AI-generated output does not include unauthorized copyrighted fragments

If provenance is uncertain, contributors MUST remove or replace the material before submission.
Maintainers MAY request provenance clarification when needed.

## 7. Quality Gate and Non-Acceptance Conditions

Maintainers MAY close or return PRs that materially fail project standards, including:

- Contributor cannot explain key implementation logic
- Missing required disclosure for substantial AI assistance
- Missing or inadequate human verification evidence for changed behavior
- Redundant implementation of existing utilities without clear necessity
- Introduction of dead code, unused helpers, or placeholder abstractions without justification
- Protocol or performance claims without reproducible evidence
- Large unfocused changes with unclear scope or ownership

This is not a ban on AI usage; it is a quality and maintainability gate.

## 8. Review and Enforcement Process

Before merge, maintainers MAY request:

- Additional tests, benchmarks, or spec updates
- PR split into smaller verifiable commits
- Clarification of technical rationale, provenance, or licensing
- Rework of sections that do not meet standards

Maintainers MAY close PRs that remain non-compliant after feedback.

Any long-term contribution restrictions MUST follow Apache project governance and community process, and SHOULD be documented with clear rationale.

## 9. Contributor Checklist (for AI-Assisted PRs)

- [ ] I can explain and defend all important changes.
- [ ] I reviewed AI-assisted code line by line before submission.
- [ ] I provided `AI Usage Disclosure` without exposing private/internal details.
- [ ] I ran relevant local build/lint/test checks and reported outcomes.
- [ ] I added/updated tests and specs where required.
- [ ] I validated protocol/performance impacts with evidence when applicable.
- [ ] I verified licensing and provenance compliance.

## 10. Governance Note

This policy complements, but does not replace, existing ASF and Apache Fory governance, contribution, and legal policies.
If conflicts arise, ASF legal and project governance rules take precedence.
