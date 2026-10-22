# NeMo speech backend: third-party attribution

This backend uses local inference only. Model installation is explicit and verified;
ordinary Gradle builds do not download source archives, tools or model weights.

## NVIDIA NeMo-Speech.cpp

- Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
- Runtime version: 0.2.0, commit `6a3ca369370782acee0dd155e34394ba60b920c4`.
- Source: https://github.com/NVIDIA/NeMo-Speech.cpp/tree/6a3ca369370782acee0dd155e34394ba60b920c4
- License: Apache License 2.0; https://www.apache.org/licenses/LICENSE-2.0
- Modification: exactly three Android-only CMake lines link `nemo_speech_asr` to
  platform `log`. The recognizer, decoder and upstream ggml patch series are unchanged.
- The upstream `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md` are included verbatim
  in the prepared Android assets under `nemo-native/licenses/`.

The build retains upstream ASR code, including its compiled diarization code; no
diarization model or optional normalization/endpointing/VAD model is enabled.
The upstream notice describes optional components as well as this CPU closure; it
does not imply optional servers, CUDA, KenLM or text-normalization libraries ship here.

## llama.cpp / ggml

- Copyright (c) 2023-2026 The ggml authors.
- Commit: `bd4f514db14d87fded667787a7a963bfbaa98e89`.
- Source: https://github.com/ggml-org/llama.cpp/tree/bd4f514db14d87fded667787a7a963bfbaa98e89
- License: MIT; the original `LICENSE` is included in prepared assets.
- NVIDIA's pinned upstream patch series includes Apache-2.0 additions and preserves
  MIT attribution. Portions of NeMo ASR derive from parakeet.cpp, Copyright (c)
  2025 Jason Ni, as described in the packaged upstream notices.

## SentencePiece and bundled dependencies

- SentencePiece, Copyright 2018 Google Inc.; Apache License 2.0.
- Commit: `31646a467d2051eb904e0b45de3a73e91fe1c1e3` (0.2.1).
- Source: https://github.com/google/sentencepiece/tree/31646a467d2051eb904e0b45de3a73e91fe1c1e3
- The static runtime includes bundled Abseil (Apache-2.0), protobuf-lite and
  Darts-clone (BSD). Original license texts, including the bundled esaxx license,
  are copied directly from this pinned archive into prepared assets.
- Static application libc++ uses the license supplied by Android NDK
  `29.0.13113456-beta1`; its license text is also shipped in prepared assets.

## NVIDIA Parakeet TDT 0.6B v3 model

- Creator and attribution: NVIDIA, **Parakeet TDT 0.6B v3**.
- Source/model card: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/tree/541d1f99c6b0c3cd0b11a95167540bb8edefd82b
- License: **Creative Commons Attribution 4.0 International (CC BY 4.0)**:
  https://creativecommons.org/licenses/by/4.0/
  Legal code: https://creativecommons.org/licenses/by/4.0/legalcode.en
- This application uses NVIDIA's official GGUF Q8_0 conversion
  `parakeet-tdt-0.6b-v3.q8_0.gguf` at that revision, without further weight changes.
- Exact bytes: 713975456; SHA-256:
  `e3880d0aaaaf2c308ea2c35016b2b895c423eb3fda924c1b463d1c19b7f4d32e`.
- CC BY 4.0 permits sharing and adaptation with appropriate attribution, a link
  to the license, and indication of changes. No NVIDIA endorsement is implied.
- The 25 trained languages describe model coverage, not qualification of every
  language or a guarantee that a transcript or inferred direction is correct.

## Build provenance

`engine-nemo/native-build.json` pins SHA-256/size for all three official source
archives, official tool archive hashes, verified read-only tool-tree fingerprints,
and the CPU/API26/static-libc++/16 KiB ABI closure. The explicit preparation helper
materializes sources into its owned output directory and preserves the upstream
patched-ggml mechanism. Its receipt records source/bridge/recipe fingerprints,
tool/source pins, the upstream patch inventory, packaged file hashes and actual
ELF ABIs, exported JNI/C entry points, dependency closure and LOAD alignment.
No host shared library, model weight, SDK archive or toolchain is committed here.
