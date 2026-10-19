import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import wave
import shutil
import subprocess
from unittest.mock import patch
import acquire_v1 as acquisition


class AcquisitionSafetyTest(unittest.TestCase):
    def test_consolidated_smoke_sources_include_both_official_fixtures(self):
        root = acquisition.ROOT/'test-data/corpus/v1'
        cases = json.loads((root/'smoke-lock.json').read_text(encoding='utf-8'))
        self.assertEqual(10, len(cases))
        for case in cases:
            self.assertEqual(root/case['path'], acquisition.smoke_source(root/'smoke', case))
        self.assertEqual(2, sum('-official-' in case['fileName'] for case in cases))

    def test_all_committed_selection_identities_are_unique(self):
        rows = acquisition.source_rows()
        self.assertEqual(48, len(rows))
        for lang in ('ru', 'en'):
            subset = [r for r in rows if r['lang'] == lang]
            for field in ('clip', 'transcript', 'client_id'):
                self.assertEqual(24, len({r[field] for r in subset}))

    def test_paths_reject_escapes_and_windows_aliases(self):
        for name in ('../clip.mp3', '/clip.mp3', 'C:/clip.mp3', 'a\\b', 'a//b', 'a/./b', 'a\0b', 'a/CON.mp3', 'a.', 'a ', 'NUL', 'a?b'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                acquisition.safe_path(name)

    def test_ignored_archive_link_and_case_collision_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            for entries in ([('ignored', tarfile.SYMTYPE), ('clip.mp3', tarfile.REGTYPE)], [('a', tarfile.REGTYPE), ('A', tarfile.REGTYPE)]):
                path = temp/'archive.tar'
                with tarfile.open(path, 'w') as tar:
                    for name, kind in entries:
                        entry = tarfile.TarInfo(name); entry.type = kind
                        if kind == tarfile.SYMTYPE:
                            entry.linkname = '../outside'
                        tar.addfile(entry, io.BytesIO())
                with self.assertRaises(ValueError):
                    acquisition.extract_selected(path, temp, {}, 100)

    def test_transcript_modification_is_rejected_before_audio(self):
        rows = [r for r in acquisition.source_rows() if r['lang'] == 'en']
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp)/'test.tsv'
            file.write_text('path\tsentence\tclient_id\tup_votes\tdown_votes\n'+rows[0]['clip']+'\tChanged\t'+rows[0]['client_id']+'\t2\t0\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Transcript'):
                acquisition.validate_transcripts(file, rows)

    def test_corrupt_and_wrong_rate_waves_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)/'audio.wav'
            with wave.open(str(path), 'wb') as output:
                output.setnchannels(1); output.setsampwidth(2); output.setframerate(8000); output.writeframes(b'\0\0'*20)
            with self.assertRaises(ValueError): acquisition.read_wave(path, 16000)
            path.write_bytes(path.read_bytes()[:-1])
            with self.assertRaises(ValueError): acquisition.read_wave(path, 8000)

    def test_wrong_ffmpeg_fails_before_any_download(self):
        args = type('Args', (), {'ffmpeg': 'ffmpeg'})()
        with patch.object(acquisition.subprocess, 'check_output', return_value='ffmpeg version changed'), patch.object(acquisition, 'download') as download:
            with self.assertRaises(ValueError): acquisition.acquire(args)
            download.assert_not_called()

    def test_conversion_failure_cleans_staging_and_preserves_canonical_corpus(self):
        original = acquisition.ROOT
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            output = root/'test-data/corpus/v1'
            shutil.copytree(original/'test-data/corpus/v1', output)
            before = {p.relative_to(output): acquisition.digest(p) for p in output.rglob('*') if p.is_file()}
            args = type('Args', (), dict(ffmpeg='ffmpeg', ffprobe='ffprobe', smoke_source=str(output/'smoke'), output=str(output)))()
            def download(url, path, *unused):
                path.write_bytes(b'fixture')
            def extract(archive, destination, expected, *unused):
                for name in expected: (destination/name).write_bytes(b'fixture')
                return {name: name for name in expected}
            def command(arguments, **unused):
                if arguments[0]=='ffmpeg': return 'ffmpeg version 9.0.1-full_build-www.gyan.dev fixture\n'
                row = next(r for r in acquisition.source_rows() if r['lang']=='en')
                return json.dumps(dict(streams=[dict(channels=1,sample_rate=row['orig_hz'])]))
            with patch.object(acquisition,'ROOT',root), patch.object(acquisition,'download',side_effect=download), \
                    patch.object(acquisition,'extract_selected',side_effect=extract), patch.object(acquisition,'validate_transcripts'), \
                    patch.object(acquisition.subprocess,'check_output',side_effect=command), \
                    patch.object(acquisition.subprocess,'run',side_effect=subprocess.CalledProcessError(1,'ffmpeg')):
                with self.assertRaises(subprocess.CalledProcessError): acquisition.acquire(args)
            after = {p.relative_to(output): acquisition.digest(p) for p in output.rglob('*') if p.is_file()}
            self.assertEqual(before, after)
            self.assertEqual([], list((root/'build/corpus-acquisition').iterdir()))

    def test_url_rejects_downgrade_credentials_and_private_resolution(self):
        for url in ('http://huggingface.co/file', 'https://bad.example/file', 'https://user:pass@huggingface.co/file'):
            with self.assertRaises(ValueError): acquisition.validate_url(url, acquisition.HOSTS)
        with patch.object(acquisition.socket, 'getaddrinfo', return_value=[(0,0,0,'',('127.0.0.1',443))]):
            with self.assertRaises(ValueError): acquisition.validate_url('https://huggingface.co/file', acquisition.HOSTS)


if __name__ == '__main__': unittest.main()
