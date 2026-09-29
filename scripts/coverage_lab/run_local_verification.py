"""Run the executable checks delivered with this patch; report scope explicitly."""
import argparse
import datetime
import hashlib
import json
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--out',type=Path,default=ROOT/'results/local_verified_2026_09_29/final')
    parser.add_argument('--with-maven',action='store_true')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    commands=[
        ('java_kernel_tests',[sys.executable,'-m','unittest','discover','-s','scripts','-p','test_entry_interval_kernel.py','-v']),
        ('checker_root_tests',[sys.executable,'-m','unittest','discover','-s','scripts','-p','test_certification_roots.py','-v']),
        ('original_checker_smoke',[sys.executable,'scripts/benchmark_smoke_tests.py']),
        ('original_numeric_checks',[sys.executable,'scripts/run_numeric_benchmarks.py']),
        ('competition_entry_audit',[sys.executable,'scripts/coverage_lab/verify_entry_kernel.py']),
        ('synthetic_full_connection',[sys.executable,'scripts/coverage_lab/build_narrow_entry_fixture.py']),
    ]
    if args.with_maven:
        commands.append(('full_maven_tests',['mvn','test']))
    rows=[]
    for name,cmd in commands:
        started=time.perf_counter()
        try:
            result=subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,timeout=300)
            rc=result.returncode;text=result.stdout+'\n'+result.stderr
        except (OSError,subprocess.TimeoutExpired) as error:
            rc=None;text=repr(error)
        log=args.out/(name+'.log');log.write_text(text,encoding='utf-8')
        row={'name':name,'command':cmd,'returncode':rc,'status':'PASS' if rc==0 else 'FAIL',
             'elapsed_seconds':time.perf_counter()-started,'log':str(log.relative_to(ROOT))}
        rows.append(row);print(name,row['status'],flush=True)
    def version(cmd):
        try:
            r=subprocess.run(cmd,text=True,capture_output=True,timeout=10)
            return (r.stdout+r.stderr).strip()
        except OSError:return None
    data=ROOT/'data/tz_update_2026_09_19/corrected_dataset.geojson'
    report={'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'python':platform.python_version(),'platform':platform.platform(),
            'java':version(['java','-version']),'java_compilation_target':'--release 11',
            'maven_available':shutil.which('mvn') is not None,'docker_available':shutil.which('docker') is not None,
            'full_spring_application_started':False,'full_competition_route_run':False,
            'original_geojson_sha256':hashlib.sha256(data.read_bytes()).hexdigest(),
            'checks':rows,'status':'PASS' if all(x['status']=='PASS' for x in rows) else 'FAIL',
            'limits':['PASS covers the listed tests, Java arithmetic kernel, and synthetic route only.',
                      'It is NOT a Maven/Spring/Docker certificate or a full 17/17 competition run.',
                      'The original 17 demand coordinates and all original restrictions remain unchanged.']}
    (args.out/'verification_manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    return 0 if report['status']=='PASS' else 1

if __name__=='__main__':raise SystemExit(main())
