"""Verify retained asset lookups from APK bytes, without installing production app."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def evaluation_manifests(corpus):
    """Identify evaluation metadata by content, independent of packaged asset names."""
    paths = [corpus/'manifest.json', *sorted((corpus.parents[1]/'model-sets').glob('*.json'))]
    if len(paths) < 2:
        raise ValueError('Missing canonical model-set manifests for production exclusion')
    payloads = [path.read_bytes() for path in paths]
    return ({hashlib.sha256(data).digest() for data in payloads},
            [json.loads(data) for data in payloads])


def verify_apks(app_test, runner_test, production, corpus):
    corpus = Path(corpus)
    manifest = json.loads((corpus/'manifest.json').read_text(encoding='utf-8'))
    cases = manifest['cases']
    if len(cases) != 58 or len(list(corpus.rglob('*.wav'))) != 58 or any('speech-' in c['path'] for c in cases):
        raise ValueError('Canonical corpus membership mismatch')
    smoke = {c['fileName']: c for c in cases if c['cohort'] == 'smoke'}
    mappings = [(app_test, {'speech-ru-fixture.wav': smoke['ru-official-0.wav'], 'speech-en-fixture.wav': smoke['en-official-0.wav']}), (runner_test, smoke)]
    for path, expected in mappings:
        with zipfile.ZipFile(path) as apk:
            names = apk.namelist()
            wavs = [n for n in names if n.startswith('assets/') and n.endswith('.wav')]
            if len(wavs) != len(expected) or set(wavs) != {'assets/'+n for n in expected}:
                raise ValueError('Missing, duplicate or unexpected fixture lookup')
            for name, case in expected.items():
                data = apk.read('assets/'+name)
                if len(data) != case['bytes'] or hashlib.sha256(data).hexdigest() != case['sha256']:
                    raise ValueError('APK fixture hash mismatch')
    manifest_hashes, manifest_values = evaluation_manifests(corpus)
    with zipfile.ZipFile(production) as apk:
        for entry in apk.infolist():
            name = entry.filename
            if name.startswith('assets/') and (name.endswith('.wav') or 'corpus/' in name or 'model-sets/' in name or name.endswith('.onnx')):
                raise ValueError('Evaluation input in production APK')
            if name.startswith('assets/') and not entry.is_dir() and entry.file_size <= 1024*1024:
                # Bound decompression, and inspect all asset suffixes, including renamed binaries.
                with apk.open(entry) as stream:
                    data = stream.read(1024*1024+1)
                if len(data) > 1024*1024:
                    raise ValueError('Oversized production asset metadata')
                if hashlib.sha256(data).digest() in manifest_hashes:
                    raise ValueError('Evaluation manifest in production APK')
                try:
                    value = json.loads(data)
                except (ValueError, UnicodeError, RecursionError):
                    continue
                if value in manifest_values:
                    raise ValueError('Evaluation manifest in production APK')
    print('PASS: both unchanged APK lookups, official hashes, 58 canonical WAVs and production exclusion')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for flag in ('app-test-apk', 'runner-test-apk', 'production-apk', 'corpus'): parser.add_argument('--'+flag, required=True)
    args = parser.parse_args()
    verify_apks(args.app_test_apk, args.runner_test_apk, args.production_apk, args.corpus)
