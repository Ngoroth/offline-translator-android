"""Acquire only the approved CV17 clips; expected bytes come from committed locks."""
import argparse
import csv
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tarfile
import tempfile
import time
import urllib.parse
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[2]
REVISION = '8262c16bf297c87a9cd88c51997c4758ed7a8ba2'
BASE = f'https://huggingface.co/datasets/fsicoli/common_voice_17_0/resolve/{REVISION}/'
HOSTS = {'huggingface.co', 'cdn-lfs.huggingface.co', 'cdn-lfs-us-1.huggingface.co', 'cas-bridge.xethub.hf.co', 'us.aws.cdn.hf.co'}
SOURCES = {
    'audio/en/test/en_test_0.tar': (711150592, '47160ec67fb545f41eba8103b0c0d7035dbb4ca3924043974449c7052c6f4775'),
    'audio/ru/test/ru_test_0.tar': (396584448, 'bd978611a35682952455bd0adaced31d2668a29d4fa4e71617c2d5de97eecb80'),
    'transcript/en/test.tsv': (4887241, 'b4d4db369413fcacacebee7de48e94c96c4f63383d6582e18a9b0856c5c8461a'),
    'transcript/ru/test.tsv': (3644643, '15162124bd657adea07695c22508b8bad174c282dcafe2302faaed05645cc59a'),
}


def digest(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify(path, size, expected):
    if Path(path).stat().st_size != size or digest(path) != expected:
        raise ValueError(f'Pinned size/hash mismatch: {Path(path).name}')


def safe_path(name):
    if not name or len(name) > 240 or '\\' in name or ':' in name or '\x00' in name:
        raise ValueError('Unsafe archive path')
    parts = name.split('/')
    if len(parts) > 12 or any(p in ('', '.', '..') for p in parts):
        raise ValueError('Unsafe archive components')
    if any(p.endswith(('.', ' ')) or any(ord(c)<32 or c in '<>"|?*' for c in p)
           or p.split('.')[0].upper() in {'CON','PRN','AUX','NUL',*[f'COM{i}' for i in range(1,10)],*[f'LPT{i}' for i in range(1,10)]} for p in parts):
        raise ValueError('Windows path alias or device name')
    return name


def validate_url(url, hosts):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in hosts or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError('Untrusted download URL')
    for address in socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM):
        if not ipaddress.ip_address(address[4][0]).is_global:
            raise ValueError('Nonpublic download address')


class VerifiedRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, hosts):
        self.hosts, self.count = hosts, 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        newurl = urllib.parse.urljoin(req.full_url, newurl)
        self.count += 1
        if self.count > 5:
            raise ValueError('Too many redirects')
        validate_url(newurl, self.hosts)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url, destination, size, expected, hosts=HOSTS):
    validate_url(url, hosts)
    opener = urllib.request.build_opener(VerifiedRedirect(hosts))
    start, count = time.monotonic(), 0
    with opener.open(url, timeout=30) as source, open(destination, 'xb') as target:
        while chunk := source.read(65536):
            count += len(chunk)
            if count > size or time.monotonic() - start > 900:
                raise ValueError('Download quota/deadline exceeded')
            target.write(chunk)
    verify(destination, size, expected)


def extract_selected(archive, destination, expected, byte_cap, max_entries=100000):
    seen, selected, total = set(), {}, 0
    with tarfile.open(archive, 'r|*') as tar:
        for index, entry in enumerate(tar, 1):
            name = safe_path(entry.name.removesuffix('/') if entry.isdir() else entry.name)
            folded = name.casefold()
            if index > max_entries or folded in seen or not (entry.isdir() or entry.isfile()):
                raise ValueError('Unsafe archive entry/type/count')
            seen.add(folded)
            total += entry.size
            if entry.size < 0 or total > byte_cap:
                raise ValueError('Archive byte quota exceeded')
            basename = name.rsplit('/', 1)[-1]
            if basename not in expected or entry.isdir():
                continue
            if basename in selected or entry.size > 200000:
                raise ValueError('Ambiguous/oversized selected clip')
            target = destination / basename
            with tar.extractfile(entry) as source, open(target, 'xb') as out:
                shutil.copyfileobj(source, out, 65536)
            verify(target, int(expected[basename]['orig_bytes']), expected[basename]['orig_sha256'])
            selected[basename] = name
    if selected.keys() != expected.keys():
        raise ValueError('Missing selected clips')
    return selected


def read_wave(path, rate, samples=None):
    data = Path(path).read_bytes()
    if len(data) > 2 * rate * 15 + 4096 or len(data) < 44 or data[:4] != b'RIFF' or data[8:12] != b'WAVE' or struct.unpack_from('<I', data, 4)[0] + 8 != len(data):
        raise ValueError('Invalid RIFF size/header')
    offset, chunks = 12, {}
    while offset < len(data):
        if offset + 8 > len(data):
            raise ValueError('Truncated chunk')
        kind, size = data[offset:offset+4], struct.unpack_from('<I', data, offset+4)[0]
        end = offset + 8 + size
        if end > len(data) or kind in chunks:
            raise ValueError('Duplicate/truncated chunk')
        chunks[kind] = data[offset+8:end]
        offset = end + (size % 2)
    fmt, pcm = chunks.get(b'fmt ', b''), chunks.get(b'data', b'')
    if len(fmt) < 16 or struct.unpack_from('<HHI', fmt) != (1, 1, rate) or struct.unpack_from('<H', fmt, 14)[0] != 16 or not pcm or len(pcm) % 2:
        raise ValueError('Invalid PCM format')
    if samples is not None and len(pcm) // 2 != samples:
        raise ValueError('Sample count mismatch')
    return len(pcm) // 2


def source_rows():
    with open(ROOT/'test-data/corpus/v1/source-lock.tsv', encoding='utf-8', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='|'))


def validate_transcripts(path, rows):
    expected = {r['clip']: r for r in rows}
    found = set()
    with open(path, encoding='utf-8', newline='') as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            if row['path'] not in expected:
                continue
            lock = expected[row['path']]
            if row['path'] in found or row['sentence'] != lock['transcript'] or row['client_id'] != lock['client_id'] or int(row['up_votes']) < 2 or int(row['down_votes']) > 1:
                raise ValueError('Transcript/speaker/vote mismatch')
            found.add(row['path'])
    if found != expected.keys() or any(len({r[key] for r in rows}) != 24 for key in ('clip', 'transcript', 'client_id')):
        raise ValueError('Missing or duplicate corpus identities')


def smoke_source(directory, case):
    """All ten immutable originals now live together, including the official fixtures."""
    source = Path(directory)/case['fileName']
    verify(source, case['bytes'], case['sha256'])
    read_wave(source, case['sampleRateHz'], case['sampleCount'])
    return source


def acquire(args):
    version = subprocess.check_output([args.ffmpeg, '-version'], text=True, timeout=10).splitlines()[0]
    if not version.startswith('ffmpeg version 9.0.1-full_build-www.gyan.dev '):
        raise ValueError('Unpinned ffmpeg build')
    work_parent = ROOT/'build/corpus-acquisition'
    work_parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(work_parent).free < 2 * 1024**3:
        raise ValueError('Insufficient acquisition space')
    locks, smoke = source_rows(), json.loads((ROOT/'test-data/corpus/v1/smoke-lock.json').read_text(encoding='utf-8'))
    output = Path(args.output).resolve()
    if output != ROOT/'test-data/corpus/v1':
        raise ValueError('Output must be canonical corpus directory')
    with tempfile.TemporaryDirectory(dir=work_parent) as temp:
        temp = Path(temp)
        staged = temp/'canonical'
        staged.mkdir()
        cases = []
        for case in smoke:
            src = smoke_source(args.smoke_source, case)
            dest = staged/case['path']; dest.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(src, dest)
            cases.append(case)
        for lang in ('en', 'ru'):
            rows = [r for r in locks if r['lang'] == lang]
            archive, transcript = temp/f'{lang}.tar', temp/f'{lang}.tsv'
            for relative, dest in [(f'transcript/{lang}/test.tsv', transcript), (f'audio/{lang}/test/{lang}_test_0.tar', archive)]:
                download(BASE+relative, dest, *SOURCES[relative])
            validate_transcripts(transcript, rows)
            mp3dir = temp/lang; mp3dir.mkdir()
            selected = extract_selected(archive, mp3dir, {r['clip']: r for r in rows}, archive.stat().st_size)
            for row in rows:
                src = mp3dir/row['clip']; relative = f"quality/{lang}/{src.stem}.wav"
                dest = staged/relative; dest.parent.mkdir(parents=True, exist_ok=True)
                probe = json.loads(subprocess.check_output([args.ffprobe, '-v', 'error', '-show_streams', '-of', 'json', str(src)], timeout=30))['streams']
                if len(probe) != 1 or probe[0]['channels'] != 1 or int(probe[0]['sample_rate']) != int(row['orig_hz']):
                    raise ValueError('Original rate/channel mismatch')
                subprocess.run([args.ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-bitexact', '-i', str(src), '-map_metadata', '-1', '-vn', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', '-fflags', '+bitexact', '-flags:a', '+bitexact', str(dest)], check=True, timeout=30)
                verify(dest, int(row['pcm16_wav_bytes']), row['pcm16_wav_sha256'])
                count = read_wave(dest, 16000, int(row['duration_ms'])*16)
                semantics = json.loads((output/'semantic-lock.json').read_text(encoding='utf-8'))
                cases.append(dict(id=src.stem, cohort='quality', language=lang, direction='RU_TO_EN' if lang == 'ru' else 'EN_TO_RU', path=relative, bytes=dest.stat().st_size, sha256=row['pcm16_wav_sha256'], sampleRateHz=16000, channels=1, bits=16, sampleCount=count, durationMs=int(row['duration_ms']), reference=row['transcript'], tags=row['tags'].split('+'), requiredSemanticPhrases=semantics[f"{lang}|{src.stem.rsplit('_',1)[-1]}"], source=dict(url=BASE+f'audio/{lang}/test/{lang}_test_0.tar', revision=REVISION, archivePath=selected[row['clip']], mp3Bytes=int(row['orig_bytes']), mp3Sha256=row['orig_sha256'], originalHz=int(row['orig_hz']), client_id=row['client_id'], wpm=float(row['wpm']))))
            archive.unlink(); transcript.unlink()
        files = ''.join(f"{c['path']}\t{c['bytes']}\t{c['sha256']}\n" for c in sorted(cases, key=lambda c:c['path']))
        (staged/'files.tsv').write_text(files, encoding='utf-8', newline='\n')
        manifest = dict(schemaVersion=1, filesSha256=hashlib.sha256(files.encode()).hexdigest(), cases=cases)
        (staged/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
        for name in ('source-lock.tsv', 'smoke-lock.json', 'semantic-lock.json'):
            shutil.copyfile(output/name, staged/name)
        backup = temp/'previous-canonical'
        output.rename(backup)
        try:
            staged.rename(output)
        except BaseException:
            backup.rename(output)
            raise
    print('Verified and published 58 pinned WAVs; acquisition temporary inputs removed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ffmpeg', default='ffmpeg'); parser.add_argument('--ffprobe', default='ffprobe')
    parser.add_argument('--smoke-source', default=str(ROOT/'test-data/corpus/v1/smoke'))
    parser.add_argument('--output', required=True)
    acquire(parser.parse_args())
