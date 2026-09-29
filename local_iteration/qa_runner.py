"""Local handoff runner: original input -> HTTP -> independent checker -> artifacts.
No metrics are manufactured; PARTIAL is not FULL. No data is uploaded anywhere.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
RULESET = "DOCUMENT_NEAREST_V1"
MAX_RESPONSE = 512 * 1024 * 1024

def save_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    tmp.replace(path)

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()

def single_target(source: dict, target: object) -> dict:
    features = source.get('features', [])
    selected = [f for f in features if (f.get('properties') or {}).get('object_type') == 'oks_connection_point'
                and (f.get('properties') or {}).get('id') == target]
    if len(selected) != 1: raise ValueError(f'Expected exactly one target ID {target!r}, found {len(selected)}')
    result = copy.deepcopy(source)
    result['features'] = [copy.deepcopy(f) for f in features
        if (f.get('properties') or {}).get('object_type') != 'oks_connection_point'
        or (f.get('properties') or {}).get('id') == target]
    return result

def multipart(payload: bytes) -> tuple[bytes, str]:
    boundary = 'heatnet_' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="input.geojson"\r\n'
            'Content-Type: application/geo+json\r\n\r\n').encode('ascii')
    body += payload + f'\r\n--{boundary}--\r\n'.encode('ascii')
    return body, 'multipart/form-data; boundary=' + boundary

def classify(report: dict) -> str:
    if not report.get('contract_valid', False): return 'INVALID'
    m = report.get('geometry_metrics') or {}
    connected, total = m.get('connected_oks_count'), m.get('total_oks_count')
    if not isinstance(connected, int) or not isinstance(total, int): return 'CHECKER_SCHEMA_ERROR'
    return 'FULL' if connected == total else 'PARTIAL'

def command(cmd: list[str], log: Path, timeout: int = 240) -> dict:
    started = time.perf_counter()
    with log.open('w', encoding='utf-8') as out:
        out.write('COMMAND: ' + ' '.join(cmd) + '\n'); out.flush()
        try:
            p = subprocess.run(cmd, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT,
                               timeout=timeout, check=False)
            row = {'command': cmd, 'returncode': p.returncode,
                   'status': 'PASS' if p.returncode == 0 else 'FAIL'}
        except subprocess.TimeoutExpired:
            row = {'command': cmd, 'returncode': None, 'status': 'TIMEOUT'}
        except OSError as e:
            out.write(repr(e)); row = {'command': cmd, 'returncode': None, 'status': 'ERROR'}
    row.update(elapsed_seconds=time.perf_counter()-started, log=log.name)
    print(row['status'], ' '.join(cmd), flush=True)
    return row

def wait_ready(service: str, out: Path, seconds: int = 150) -> None:
    deadline = time.monotonic() + seconds
    errors = []
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(service + '/configuration', timeout=3) as r:
                payload = r.read(2_000_000)
            config = json.loads(payload)
            if RULESET not in config.get('supported_rulesets', []):
                raise ValueError('Service does not advertise DOCUMENT_NEAREST_V1')
            save_json(out/'api-configuration.json', config)
            with urllib.request.urlopen(service + '/algorithms', timeout=5) as r:
                save_json(out/'api-algorithms.json', json.load(r))
            return
        except (OSError, ValueError) as e:
            errors.append(str(e)); time.sleep(2)
    save_json(out/'startup-errors.json', errors)
    raise RuntimeError('Service did not become ready within the startup deadline')

def run_case(service: str, src: Path, algorithm: str, budget_ms: int, out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    payload = src.read_bytes()
    (out/'input.geojson').write_bytes(payload)
    body, content_type = multipart(payload)
    query = urllib.parse.urlencode({'algorithm': algorithm, 'ruleset': RULESET,
        'entryStrategy': 'AUTO', 'budgetMs': budget_ms, 'diagnosticFallback': 'false'})
    url = service + '?' + query
    row = {'algorithm': algorithm, 'ruleset': RULESET, 'budget_ms': budget_ms,
           'input_sha256': digest(src), 'case': out.name, 'url': url}
    save_json(out/'request.json', row)
    started = time.perf_counter()
    try:
        req = urllib.request.Request(url, data=body, headers={'Content-Type': content_type}, method='POST')
        with urllib.request.urlopen(req, timeout=budget_ms/1000 + 60) as response:
            status = response.status
            headers = dict(response.headers.items())
            target = out/'result.geojson'
            size = 0
            with target.open('wb') as f:
                while True:
                    chunk = response.read(1024*1024)
                    if not chunk: break
                    size += len(chunk)
                    if size > MAX_RESPONSE: raise ValueError('Response exceeds the diagnostic size limit')
                    f.write(chunk)
        save_json(out/'response-headers.json', headers)
        row.update(http_status=status, api_elapsed_seconds=time.perf_counter()-started)
        # Import the delivered independent checker, not Java's certification flags.
        sys.path.insert(0, str(ROOT/'scripts'))
        from benchmark_checker import check
        report = check(out/'input.geojson', target, 'ITERATION_001_' + out.name, RULESET)
        save_json(out/'checker-report.json', report)
        m = report.get('geometry_metrics') or {}
        row.update(status=classify(report), checker_status=report.get('status'),
                   connected=m.get('connected_oks_count'), total=m.get('total_oks_count'),
                   violation_count=len(report.get('violations', [])), result_sha256=digest(target))
        response_headers = {k.lower():v for k,v in headers.items()}
        row['api_ruleset'] = response_headers.get('x-ruleset-id')
        row['api_complete'] = response_headers.get('x-solution-complete')
        if row['api_ruleset'] != RULESET:
            row.update(status='RULESET_MISMATCH', error='Response ruleset is absent or does not match request')
        if row.get('api_complete') in ('true','false'):
            row['api_checker_coverage_agree'] = ((row['api_complete']=='true') == (classify(report)=='FULL'))
    except urllib.error.HTTPError as e:
        (out/'http-error-body.txt').write_bytes(e.read(4_000_000))
        save_json(out/'response-headers.json', dict(e.headers.items()))
        row.update(status='HTTP_ERROR', http_status=e.code, error=str(e))
    except Exception as e:
        (out/'error.txt').write_text(traceback.format_exc(), encoding='utf-8')
        row.update(status='ERROR', error=str(e))
    row['elapsed_seconds'] = time.perf_counter()-started
    save_json(out/'case-summary.json', row)
    print(out.name, row['status'], f"{row.get('connected','-')}/{row.get('total','-')}", flush=True)
    return row

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['smoke','live'])
    parser.add_argument('--service-url', default='http://app:8080/api/trace')
    parser.add_argument('--out', type=Path, default=Path('/artifacts'))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    source = ROOT/'data/tz_update_2026_09_19/corrected_dataset.geojson'
    manifest = {'iteration':'ITERATION_001', 'mode':args.mode, 'python':sys.version,
                'platform':platform.platform(), 'ruleset':RULESET,
                'input_sha256':digest(source), 'case_outputs':[], 'status':'STARTED'}
    manifest_path = args.out/f'qa-{args.mode}.json'
    save_json(manifest_path, manifest)
    try:
        files = [ROOT/'pom.xml', ROOT/'scripts/benchmark_checker.py', ROOT/'local_iteration/release.json']
        manifest['file_hashes'] = {str(p.relative_to(ROOT)):digest(p) for p in files if p.is_file()}
        actual_sources = {str(p.relative_to(ROOT)):digest(p)
                          for p in sorted((ROOT/'src').rglob('*.java')) if p.is_file()}
        actual_sources['scripts/benchmark_checker.py'] = digest(ROOT/'scripts/benchmark_checker.py')
        save_json(args.out/'actual-source-hashes.json', actual_sources)
        command([sys.executable,'-m','pip','freeze'], args.out/'python-dependencies.log')
        if args.mode == 'smoke':
            checks = [
                ([sys.executable,'-m','unittest','discover','-s','local_iteration','-p','test_qa_runner.py','-v'], 'runner-tests.log'),
                ([sys.executable,'-m','unittest','discover','-s','scripts','-p','test_certification_roots.py','-v'], 'checker-root-tests.log'),
                ([sys.executable,'scripts/benchmark_smoke_tests.py'], 'checker-original-smoke.log'),
            ]
            manifest['checks'] = [command(c,args.out/n) for c,n in checks]
            manifest['status'] = 'PASS' if all(x['status']=='PASS' for x in manifest['checks']) else 'FAIL'
        else:
            wait_ready(args.service_url.rstrip('/'), args.out)
            data = json.loads(source.read_text(encoding='utf-8'))
            # The IDs below define diagnostic cases for this supplied dataset only.
            # They are not special cases in the solver or its rules.
            for target_id in (2,5,10):
                local = args.out/f'single_{target_id}.geojson'
                save_json(local,single_target(data,target_id))
                row=run_case(args.service_url.rstrip('/'), local, 'B0-CORRIDOR', 20_000,
                             args.out/f'single_{target_id}_b0')
                manifest['case_outputs'].append(row); save_json(manifest_path, manifest)
                if row['status']=='ERROR':
                    # A timed-out request may still be consuming the server.
                    raise RuntimeError('Transport/checker error; stop sequential batch, inspect case logs')
            for algorithm,budget in [('B3',120_000),('FIRST-FULL',120_000)]:
                row=run_case(args.service_url.rstrip('/'),source,algorithm,budget,
                             args.out/('original_'+algorithm.lower()))
                manifest['case_outputs'].append(row); save_json(manifest_path,manifest)
                if row['status']=='ERROR': raise RuntimeError('Transport/checker error; stop batch')
            ok = all(x['status'] in ('FULL','PARTIAL') for x in manifest['case_outputs'])
            manifest['status']='CHECKS_COMPLETED' if ok else 'NEEDS_REVIEW'
            manifest['all_cases_full']=all(x['status']=='FULL' for x in manifest['case_outputs'])
            manifest['note']='PARTIAL remains partial; completion of the runner is not a 17/17 claim.'
    except Exception as e:
        manifest.update(status='ERROR', error=str(e))
        (args.out/f'qa-{args.mode}-error.txt').write_text(traceback.format_exc(),encoding='utf-8')
    finally:
        save_json(manifest_path,manifest)
    return 0 if manifest['status'] in ('PASS','CHECKS_COMPLETED') else 1

if __name__ == '__main__':
    raise SystemExit(main())
