# Engineering Requirements

Before completing any implementation, ensure that the code can confidently satisfy all of the following requirements:

1. The code must be easy to maintain and extend.
2. The code must withstand a sudden thousandfold increase in load.
3. The code must be secure.

## Design Principles

Use SOLID as criteria for evaluating a design, not as a reason
to increase the number of classes, interfaces, or layers:

- Separate components by their reasons to change; do not mix independent
  responsibilities. A single responsibility does not mean a single method.
- Create extension points for confirmed variations in behavior,
  not hypothetical future requirements.
- Implementations of a shared contract must preserve its behavior,
  invariants, and guarantees, including error behavior.
- Keep interfaces narrow and aligned with their consumers' needs;
  do not force consumers to depend on capabilities they do not use.
- Keep product rules independent of UI, storage, and external service
  details. Introduce abstractions where they protect a meaningful boundary.

Prefer the simplest design that satisfies the requirements.
Every new abstraction must solve a concrete problem.

## Repository Android Skills

Android-specific skills may be installed locally under `.agents/skills/`, which
is ignored by Git and not supplied with the repository. When using an installed
skill, read its relevant `SKILL.md` before applying its workflow.

Skills supplement the project's development and review workflow; they do not
authorize unrelated changes. Missing prerequisites are not permission to migrate
navigation, add a DI framework, upgrade dependencies, or adopt experimental APIs.

## Domain Language

The domain thesaurus is `docs/THESAURUS.md` (format 2.0), versioned with the
repository. Its English terms map to existing code identifiers; definitions are
reconstructed from code. Maintain it only through the `ubiquitous-language` skill.

- Before naming a domain-bearing class, method, field, variable, API, configuration
  key, file, or directory, search the thesaurus for each candidate and the existing
  name (`rg -n -i '<word>' docs/THESAURUS.md`, or the available search tool).
- An Index line gives the canonical `Identifier`; use it even when the matched
  name is under `avoid:`. Derive casing and compound names without synonyms.
- Forbidden (`use:`) and Legacy (`→`) lines give replacements for new names.
  Unresolved entries require a decision; do not silently choose a winner.
- For a genuinely new domain concept, first add an alphabetically sorted Index
  line and a matching Terms entry with a definition. Reuse or compose existing
  concepts before inventing another term.
- Do not infer deprecation or bounded contexts from spelling or module boundaries.
  Infrastructure roles and conventional local variables are not new domain terms.

## Repository Language

Write new repository files, documentation, comments and identifiers in English,
regardless of the conversation language. Preserve intentional localized UI text
and language-specific test inputs. Do not rewrite the thesaurus outside its
dedicated maintenance skill.
