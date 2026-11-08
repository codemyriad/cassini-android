#!/usr/bin/env python3
"""Freeze public numerical outputs and per-run provenance without changing a product golden."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def fingerprint(data):
    return hashlib.sha256(data).hexdigest()


def payload(report):
    return [dict(file=item['file'], inputSha256=item['inputSha256'], text=item['androidResult']['text'],
                 language=item['androidResult'].get('language'), words=item['androidResult']['words'])
            for item in report['results']]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    numerical = args.output / 'numerical'
    numerical.mkdir(exist_ok=True)
    reference = json.loads(args.reference.read_text())
    snapshot = {}
    for api in [26, 35, 29, 28]:
        folder = args.runs / f'api{api}'
        if not (folder / 'batch.json').is_file():
            continue
        batch = json.loads((folder / 'batch.json').read_text())
        if batch['status'] != 'completed':
            raise ValueError('Cannot publish an incomplete batch')
        results = []
        for case in batch['cases']:
            if 'skipped' in case:
                results.append(case)
                continue
            source = folder / case['name'] / 'result.json'
            raw = source.read_bytes()
            report = json.loads(raw)
            items = payload(report)
            if len(items) != len(reference['fixtures']):
                raise ValueError('Wrong fixture count')
            for actual, expected in zip(items, reference['fixtures']):
                if (actual['file'], actual['inputSha256']) != (expected['file'], expected['inputSha256']):
                    raise ValueError('Case PCM does not match the frozen reference')
            if report['privacyRefusals'] != 6:
                raise ValueError('Privacy guard did not pass all cases')
            env = report['environment']
            if case['name'] != 'published':
                if env['getauxvalCalls'] <= 0 or sum(env['mathCalls'].values()) <= 0:
                    raise ValueError('Requested interposition was not exercised')
                if case['name'].startswith('off-') and env['effectiveDotprod']:
                    raise ValueError('Forced-off dispatch did not take effect')
                if case['name'].startswith('on-') and not env['effectiveDotprod']:
                    raise ValueError('Forced-on dispatch did not take effect')
                if env['pinnedMath'] != case['name'].endswith('-musl'):
                    raise ValueError('Wrong math implementation')
            encoded = json.dumps(items, sort_keys=True, separators=(',', ':')).encode()
            digest = fingerprint(encoded)
            # One fixture per line keeps the full returned words/times/probabilities reviewable,
            # while content addressing deduplicates identical outputs across variants and guests.
            (numerical / (digest + '.json')).write_text('[\n' + ',\n'.join(
                json.dumps(item, sort_keys=True, separators=(',', ':')) for item in items) + '\n]\n')
            current = dict(case)
            current.update(numericalSha256=digest, rawReportSha256=fingerprint(raw), engineSha256=report['engineSha256'],
                           exactWordFixtures=sum(item['exactWordsEqual'] for item in report['results']),
                           maxTimeDifferenceSeconds=max(item['maxTimeDifferenceSeconds'] for item in report['results']))
            results.append(current)
        snapshot[str(api)] = dict(hardware=batch['hardware'], cases=results)
    (args.output / 'runs.json').write_text(json.dumps(snapshot, indent=2) + '\n')
    lines = ['| API | Variant | Effective dotprod | Exact word fixtures | Max boundary delta | Numerical output |',
             '| --- | --- | --- | --- | --- | --- |']
    for api, result in snapshot.items():
        for case in result['cases']:
            if 'skipped' in case:
                lines.append(f'| {api} | {case["name"]} | skipped | — | — | {case["skipped"]} |')
                continue
            env = case['environment']
            dot = env.get('effectiveDotprod', bool(int(env['realHwcap'], 16) & (1 << 20)))
            lines.append(f'| {api} | {case["name"]} | {dot} | {case["exactWordFixtures"]}/13 | '
                         f'{case["maxTimeDifferenceSeconds"] * 1000:.0f} ms | `{case["numericalSha256"][:12]}` |')
    (args.output / 'TABLE.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
