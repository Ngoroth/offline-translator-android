# Mozilla Translation engine integration outcome

Date: 2026-11-15. Scope: [approved implementation plan, revision 3](../plans/2026-11-14-2248-mozilla-translation-engine.md), against base `3d028a8e3e2da237cfa161b57746f760c62ce30a`.

## Result and acceptance boundary

Mozilla is implemented as a second explicitly selectable Translation engine for Text and Conversation. Tencent remains registered first and fully available. Defaults, Reset, completed setup, persisted settings format, recognition/output choices and the 17-language catalog are unchanged. No automatic fallback, provider dispatcher, Whisper translation-to-English task, publication, version bump, commit or push was performed.

The bounded implementation/runtime checks and required direct warm-speed criterion passed. This is **not linguistic certification or user acceptance**. Mozilla still makes consequential meaning errors, including currency substitutions; those are listed below. Independent code review, goal-validation and user acceptance remain distinct workflow decisions.

## Configuration and artifact identity

- Real Android x86_64 emulator, API 37.2 image, 16-KiB pages, four configured CPUs and approximately 8-GiB guest RAM. ARM was cross-compiled, not executed. This is not a phone or low-RAM benchmark.
- Mozilla upstream `8eada038fd45bbf6fa58a412b22f80c79887fabc`; production `BlockingService`, cache zero, one eager backend per selected model, serialized calls. An English pair retains two directions; a non-English pair retains four English-hub directions. This is not the historical four-worker CLI configuration.
- Current AAR `engine-mozilla/libs/mozilla-native-8eada038-r2.aar`: 6,189,845 bytes, SHA-256 `42d499084f6274c7641670dc29efc4bf93c65b80b28ce5fa3dfd9c6cea427ab5`.
- Corresponding-source archive: SHA-256 `62bd5ba8e39ad8892dad8c3e7da63212a3377f3479e6b3184389a7ffabfaf81e`. Native input inventory: `0ace38641cd049569cfd7172e4f78e6e73e0d318fe701b07e4102b23c187dea5`.
- ARM uses the real Ruy/NEON path; x86_64 uses intgemm. Both packaged ELFs have 16-KiB LOAD alignment and only approved Android/platform dynamic dependencies. Unstripped symbols are retained separately. Pins, tools, patches, notices and source/member hashes are in `engine-mozilla/.native-evidence/handoff.json` and the adjacent receipt.
- Actual locally signed optimized APK: SHA-256 `1e6b28bb509ee5164866f64328e05e0df4c13caace2c2a634e34edf7e9f7d2c2`, 101,239,264 bytes. It uses the standard development certificate, not a production signing secret. Its installed identity was checked after the emulator restart.
- Unsigned release AAB: SHA-256 `3061f09b797f0d39ad44399571077088b318dab9531a920af7b22834fb31c880`, 40,829,664 bytes. APK and AAB each contain 2,514,828 uncompressed DEX bytes. R8 actually ran; application classes are obfuscated, with narrow JNI keeps rather than global optimization/obfuscation suppression. `analyzeReleaseR8Config`, `bundleRelease`, signature verification and 16-KiB ZIP alignment passed. The temporary signing-password file was removed.

## Frozen inputs and runtime coverage

The [Mozilla corpus](../../test-data/corpus/translation/mozilla-v1/README.md) was authored and frozen before observing new outputs: 96 source turns, three categories per direction, all 32 English-hub legs and all 17 source languages. Seven explicitly selected tiny legs contribute 21 cases: VI→EN and EN↔ID/TR/HI. The other 25 legs contribute 75 cases. English essential propositions/uncertainty are host-only and never enter prompts.

The original `translation/v1` bytes and IDs are unchanged. All 48 in-catalog rows were retained as regression evidence. Its eight Arabic rows are explicitly outside this iteration's app catalog, not silently discarded failures.

| Final-artifact scenario | Actual result |
|---|---|
| Module JNI/runtime class | 13/13 passed |
| Real accepted-JNI consumer lifecycle class | 4/4 passed |
| Fresh multilingual plus preserved regression output | 144/144 completed; 40/40 excluded warm-ups; 17 owners; no missing/failed rows |
| HI→ID and ID→HI composed tiny routes | 2/2 completed; one bidirectional four-leg owner |
| Offline Whisper transcribe→Mozilla→Piper Conversation | 4/4 fixed WAV invocations, two serialized owners, five real VAD/forwarded/recognized/completed/validated utterances; zero failures |
| Same production providers, warmed comparison | 192/192 measured calls; 24/24 excluded warm-ups; 12 initializations; zero failures/missing rows |
| Fresh-process first-use comparison | 12/12 observations, 12 distinct application PIDs; no missing/failed observations |
| Retained-owner app gate | Passed separately with saved Tencent and Mozilla; zero downloads |
| Startup choice/controller gate | Passed; both IDs, busy-owner refusal, commit-once/reopen behavior |
| Corrected compact rendered UI | 3/3 relevant touch/geometry/fixed-footer/microphone cases passed |

The consumer cases observe actual accepted nativeLoad/nativeTranslate frames, cancellation without late publication, close/drain/replacement and an independent awaitIdle-return boundary before close. The failed first pivot hop retains its typed failure; a bounded complete ART trace records one nativeTranslate entry/completion and no second-hop entry. These are real JNI/resource scenarios, not fake-provider call-count assertions.

Both app retained-owner runs recorded load counts 0→1 on first Text acquisition, still 1 through Conversation, last Text and pair reversal, then 2 after background/reacquire and 3 after trim/reacquire; both completed with download count zero. Speech capture was intentionally not started by that lifetime-only gate.

The Conversation test uses actual Whisper, Mozilla, Piper, native VAD and Android playback. Output-disabled execution has zero TTS readiness/preparation/speak admissions. Enabled execution binds each real VAD segment to its public transcript, preserves recognized text as MT input/original text, and speaks only the successful final trimmed target. Public non-silent playback/drain is observed; private PCM/AudioTrack telemetry is not invented. Half-duplex capture termination and dynamic utterance counts are retained, rather than assuming one WAV equals one utterance. Pacing/observer I/O makes this unsuitable as an unperturbed whole-turn latency benchmark.

Catalog classification is not qualification of every composed pair: the sampled RU↔ZH and HI↔ID final outputs do not certify all 272 ordered pairs or all languages' local speech readiness.

## Complete-call speed

Same emulator and application build, current production Tencent and Mozilla, one resident provider at a time. All 32 v1 RU↔EN/RU↔ZH sources were measured three times, alternating directions inside each retained owner and counterbalancing provider order across passes. Constructor, preparation, explicit warm-ups and observer memory/JSON collection are outside these per-call clocks. Tencent retains its existing production options and hidden preparation warm-up; no Tencent speed or decoding modification was made.

P50/P95 below are **nearest-rank percentiles of repeated full API-call milliseconds**, not TTFT, token throughput or whole Conversation responsiveness.

| Direction | Calls/provider | Mozilla P50 ms | Mozilla P95 ms | Tencent P50 ms | Tencent P95 ms |
|---|---:|---:|---:|---:|---:|
| RU→EN | 36 | 22.78 | 38.41 | 5,356.28 | 15,113.84 |
| EN→RU | 36 | 18.98 | 48.42 | 5,264.09 | 16,753.06 |
| RU→ZH | 12 | 26.41 | 78.06 | 3,818.73 | 12,971.39 |
| ZH→RU | 12 | 34.69 | 126.30 | 5,918.30 | 16,977.09 |

The required RU→EN and EN→RU P50 **and** P95 improvement passed, with no omitted/failed measured cases. Pivot results are reported separately.

For each source, take its provider's median of three calls, form Tencent/Mozilla, then take the explicitly **nearest-rank P50 of per-source paired ratios**: RU→EN 294.79×, EN→RU 324.33×, RU→ZH 138.65×, ZH→RU 133.60×. These are not ratios of aggregate percentiles or conventional even-count medians.

Fresh-process constructor + local preparation + first complete call, three observations per provider/pair:

| Retained pair | Mozilla combined ms | Tencent combined ms |
|---|---|---|
| RU↔EN | 544.57, 481.96, 488.58 | 5,013.69, 4,928.74, 4,850.09 |
| RU↔ZH | 1,101.19, 963.14, 935.02 | 4,822.18, 4,978.12, 5,311.55 |

Actual force-stop/absent-PID/start-event binding, first driver invocation and first owner-call ordinal were retained for all 12. Fresh process is not cold OS/page cache; process launch/instrumentation and observer I/O are excluded. Tencent preparation includes its existing hidden English/one-token warm-up.

The first reverse-direction API calls are the **excluded, different-sentence warm-ups**, not isolated switch penalties: Mozilla EN→RU 139.33/127.52/129.73 ms and ZH→RU 66.93/73.73/73.42 ms; Tencent EN→RU 45,318.93/44,812.67/46,144.30 ms and ZH→RU 5,711.13/5,754.82/5,729.95 ms. These unexpectedly long Tencent warm-ups remain visible; they were not substituted for the fixed measured sources. Subsequent measured calls alternate directions under the same prepared owner without route reload. The warm-up input difference prevents attributing these durations solely to switching.

## Resource observations

Boundary samples are full debug-test process PSS/RSS, not incremental model bytes, continuously sampled maxima, true peaks or phone limits. Values below convert KiB to MiB by dividing by 1024. Native allocator allocation counters are not resident memory.

| Retained pair/provider | Maximum boundary PSS MiB | Maximum boundary RSS MiB |
|---|---:|---:|
| Mozilla RU↔EN | 299.53 | 447.94 |
| Mozilla RU↔ZH | 576.69 | 725.07 |
| Tencent RU↔EN | 1,495.16 | 1,645.72 |
| Tencent RU↔ZH | 1,477.50 | 1,625.89 |

All 12 owner releases completed. Mozilla close observations were 12.22–39.41 ms; subsequent process PSS samples were 76.89–99.89 MiB. This is bounded disposal evidence, not a long-running leak/pressure proof or a guarantee that RSS returns to zero.

With actual Whisper + Mozilla RU↔EN + both selected Piper voices co-resident, 58 unique boundary samples reached **1,087,706 KiB PSS / 1,259,040 KiB RSS** (1,062.21 / 1,229.53 MiB). Speech coexistence is therefore not represented by the translation-only 300-MiB figure. No proportional extrapolation to tiny packs, ARM, phones or other voices is made.

## Meaning review: material limits for user acceptance

Four separate sessions judged disjoint source-language slices from neutral, blinded files. They were not duplicate adjudicators or votes. The exact join covers 146 source cases and 178 provider/output variants: Mozilla fresh 96, Mozilla regression 48, Mozilla composed-route probes 2, and Tencent on the common 32 regression sources. The review is **model-assisted, not native-speaker/human certification or an accuracy estimate**. Confidence and expectation conflicts/unknowns remain in the retained judgments.

| Cohort/provider | PASS | MINOR | MAJOR | CRITICAL | UNKNOWN |
|---|---:|---:|---:|---:|---:|
| Mozilla fresh 96 | 70 | 12 | 12 | 0 | 2 |
| Mozilla preserved regression 48 | 30 | 8 | 8 | 1 | 1 |
| Mozilla HI↔ID probes 2 | 2 | 0 | 0 | 0 | 0 |
| Tencent common regression 32 | 19 | 6 | 5 | 1 | 1 |
| Mozilla same common regression 32 | 16 | 7 | 8 | 1 | 0 |

The last row is a subset of Mozilla's 48-row regression cohort, not additional judgments. These flags do **not** establish a Mozilla quality win over Tencent.

Concrete consequential Mozilla flags include:

- KO→EN: 12,000 won becomes “one thousand dollars”.
- ZH→EN: 85 yuan becomes “$85”.
- TR→EN: 900 lira becomes “$900”.
- EN→ZH: a lost-property desk becomes a lost-real-estate counter.
- KO→EN: an airport-bound bus stop becomes a stop at the airport; a separate spicy-sauce request is omitted.
- TH→EN: a request for sugar separately becomes “I'm splitting the sugar”.
- EN→PT: taking a suitcase upstairs loses the upward movement.

The known date-exclusion/currency regressions remain evidence; integration did not retrain the identical model payloads. The shared shellfish→“моллюски” CRITICAL label is a **medium-confidence, qualified terminology sentinel** for both providers, not a clinical harm probability or medical certification. Other pragmatic/lexical uncertainty is retained rather than forced into PASS/FAIL. Inputs and source-governed expectations remain separate from model prompts.

## Actual optimized-release surface

The exact installed, locally signed R8 variant was exercised through its real UI/JNI, not by debug instrumentation pretending to test a release binary:

1. EN→RU, frozen `en-ru-01`: “Where can I buy drinking water?” → “Где можно купить питьевую воду?”
2. RU→ZH, frozen `ru-zh-01`: “Можно оплатить наличными?” → “我能用现金支付吗?” through the prepared English pivot. The displayed source remains Russian.
3. Offline Conversation with the existing Whisper/System-offline-output choices: real AudioRecord/VAD accepted paced virtual-microphone PCM from the frozen official Russian WAV, Whisper produced Russian original text, and Mozilla displayed “A team is coming to you now. Go ahead.” for “Сейчас в тебе приедет бригада. Давай.” The original recognized text was retained, the turn reached Translation ready, and Stop returned to the stopped screen. This is not a claim that the ASR wording is a human reference transcription.

Screenshots, UI hierarchies, exact WAV/input identities, source admission, installed APK hash and device restoration are bound in `.cache/translator-evaluation/mozilla-integration/integration-evidence-final-v1.json` and `release-runtime-v1/`.

The initial optional concurrent blocking input/output-control attempt failed at emulator transport and the emulator stopped answering ADB. Those failed receipts and the pre-correction helper are retained. The user terminated that emulator and explicitly authorized launching the same AVD again. It was restarted without wipe or loading the stalled snapshot. The corrected release smoke starts actual capture first and uses paced real-time input without concurrent output recording. Real-time delivery may overwrite data while capture is paused, so no complete-wave admission claim is made. Output PCM recording/acoustic or independent spoken-word certification was not established. The separate debug Piper non-silent public-playback evidence is not mislabeled as release PCM evidence.

After verification, the original **Tencent / Whisper / System offline speech, English→Russian, English interface, 100% speed, 700-ms silence, speech-output enabled** settings were restored through the real UI. Airplane mode is off, mobile data and Wi-Fi are enabled again, the prior microphone permission is unchanged, and physical host-microphone capability was restored only after stopping/force-stopping the test capture. The optimized test-signed APK remains installed for user review. No application models/settings were wiped; Mozilla remains available for explicit selection.

## Discovered defects and retained failures

- A genuine upstream invalid-precision exception unwound through a partially initialized ExpressionGraph and dereferenced a null tensor workspace. Exact crashed BuildID/PC/object bytes and diagnostic relink bind the cause. The narrow partial-graph cleanup patch preserves the real exception; final runtime checks pass. The original crash remains recorded.
- A valid empty final shortlist row in the unchanged EN→VI artifact was rejected by an upstream `>=` offset bound. Static checksum/shape/ID evidence established validity. The patch permits the end sentinel while rejecting reversed/non-monotonic ranges; final all-32-leg/tiny gates pass on the same component hashes. The original all-catalog preparation failure remains recorded.
- Revision 3 changes only the pinned transport for the unchanged EN→ZH lexical bytes after the prior GCS attachment failed. It is not a model update, runtime fallback or new product scope.
- A 260-dp Russian layout had only seven visible workspace pixels because the fixed status/footer consumed 676 of 683 pixels. Compact-height status now scrolls with the workspace, while Stop/speaker/attribution and touch sizes remain fixed. The original 74-pass/one-failure UI run and diagnostic geometry are retained; the corrected three relevant surface cases passed.
- The original Conversation test incorrectly treated one WAV as one VAD utterance and read a stale first-terminal snapshot. Revision 2 validates every real segment after producer/drain boundaries; no core recognition/translation/playback behavior or input was narrowed. Original failed rows remain retained.
- Earlier configuration-cache capture/build/test-scheduler failures and the release-control transport failures remain historical failures, not retroactively green runs.

## Reproduction and evidence index

Use [verify-translator.md](verify-translator.md#mozilla-prepared-resource-qualification) for explicit opt-in module/app preparation, offline qualification, warm/first-use and Conversation selectors. Ordinary tests do not implicitly download models or start inference.

Local evidence root: `.cache/translator-evaluation/mozilla-integration/`:

- `module-runtime-v3/`, `module-consumer-v2/`: current-AAR 13/4 JNI/lifecycle receipts and retained consumer trace.
- `app-outputs-v1/`, `tiny-pivot-v1/`, `conversation-offline-v2/`: final output/route/speech execution receipts and append-only raw rows.
- `compare-warm-v1/`, `warm-analysis-v1.json`: exact 192-call lattice, failures/missing counts, timings, ratios, initialization/release and memory boundaries.
- `first-use-series-v1/`, `first-use-analysis-v1.json`: all 12 independent process identities and complete first-use observations.
- `quality-summary-v1.json`, `quality-private-v1/`; neutral blinded inputs/judgments in `.cache/translator-evaluation/quality-screen-v1/`: exact source/output/judgment binding, uncertainty and provenance.
- `shared-lifetime-tencent-v1/`, `shared-lifetime-mozilla-v1/`, `startup-choice-v1/`, `compact-ui-fixed-v1/`: preserved provider/owner/startup/rendered-surface behavior.
- `release-final-v1/`, `release-runtime-v1/`, `integration-evidence-final-v1.json`: actual optimized artifacts, R8/signature evidence, installed binary identity, real UI output and restored settings/device state.
- `engine-mozilla/.native-evidence/`: current source/build receipts, symbols, static VI diagnosis and preserved failed closures.

No ARM runtime, physical microphone/acoustic test, human bilingual review, all-pair semantic certification, general translation accuracy, ≤2-second whole-Conversation guarantee or future published-binary claim is made. The remaining decision is user acceptance of this explicitly selectable provider with the disclosed model limitations, after the independent workflow checks.
