# Four-way translation comparison on the Android emulator

## Decision

**Mozilla is the only demonstrated large speed improvement, not a demonstrated equally dependable replacement.** It remains the performance lead to qualify further. Index had fewer model-assisted meaning flags on this small set, but no consistent speed advantage and a concrete allergen substitution. MADLAD is larger, generally slower, and not an acceleration choice.

No candidate is accepted as the new default. Keep Tencent and product behavior unchanged. The joint requirement “substantially faster and dependable tourist dialogue across the required languages” is not yet established by these results; accepting a documented tradeoff is a separate product decision. Neither runtime completion nor a low AI-flag count establishes safety or human translation accuracy.

## Measured execution

Run: `fourway-20261115-main-v1`. The runner exited **1**, not 0. All **528 measured calls** completed successfully: 44 inputs × four engines × three repetitions. All 120 excluded warmups also completed. There were no declared inference failures or failed measured inputs.

The missing evidence is **all five Index cold-start observations**, not its warm translations. The Windows ADB daemon was unreachable on port5037 during that model-group preparation. Five blocks were not executed; the retained six evidence entries are one preparation error and five consequent not-executed records. No retry, backfill or replacement was performed. Index cold-start performance is therefore **unmeasured**, not a model failure.

Observed totals: 135 executed blocks, 135 startup rows, 15 cold first calls, 120 warmups and 528 measured calls = 798 rows. The frozen schedule expected 140 blocks / 808 rows; the ten missing cells are five Index startup/call pairs. All 135 executed native and transport exit codes were0. The completed analysis contains 176 model/input groups, each with all three measured repetitions, and 132 complete source-paired candidate/Tencent comparisons.

## Complete-call latency

Values are **nearest-rank P50 / P95 in milliseconds**. Every cell below has six calls from two distinct fresh sources, each repeated three times. P95 is consequently the maximum of these six observations, not a well-powered tail estimate. Correctly terminated output is not necessarily a correct translation; no semantically poor output is silently removed from timing.

### CLEAN inputs

| Direction | Tencent | Mozilla | Index | MADLAD |
| --- | ---: | ---: | ---: | ---: |
| RU→EN | 5678.3 / 5891.2 | 28.3 / 32.5 | 5354.6 / 6326.6 | 9189.7 / 9290.7 |
| EN→RU | 4944.2 / 6274.9 | 21.3 / 36.6 | 5591.7 / 5894.0 | 6840.2 / 7484.1 |
| ES→EN | 4498.5 / 4763.4 | 22.3 / 32.3 | 5040.2 / 5256.5 | 7108.1 / 8808.2 |
| EN→ES | 4665.6 / 5199.6 | 19.1 / 30.5 | 4461.1 / 5538.2 | 4378.8 / 7559.9 |
| ZH→EN | 4548.0 / 4909.8 | 25.7 / 37.6 | 4876.5 / 5889.8 | 6631.7 / 8654.7 |
| EN→ZH | 3423.0 / 3788.8 | 24.6 / 26.9 | 4146.9 / 4314.2 | 5817.6 / 6415.3 |
| AR→EN | 4365.9 / 5187.0 | 15.1 / 31.8 | 4131.7 / 4996.6 | 4698.9 / 5151.9 |
| EN→AR | 5748.3 / 6266.6 | 21.6 / 28.5 | 5483.6 / 5835.2 | 7822.9 / 9036.6 |
| RU→ZH | 2917.0 / 5038.1 | 38.8 / 57.9 | 2903.5 / 4698.6 | 2321.1 / 6293.5 |
| ZH→RU | 4247.0 / 5383.2 | 61.8 / 125.9 | 4799.4 / 5174.1 | 6235.3 / 6297.0 |

### Synthetic ASRLIKE inputs

| Direction | Tencent | Mozilla | Index | MADLAD |
| --- | ---: | ---: | ---: | ---: |
| RU→EN | 5489.3 / 5731.7 | 26.8 / 38.3 | 5416.0 / 5614.1 | 7987.6 / 8941.9 |
| EN→RU | 5664.3 / 6344.2 | 32.1 / 37.7 | 5348.4 / 6234.8 | 5957.4 / 7924.2 |
| ES→EN | 4772.3 / 5008.8 | 17.9 / 29.6 | 4761.9 / 5370.7 | 6068.2 / 6332.1 |
| EN→ES | 4560.2 / 5738.1 | 25.1 / 29.9 | 4556.3 / 5486.6 | 4547.7 / 6610.6 |
| ZH→EN | 4771.5 / 5205.3 | 21.0 / 71.5 | 5193.1 / 5656.3 | 7392.8 / 9594.7 |
| EN→ZH | 3402.7 / 4047.4 | 18.5 / 42.0 | 4125.9 / 4250.2 | 4305.3 / 6210.7 |
| AR→EN | 4531.0 / 5547.3 | 26.8 / 30.7 | 4301.4 / 5128.5 | 4327.5 / 7335.7 |
| EN→AR | 6522.0 / 7517.5 | 24.5 / 41.0 | 5224.8 / 5640.2 | 7520.2 / 8270.9 |
| RU→ZH | 2800.4 / 4759.5 | 15.9 / 67.4 | 2854.6 / 4871.7 | 1776.1 / 6440.3 |
| ZH→RU | 4638.7 / 6028.9 | 43.4 / 67.3 | 5223.6 / 5837.4 | 3752.2 / 7275.7 |

### Paired speed comparison

This is the **nearest-rank P50 of per-source ratios**, each computed as Tencent's three-call median divided by the candidate's three-call median. It is not a ratio of pooled percentiles or a conventional even-sample median. Above1 means faster than Tencent; below1 means slower. All required source pairs are complete.

| Candidate | CLEAN,20 sources | ASRLIKE,20 sources | Known regressions,4 sources |
| --- | ---: | ---: | ---: |
| Mozilla | 169.19× | 180.66× | 319.57× |
| Index | 0.95× | 0.99× | 1.02× |
| MADLAD | 0.67× | 0.78× | 0.45× |

On this exact emulator/configuration cohort, Mozilla is the clear speed candidate. Index does not provide a consistent speed improvement over current Tencent; MADLAD is generally slower and has a larger payload. These observations do not rank semantic reliability or predict ARM performance.

## Cold process and sampled memory

The cold input is the same complete RU→EN bicycle request for every engine. Ranges summarize the five individual observations preserved in `analysis.json`; no storage-cache flush was performed.

| Engine | Observed cold processes | Load range,ms | First complete call range,ms | Constructor+load+first-call range,ms |
| --- | ---: | ---: | ---: | ---: |
| Tencent | 5/5 | 191.2–208.8 | 5841.6–7148.5 | 6051.2–7345.3 |
| Mozilla | 5/5 | 314.0–364.1 | 26.3–33.9 | 341.6–397.4 |
| Index | 0/5 | Unmeasured:ADB preparation failure | Unmeasured | Unmeasured |
| MADLAD | 5/5 | 837.6–888.0 | 9165.2–9313.3 | 10004.5–10201.6 |

| Engine | Sampled blocks | Highest sampled PSS,MiB | Highest recorded kernel process-lifetime RSS,MiB |
| --- | ---: | ---: | ---: |
| Tencent | 35 | 1397.8 | 1400.2 |
| Mozilla | 35 | 882.2 | 927.7 |
| Index | 30 | 1362.1 | 1364.6 |
| MADLAD | 35 | 2123.9 | 2126.6 |

All135 sampled engine PIDs match the native processes. Mozilla direct-only blocks reached568.1MiB sampled PSS; its two-pack pivots reached882.2MiB. This is not a72MB RAM engine, and not a bidirectional conversation-owner or speech-overlap measurement. Four graph replicas are eager per required pack. Some short Mozilla blocks have only one PSS sample; sampled PSS/RSS and native lifetime RSS have different meanings and can miss different phases. Index has30 rather than35 blocks because its five cold blocks were not executed.

## Blinded meaning screen

Three model-assisted reviewers judged **disjoint** slices: RU/EN10 sources/40 variants, Chinese-related17/68, and Spanish/Arabic-related17/68. They did not inspect engine keys, timings or other slices. All176 opaque variants were covered exactly once; only after all three finished were labels joined to engine identities. These are not three independent votes on each translation.

Each engine has 20 CLEAN, 20 paired ASRLIKE and 4 known-regression outputs. CLEAN and ASRLIKE represent the same 20 situations, not 40 independent scenarios. There is one distinct observed output per source/engine across its three measured repetitions. The table counts cases flagged MAJOR or CRITICAL for consequential missing, added or changed meaning, not style-only MINOR issues.

| Engine | CLEAN serious flags /20 | ASRLIKE serious flags /20 | Known-regression serious flags /4 | UNKNOWN cases,CLEAN/ASRLIKE/regression |
| --- | ---: | ---: | ---: | --- |
| Tencent | 4 | 7 | 1 | 1/1/0 |
| Mozilla | 5 | 8 | 2 | 0/1/0 |
| Index | 3 | 3 | 2 | 0/1/0 |
| MADLAD | 4 | 5 | 1 | 0/1/0 |

UNKNOWN is neither a passing translation nor a proven error. All four engine outputs for the unpunctuated ASRLIKE bicycle source received UNKNOWN from the RU/EN reviewer because of permission-question versus availability-statement ambiguity; the paired CLEAN source was not used to restore missing punctuation. Another UNKNOWN concerns an underspecified room/key-number interpretation. There are no missing judgments or NOT_TRANSLATED flags.

These counts are **screening flags, not accuracy estimates or a statistically established quality ranking**. Synthetic checklists can overstate a proposition; the literal source prevails. Confidence and expectation conflicts remain in every raw judgment. Severity boundaries involving pronouns, transliteration or pragmatic recovery deserve bilingual human adjudication. No native-speaker or clinical certification was performed.

### Concrete source-bound counterexamples

| Engine | Case | Source meaning and actual output issue |
| --- | --- | --- |
| Mozilla | `fourway-heldout-zh-en-01-clean` | Source `这20元是雨伞押金吗？还伞时会全部退还吗？` asks whether20yuan is an umbrella deposit and whether it is fully refunded on return. Output `Is $20 an umbrella deposit?Will the umbrella be returned?` adds a dollar symbol and changes the refund question into return of the umbrella. |
| Index | `fourway-heldout-ru-en-02-asrlike` | Russian specifies sesame allergy and sesame oil. Output `I have a peanut allergy, so if there's peanut oil in the sauce, don't add the sauce.` changes both to peanut. This is not a style issue or a missing-EOS failure. |
| Tencent | `fourway-heldout-ru-zh-01-clean` | Source requests a taxi child seat for a two-year-old, not a second car. Output contains `婴儿车` (stroller), not a child restraint; the no-second-car instruction remains. |
| MADLAD | `fourway-heldout-zh-ru-01-asrlike` | Source `嗯 我不吃猪肉 汤里的肉是牛肉还是猪肉` states pork avoidance and asks whether soup contains beef or pork. Output `Да, я не ем свинины.` omits the ingredient question entirely. |

The known Mozilla room-date reversal (`en-ru-08`, `не раньше 6 декабря`) and yuan-to-dollar regression (`zh-ru-04`) also remain in this exact-pack run. They are reused regression evidence, not fresh generalization tests. Current Tencent likewise has consequential errors; it is a baseline, not a certified oracle.

## Evidence files and reproducible commands

Completed raw run and analysis: `.cache/translator-evaluation/four-way/runs/fourway-20261115-main-v1/`. `analysis.json` retains120 direction/cohort summaries, all individual cold observations,135 memory records and132 paired source comparisons. `expected-missing.jsonl` and `evidence-errors.json` preserve the absent Index cold cohort. No results were backfilled from calibration.

`quality/{ruen,chinese,other}-judgments.jsonl` retain blind judgments. `blind-label-key.json` binds opaque labels; `quality-judgments-unblinded.jsonl` retains the joined176 outputs and source-specific evidence. `quality-summary.json` contains exact case/variant coverage and unknowns. The data revision archive preserves the initial host checklist and its source-governed bicycle correction; all58 native source identities/texts remain unchanged.

Commands actually executed:

```text
C:/Users/Ngoroth/AppData/Local/Programs/Python/Python313/python.exe -B .cache/translator-evaluation/four-way/measure.py --config .cache/translator-evaluation/four-way/config.json --run-id fourway-20261115-calibration-v1 --adb C:/Users/Ngoroth/AppData/Local/Android/Sdk/platform-tools/adb.exe --serial emulator-5554 --phase calibration
C:/Users/Ngoroth/AppData/Local/Programs/Python/Python313/python.exe -B .cache/translator-evaluation/four-way/measure.py --config .cache/translator-evaluation/four-way/config.json --run-id fourway-20261115-main-v1 --adb C:/Users/Ngoroth/AppData/Local/Android/Sdk/platform-tools/adb.exe --serial emulator-5554 --phase measure
C:/Users/Ngoroth/AppData/Local/Programs/Python/Python313/python.exe -B .cache/translator-evaluation/four-way/analyze.py .cache/translator-evaluation/four-way/runs/fourway-20261115-main-v1
```

The calibration exit was 0; main exit 1 was retained; analysis exited 0. Existing run IDs are immutable and refuse reuse. A fresh repeat requires an explicitly new run ID and must not replace this result.


## Scope and environment

This is an engine-only comparison, not an application, speech-pipeline, ARM-device or release qualification. The app and its Tencent default remain unchanged.

The frozen calibration environment is `emulator-5554`, Android SDK 37 / SDK-full 37.2, x86_64, four logical processors, 16,384-byte pages and `MemTotal: 8128796 kB`. CPU inference is used throughout. The image also advertises an ARM native bridge; these experiments execute actual x86_64 ELF binaries, not ARM code.

## Exact configurations

| Engine | Model payload | Runtime and decoding |
| --- | --- | --- |
| Current Tencent | Hy-MT2-1.8B Q4_K_M, 1,133,080,448 bytes | Unchanged production native runtime; llama.cpp `d8d92973c8c21bb4dd5d592f0fec26fe2cd1e654`; CPU4, constrained greedy, production 4,096-token bound |
| Index | Official Index-Translate-2B Q4_K_M, 1,312,164,352 bytes | llama.cpp `e574a17b062be51b98e239ccd3d3743e3d902333`; CPU4, greedy, 256 generated tokens; official target-only prompt and non-thinking template |
| MADLAD | MADLAD-400-3B Q4_K_M, 1,858,124,864 bytes, plus official SentencePiece sidecar 4,427,844 bytes | llama.cpp `d8d92973c8c21bb4dd5d592f0fec26fe2cd1e654`; native SentencePiece 0.2.1; CPU4, encoder once per request, greedy, 256 generated tokens |
| Mozilla | Eight frozen directional model/vocabulary/shortlist packs, selected by route | Frozen maintained Mozilla inference build; four sentence workers and four eager graph replicas per required pack; beam1, source segmentation 128, max-length-factor2; non-English routes time both English-pivot hops |

These are exact engine configurations, not an isolated comparison of parameter counts. Four Mozilla sentence workers are not equivalent to four-way parallel matrix multiplication for one short sentence. Token budgets, kernels, prompt formats and memory policies differ and are not silently equalized.

Index model revision: `3a0eca6ce931d88d00614d427ab3ed8ff0743902`, payload SHA256 `044b313d29342bd3b2c77cbb64023ca0d209bd9b3247763f9d162767ef2d746a`. The quantization is official; its exact conversion commit is not published.

MADLAD quantization revision: `7f55e827c4c2fd1fdc52893879e18966d2867dba`, payload SHA256 `fc56f16d215db71e856de3c3770974c867e3d95a782d415d4cfabc9fb470b8e4`. This community GGUF has the full 32-layer encoder and 32-layer decoder, 2,940,374,016 stored parameters and llama-compatible tensors. It is not the smaller differently packaged artifact sometimes quoted for other runtimes. The uploader does not supply a reproducible conversion recipe. The official tokenizer is pinned to `fa184c675da0b5c9e1c8694fccd4e12e2d422094`; its sidecar SHA256 is `ef11ac9a22c7503492f56d48dce53be20e339b63605983e9f27d2cd0e0f3922c`. Combined GGUF plus tokenizer payload is 1,862,552,708 bytes, not RAM usage.

The eight measured Mozilla packs match complete current-release model and companion payload hashes despite registry version renumbering. This is not a new-weight quality improvement. No redownload or relabel-only requalification was performed.

## Inputs and measurement boundaries

The frozen dataset contains 20 newly authored tourist situations across ten directions, each in CLEAN and paired synthetic ASRLIKE form: 40 fresh inputs. Four exact earlier regression inputs are separate, not new held-out evidence. Ten short same-pair warmups and four separate calibration inputs are excluded from measured warm statistics. ASRLIKE is a deterministic text rendering, not actual recognition output or ASR-accuracy evidence. These are synthetic sources and proposition checklists, not a human-certified evaluation corpus or a model-training holdout.

Directions: RU↔EN, EN↔ES, EN↔ZH, EN↔AR and RU↔ZH. Every request is stateless: no transcript history or reference answer is sent to the engine. Host-only expectations do not enter prompts. Underdetermined ellipsis remains underdetermined; the bicycle request does not establish paid rental rather than borrowing.

Full-translation timing uses the native steady clock, including prompt construction, tokenization, encoder/prefill, decoding, detokenization and both pivot hops. ADB, transfers, hashing and JSONL writing are outside the timer. Constructor, load and first call are distinct; there is no token-TTFT measurement. Process-cold does not mean storage-cache-cold: file verification and staging touch model pages, and caches are not dropped.

Percentiles use explicit nearest-rank P50/P95. Three repetitions of the same source are not independent sentences. Successful-only latencies require accompanying failure/missing denominators. Paired Tencent/candidate ratios require all three complete measured calls from both engines. CLEAN, ASRLIKE and known REGRESSION cohorts remain separate. The three specified four-engine rotations are not a fully balanced Latin design; storage, cache and order effects remain limitations.

Memory sampling reads the actual engine PID's `smaps_rollup` every 0.2 seconds. Sampled PSS/RSS maxima are not exact peaks. Native `ru_maxrss` is a process-lifetime RSS maximum, not instantaneous PSS or a per-request heap measurement. No ASR/TTS memory or retained bidirectional app owner is included.

## Observed native and calibration proof

Index final Android binary SHA256: `1d86bb24b608602158d3e3b544289d14b3d3b63e9d65e60e15daa8ed36981523`. MADLAD final Android binary SHA256: `7fbc60c7f8b744aaf56258ab8b8b7ab2bb8952278634ca1002a9904222ec1699`. Both passed real emulator token checks, complete excluded translations, missing-model/empty-input failures, and an explicitly excluded one-token cap probe. Main generation caps are unchanged.

Index matched all 62 full official-tokenizer fixtures. MADLAD matched all 64 official T5Tokenizer fixtures, including tabs, special-token boundaries and decoding cleanup. Offline official-tokenizer verification of actual generated IDs matched all eight retained calibration/cap outputs per new engine. These are token/decoding proofs, not semantic correctness scores.

The four-engine calibration `fourway-20261115-calibration-v1` exited 0: 16 fresh processes, 16 startup rows and 16 translation rows; all 32 rows are successful and evidence-valid. Missing cells, inference failures and evidence errors are empty. `freeze.json` timestamps describe pre-inference preparation, not total inference-run duration. Tencent exposes no direct EOS/token-count telemetry, so its successful native contract is not relabelled as observed EOS.

Preparation failures remain retained and are not counted as model mistranslations: the original MADLAD vocab-only guard queried uninitialized runtime hparams; the first Index fixture producer omitted official added-token definitions; the initial parent calibration helper collided input/output paths; and llama's MADLAD tokenizer differed from official SentencePiece on three excluded edge fixtures. MADLAD now uses the exact official native SentencePiece wrapper rather than dropping those edges. Device-tested earlier binaries, fixtures, statuses, failed-build receipts and raw outputs are preserved. Retention limit: an initial pre-freeze MADLAD executable/log was overwritten by an incremental build before any device test; this is recorded in its build-attempt ledger, not represented as retained execution proof. The parent staging helper also received a fail-closed existence-probe correction, with six isolated host safety scenarios passing; cleanup was limited to verified owned device copies.

## Evidence and interpretation limits

Local evidence root: `.cache/translator-evaluation/four-way/`. It contains frozen source/runtime/model inventories, data revisions, `preflight/index-r4`, `preflight/madlad-r3`, candidate decoder proofs and `runs/fourway-20261115-calibration-v1`. Source text/IDs were not changed by the host-only bicycle-checklist correction. All removed device files were individually verified disposable executable/model copies with preserved matching host artifacts; raw evidence was retained.

This ten-direction screen cannot qualify the full 17-language product, all composed routes or future ASR/local-TTS coverage. Mozilla has seven current-language English-hub legs available only as unqualified tiny packs: VI→EN and EN↔ID/TR/HI. They are catalog-covered, not proven by the measured base-pack configuration. No language reduction, automatic fallback, default change or provider integration is authorized by these experiments.
