import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from acquire_v1 import ROOT
from verify_apk_fixture_assets import verify_apks


class ApkFixtureTest(unittest.TestCase):
    def test_aliases_keep_identity_and_production_has_no_evaluation_data(self):
        corpus=ROOT/'test-data/corpus/v1'
        if not (corpus/'manifest.json').exists(): self.fail('Acquire pinned corpus before APK validation tests')
        cases=json.loads((corpus/'manifest.json').read_text(encoding='utf-8'))['cases']
        with tempfile.TemporaryDirectory() as tmp:
            paths=[Path(tmp)/name for name in ('app.apk','runner.apk','production.apk')]
            with zipfile.ZipFile(paths[0],'w') as apk:
                for lang in ('ru','en'): apk.write(corpus/f'smoke/{lang}-official-0.wav', f'assets/speech-{lang}-fixture.wav')
            with zipfile.ZipFile(paths[1],'w') as apk:
                for case in cases:
                    if case['cohort']=='smoke': apk.write(corpus/case['path'], 'assets/'+case['fileName'])
            with zipfile.ZipFile(paths[2],'w') as apk:
                apk.write(ROOT/'engine-sherpa/src/main/assets/speech-models.json','assets/speech-models.json')
            verify_apks(*paths,corpus)
            with zipfile.ZipFile(paths[2],'a') as apk: apk.writestr('assets/ru-official-0.wav',b'wrong')
            with self.assertRaisesRegex(ValueError,'production'): verify_apks(*paths,corpus)
            with zipfile.ZipFile(paths[0],'w') as apk: apk.writestr('assets/ru-official-0.wav',b'wrong name')
            with self.assertRaisesRegex(ValueError,'lookup'): verify_apks(*paths,corpus)

    def test_canonical_manifest_payloads_rejected_regardless_of_asset_path(self):
        corpus=ROOT/'test-data/corpus/v1'
        cases=json.loads((corpus/'manifest.json').read_text(encoding='utf-8'))['cases']
        with tempfile.TemporaryDirectory() as tmp:
            app,runner,production=[Path(tmp)/name for name in ('app.apk','runner.apk','production.apk')]
            with zipfile.ZipFile(app,'w') as apk:
                for lang in ('ru','en'): apk.write(corpus/f'smoke/{lang}-official-0.wav',f'assets/speech-{lang}-fixture.wav')
            with zipfile.ZipFile(runner,'w') as apk:
                for case in cases:
                    if case['cohort']=='smoke': apk.write(corpus/case['path'],'assets/'+case['fileName'])
            for source in (corpus/'manifest.json',ROOT/'test-data/model-sets/baseline.json'):
                for name in (source.name,'renamed.bin','unrelated/deep/speech-models.json'):
                    for reformatted in (False,True):
                        with self.subTest(source=source,name=name,reformatted=reformatted):
                            data=source.read_bytes()
                            if reformatted: data=json.dumps(json.loads(data),sort_keys=True,ensure_ascii=True).encode()
                            with zipfile.ZipFile(production,'w') as apk: apk.writestr('assets/'+name,data)
                            with self.assertRaisesRegex(ValueError,'manifest in production'):
                                verify_apks(app,runner,production,corpus)


if __name__=='__main__': unittest.main()
