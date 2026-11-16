# Mozilla tourist qualification corpus v1

A frozen source-only qualification fixture for approved plan
`docs/plans/2026-11-14-2248-mozilla-translation-engine.md`, revision 2,
Task 6 and Appendix A. It contains **96 measured cases**: one request, one
negation and one price/time case for each of the 32 direct English-hub legs.
There are 25 base-memory legs (75 cases) and seven explicitly selected tiny
legs (21 cases): VI→EN and EN↔ID/TR/HI. This is not all-pair or linguistic
certification. No candidate output was read during authorship.

## Files and frozen contract

- `cases.jsonl`: UTF-8/LF JSON objects with exactly `id`, `source`, `target`,
  `text`. Give a translator only this text and language pair, using its
  stateless current-utterance API. No history or reference translation is needed.
- `meaning-expectations.jsonl`: one host-only English assessment object per
  measured ID, using `essential_propositions`, `uncertainty`, and `judgment`.
  Never supply this file to the engine, its prompts, or decoding prefixes.
- `warmup.jsonl`: exactly **32 optional, unmeasured short warm-ups**, one per
  hub direction, with `warmup: true`. Their IDs and exact source strings are
  disjoint from measured cases. Use only when a warmed protocol needs them;
  never add them to the 96-case denominator, quality scores or measured times.
  These are minimal direction coverage, not an arbitrary additional batch.
- `manifest.json`: version, provenance, source-language notes, per-leg
  architecture and counts, and exact row/byte/SHA-256 inventory. Its inventory
  pins all three JSONL payloads, this README and the validator. It does not
  hash itself; the validation command reports its own SHA-256 separately.
- `validate.py`: deterministic, standard-library-only, read-only validator.
  It never freezes/reformats inputs, runs models or writes output files.

All source strings are independently authored short tourist turns (at most
200 UTF-16 units), not target-language references for another row. The
`moz1-SOURCE-TARGET-CATEGORY` IDs are permanent; the category is `request`,
`negation` or `price-time`. Change an input or proposition only in a new corpus
version, not by replacing these bytes. Preserve UTF-8 without BOM, LF and a
final newline. Existing `test-data/.gitattributes` uses `* -text` and already
prevents Git newline conversion; no additional registration is needed.

## Source-governed meaning review

Assess essential meaning, not exact wording or substrings. Requests and
questions must not become claims that a booking, price, time or permission
has been confirmed. Preserve negation scope, agency, quantities, currency,
per-person/per-item versus total basis, and temporal qualifiers. Natural
paraphrases, appropriate politeness and equivalent number/time notation may
be valid. Do not infer an allergy from an ingredient preference, a calendar
date from today/tomorrow, a time zone, a country, a speaker gender, or other
facts absent from the source.

`uncertainty` explicitly records recognized source ambiguities and missing
context. An empty list means only that the AI author identified no additional
case-specific ambiguity; it is not confidence certification. If the source
and expectations appear to disagree, the source governs: retain the output,
flag the case and mark the judgment uncertain. Never invent meaning to force
a pass. These expectations are host assessment guidance, not executable
semantic assertions, target translations, goldens or medical safety advice.

All 17 app language IDs appear as sources: en, vi, es, id, it, zh, ko, de,
pl, pt, ru, th, tr, fr, hi, ja, nl. App `zh` means Simplified Chinese and maps
only to upstream `zh-Hans`. Portuguese is not scored against a mandated
regional dialect. Arabic is not in this catalog.

## Provenance, reuse and limits

The source turns, warm-ups and English propositions were authored with AI
assistance on 2026-11-15 for this qualification slice before this author
observed candidate outputs. They are synthetic, not real conversations,
mined benchmarks or model-generated target answers. Direction-specific
scenarios were composed individually; the set is not a parallel reference
corpus. Prior v1 sources were inspected for conventions and exact-source
separation, not copied. Prior candidate outputs were not inspected.

The original `../v1` remains unchanged. Its 48 in-catalog measured rows are
separate, previously used **regression** meaning evidence; its eight Arabic
rows are explicitly out of catalog, not failed or silently omitted. The 32
v1 RU↔EN/RU↔ZH rows are the separate comparative speed set. Do not add any of
these rows to the fresh 96-case denominator. This new set is fresh relative
to this authorship/observation boundary, not proof of absence from training
data. Once evaluated, subsequent uses are regression, not unseen holdout.

No model output, exact golden, audio, device run, runtime result, human
review or native-speaker acceptance is included or claimed. AI authorship
can contain language or interpretation errors; the bounded three cases per
leg cannot establish general dialogue reliability, safety or all 272 pairs.
The synthetic data and helper use the repository Apache-2.0 license;
third-party model licenses do not become the corpus license.

## Read-only verification

From the repository root, run exactly:

```text
python -B test-data/corpus/translation/mozilla-v1/validate.py
```

The command checks frozen bytes, UTF-8/LF, strict row schemas, stable unique
IDs, 96 one-to-one host expectations, 32 complete legs and category coverage,
all 17 source languages, 25/7 architecture coverage, 32 disjoint warm-ups,
and pinned v1 payloads with 48 in-catalog / eight Arabic regression rows.
It checks exact source separation, not semantic independence or translation
quality. Successful stdout includes all input hashes and a manifest hash.
The final implementation handoff records that exact command and observed
result; command output is evidence, not a claim of linguistic certification.
