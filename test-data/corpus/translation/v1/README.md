# Tourist translation corpus v1

A frozen, model-independent text fixture for offline translation comparisons and future regression tests. It contains 56 measured source cases across 10 directed language pairs, with separate semantic expectations and 10 disjoint warm-up cases. It does not contain audio.

## Files and format

All files use UTF-8 and LF. `manifest.json` records exact byte counts, SHA-256 hashes, direction counts and provenance. The existing `test-data/.gitattributes` preserves these bytes.

- `cases.jsonl`: one JSON object per line with `id`, `source`, `target`, and `text`. IDs are stable. `zh` means Mainland Simplified Chinese; `ar` means Modern Standard Arabic.
- `meaning-expectations.jsonl`: one object per measured ID with `essential_propositions` in English and a `judgment` limitation. These are host-side assessment guidance, not reference translations or machine-enforced assertions.
- `warmup.jsonl`: unmeasured input objects, with IDs disjoint from the measured cases. Never include their times or outputs in measured-case statistics.
- `manifest.json`: this text fixture's own inventory, not the speech evaluator's audio `CorpusManifest` schema.

Cases cover transport, accommodation, restaurants, shopping, directions and practical requests. They deliberately exercise negation, prices, dates/times, names, quantities, allergy statements and who performs an action. Some inputs are longer compound requests, not only phrasebook sentences. This does not establish medical or other professional translation safety.

## Reuse

1. Verify inventory hashes, unique IDs, direction counts and the one-to-one mapping between cases and semantic expectations.
2. Give the translator only source text and the language pair. Do not include semantic expectations or a reference answer in its prompt or decoding prefix.
3. Preserve raw outputs, failures, model/tokenizer/runtime identities and full-completion timing. Treat truncation and failed inference explicitly.
4. Assess preserved meaning, not exact wording or substring matches. Natural paraphrases and equivalent transliterations can be valid. Flag ambiguity or uncertain judgments rather than manufacturing a pass. The expectations have not received native-speaker acceptance in every language.
5. Keep model-generated translations as versioned evaluation results, not automatic golden answers. A future human-reviewed reference set should state its provenance and review status separately.

This corpus was used to compare models: future runs are regression checks, not evaluation on an unseen benchmark. Do not tune models on these cases and then describe their scores as held-out quality. Keep v1 identities and bytes stable; publish a new version for changed cases or expectations.

## Provenance and license

The synthetic cases, warm-ups and semantic expectations were authored with AI assistance and frozen before examining SMaLL-100, NLLB or Tencent outputs. No real user conversation or model-generated target translation was used as a reference. The corpus is covered by the repository's Apache-2.0 license; third-party model licenses do not become the corpus license.
