# Mozilla, LMT and NLLB: Android emulator comparison

> **Evidence availability — 2026-11-16:** Local cleanup removed the experiment's raw evidence, preparation assets and helper scripts from `.cache/translator-evaluation/`. References to retained files below describe their status at the time of the experiment, not current availability. The recorded results, failures and limitations remain unchanged, but the deleted files can no longer be inspected or used to recompute these results from this checkout.

## Scope and historical evidence

Measured on 2026-11-14, 20:10:46–20:26:16 UTC. This is an **engine-only x86_64 Android emulator comparison**, not an app/ASR/TTS, ARM-phone or Firefox benchmark. No production engine was added or selected. No application install, commit or push was performed for this experiment.

The emulator has four logical CPUs, 16-KiB pages and the Android build recorded in the frozen metadata. Each engine runs alone, CPU-only, with a four-thread/worker budget and one request at a time. Mozilla uses four sentence workers, not four-way intra-operation GEMM for a short sentence; all four graph replicas for the required route are initialized during load. LMT uses a community Q4_K_M GGUF with the exact official prompt, greedy decoding and a 256-token cap. NLLB uses the pinned CT2 4.9.0 INT8_FLOAT32 conversion, one replica, four threads, beam 1 and a 256-token cap including the target prefix. These are different runtimes/kernels and lossy representations, not an architecture-only or equal-quality comparison. LMT's upstream beam-5/512-token example is not the tested configuration.

Sources: [Mozilla inference](https://github.com/mozilla/translations/tree/main/inference), [LMT-60-0.6B](https://huggingface.co/NiuTrans/LMT-60-0.6B), [NLLB-200 distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M). Mozilla model packs are MPL-2.0, LMT is Apache-2.0, and NLLB weights are CC-BY-NC-4.0; this report does not change their obligations. The LMT quantization is third-party, and its exact original conversion revision is undisclosed.

Local raw evidence was stored at `.cache/translator-evaluation/modern-mt/runs/emulator-20261114-main/`, an ignored local cache rather than versioned benchmark data. It included `metadata.json` (all commands, model file inventories and device identity), `all-rows.jsonl`, each block's input/output/exit/transport/memory files, `analysis.json`, `execution-receipt.json`, and `quality/`. Preparation/source/patch/license inventories were stored in the sibling experiment directories `mozilla/` and `lmt/`; NLLB reused the unchanged earlier prepared assets. The independent focused audit was stored separately in `.cache/translator-evaluation/modern-mt/measurement-review.md`. These files were removed in the cleanup noted above.

The preserved [tourist corpus](../../test-data/corpus/translation/v1/) has 56 unique source cases in ten directions. Each case was measured three times in counterbalanced model order: 12 unique sources per RU→EN/EN→RU direction and four per other direction, yielding 36 or 12 timing observations. Each directed-pair process first attempts its disjoint excluded warm-up. There are also five fresh-process first-call observations per model. OS storage caches were not dropped. Meaning expectations stay on the host and never enter model prompts.

## Actual execution outcome

**The main runner exited 1, not 0.** All 105 scheduled blocks and 714 raw rows were retained: 105 startup, 15 cold-call, 90 excluded warm-up and 504 measured translation rows. Transport/evidence errors: **0**. All 504 measured calls completed without declared inference failure or truncation. That establishes output completion, not semantic correctness.

Three excluded **LMT EN→RU warm-ups** reached the 256-token cap without EOS, once per pass. They are preserved in `inference-failures.json`; those three blocks have explicit native/shell exits 2/2, while the other 102 have 0/0. No case was replaced, retried or removed, and the cap was not increased to make the run pass. EN→RU timing is therefore after a truncated warm-up attempt, not after a successfully completed warm-up translation.

Excluded qualification also verified actual direct and two-hop Mozilla inference, native missing-model/empty-input errors, output-cap failures, and 66 exact LMT prompt/token fixtures on the emulator. Mozilla's initial host preflight exited 1 on exact comparison of float32 0.009999999776482582 with 0.01; corrected receipt-only validation passed without repeating inference. This host assertion failure and the unrelated main-run warm-up failures remain explicitly recorded.

## Complete-translation latency

Milliseconds, **P50 / P95**, nearest-rank over repeated cases, after the excluded warm-up attempt. Timers include prompt/tokenization, native computation and final decoding; Mozilla pivot includes both hops. They exclude ADB, JSONL file writing and startup/load. P95 is not a long-sentence-only statistic. Repeated observations are not independent sentences or population-quality estimates.

| Direction | Mozilla | LMT Q4_K_M | NLLB INT8 |
| --- | ---: | ---: | ---: |
| RU→EN | 20 / 43 | 1735 / 4331 | 1053 / 3353 |
| EN→RU | 20 / 43 | 1610 / 5027 | 852 / 3933 |
| EN→ES | 19 / 34 | 1564 / 3748 | 1474 / 2168 |
| ES→EN | 20 / 39 | 1347 / 3382 | 1130 / 2537 |
| EN→ZH | 18 / 45 | 1203 / 2625 | 846 / 3291 |
| ZH→EN | 27 / 45 | 1387 / 3022 | 1548 / 3028 |
| RU→ZH, Mozilla via EN | 38 / 86 | 1058 / 3227 | 882 / 2946 |
| ZH→RU, Mozilla via EN | 52 / 169 | 1452 / 4252 | 1113 / 2006 |
| EN→AR | 24 / 40 | 1854 / 4074 | 1210 / 3281 |
| AR→EN | 27 / 45 | 1542 / 4275 | 874 / 3723 |

Mozilla is the speed leader in every tested direction. The **nearest-rank P50 of per-source paired** NLLB/Mozilla time ratios is 55.93× for RU→EN and 60.18× for EN→RU. Each ratio divides the three-pass NLLB source median by the corresponding Mozilla source median; these are not ratios of table percentiles or conventional even-sample medians averaging the middle two ratios. LMT is slower than NLLB on every tested RU→EN/EN→RU source; this does not establish performance of another quantization, beam policy or ARM kernel. Tencent was not rerun in this cohort, so no fresh paired Tencent speed factor is claimed.

## Fresh-process first-call costs

Five individual observations per model, milliseconds rounded to nearest integer. The input is the same first short RU→EN pharmacy case. Load and first translation remain distinct; constructor is separately retained in raw startup rows. Combined engine cost excludes process launch/ADB and is not app startup. These are process-cold, not storage-cache-cold measurements.

| Model | Load, five observations | First full translation, five observations | Constructor + load + first-call range |
| --- | --- | --- | ---: |
| Mozilla | 406, 345, 347, 313, 341 | 20, 35, 19, 18, 26 | 331–426 |
| LMT | 402, 480, 437, 408, 496 | 925, 937, 883, 966, 883 | 1320–1418 |
| NLLB | 860, 787, 952, 1018, 890 | 757, 801, 873, 880, 693 | 1583–1898 |

## Model data and sampled memory

Model data is the actual local input inventory, not an APK size or a claimed minimal deployment. Native executables are separate. Mozilla's eight direct packs cover EN↔RU/ES/ZH/AR; the two RU↔ZH routes reuse them through English. Models for further languages were not downloaded or measured.

| Model | Prepared model data | Highest sampled PSS in RU→EN warm blocks | Highest sampled PSS across all blocks |
| --- | ---: | ---: | ---: |
| Mozilla | 324.91 MB for all eight directions; RU↔EN subdirectories 72.16 MB | 362 MiB | 886 MiB, ZH→RU pivot |
| LMT | 484.22 MB GGUF | 970 MiB | 970 MiB |
| NLLB | 656.53 MB prepared directory; model.bin alone 622.60 MB | 1003 MiB | 1003 MiB |

MB means decimal bytes; MiB means 1,048,576 bytes. PSS/RSS are sampled from the recorded engine PID every approximately 0.2 seconds, not exact peaks or whole-conversation memory. All 105 blocks have readable samples. Short Mozilla cold blocks can miss loaded memory: for example `cold-3-mozilla` sampled maximum RSS 237,076 KiB while the process's after-load lifetime peak RSS was 399,360 KiB. Kernel `ru_maxrss` is retained separately and must not be relabeled PSS. Four eager Mozilla graph replicas and both pivot packs are included; this is not a tuned single-worker product footprint.

## Directly inspected meaning regressions

Completion and speed do not establish tourist reliability. Examples checked against the raw source/output pairs:

- **Mozilla, EN→RU (`en-ru-08`):** “3 December to 5 December, not until 6 December” becomes “с 3 декабря по 5 декабря, не раньше 6 декабря”, reversing the exclusion of December 6.
- **Mozilla, ZH→RU pivot (`zh-ru-04`):** 80 yuan becomes “80 долларов”; the explanation about not ordering off-menu items is also distorted.
- **LMT, EN→RU (`en-ru-12`):** stroller permission becomes “разрешено ли нам сиденье на кошке”, and the direct-bus condition is omitted.
- **NLLB, RU→EN (`ru-en-07`):** the passport-not-lost sentence is present, but the following hotel-safe explanation is omitted. `ru-en-09` similarly loses the request to resolve a duplicate parking charge before departure.
- **Allergy boundary (`en-ru-05`):** Mozilla translates shellfish as “моллюсков”, potentially narrowing coverage by excluding crustaceans; NLLB says “раковины” and omits the clean-pan request. LMT uses the broader “морепродукты” but weakens the pan wording. These are model-assisted linguistic concerns, not medical certification.

None of the three can be called error-free or guaranteed safe. Broad coverage and especially Mozilla's non-English pivot routes require pair-specific quality assessment, rather than treating an English-hub catalog as equal reliability in every pair.

## Complete blinded, model-assisted semantic review

Two independent assistant sessions reviewed disjoint blinded slices: all 24 RU↔EN cases (72 model outputs) and all 32 other cases (96 outputs). They received original sources, host-only meaning expectations and opaque engine labels, not the label key or native/runtime artifacts. Every one of the 168 model/case output variants was judged; each model produced one identical output variant across its three measured repetitions. Parent coverage checks found no missing, duplicate or mismatched variant identities.

These are **model-assisted linguistic judgments, not native-speaker or medical certification**. Expectations are synthetic semantic propositions, not human-approved reference translations. The corpus is a reused regression/stress set, not an unseen representative sample. Counts below must not be described as general translation accuracy or safety probabilities. A medium-confidence critical flag includes the shellfish/molluscs coverage concern described above.

| Model | PASS | MINOR | MAJOR | CRITICAL | UNKNOWN | Outputs judged |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Mozilla | 34 | 12 | 9 | 1 | 0 | 56 |
| LMT | 23 | 8 | 23 | 2 | 0 | 56 |
| NLLB | 25 | 4 | 23 | 4 | 0 | 56 |

PASS means no consequential meaning loss was identified; MINOR is non-substantive wording/grammar; MAJOR changes or omits essential meaning; CRITICAL flags a clear or explicitly qualified safety-relevant mistranslation. No measured output was an inference failure, but all three have semantic problems. Zero UNKNOWN means the judges assigned a category, not that their judgments have zero uncertainty.

Case-specific notes, confidence, exact evidence fragments and expectation-conflict fields are retained in `quality/ru-en-judgments.jsonl` and `quality/other-judgments.jsonl`; `quality/coverage.json` reconciles every variant. For example, Mozilla preserves the no-serve-if-unchecked dairy instruction in `en-es-04` and the separate-pan instruction in `en-zh-04`; NLLB omits these safeguards, while LMT changes butter to oil in the former and omits the separate pan in the latter. The parent inspected these actual source/output pairs and the reported date/currency failures directly.

## Decision supported by this experiment

**Mozilla is the strongest next integration candidate among these tested configurations:** decisively fastest in all ten directions, the smallest RU↔EN model-data package, and fewer major/critical flags on this particular blinded regression set. LMT is not a speed upgrade over NLLB on RU↔EN here. NLLB remains the broader-language option, not the quality or speed winner in this test.

This is not production acceptance. Mozilla still changes dates and currencies in some tourist cases and has an allergy-terminology concern; non-English pivot routes can compound meaning errors. Only five languages/ten directions were measured. Future ASR/TTS language coverage, every model language, ARM speed, Android app packaging and whole-conversation memory were not established. No silent fallback, provider/default change or language reduction was implemented.
