"""Preserve before/after evidence for the checker changes, using synthetic inputs."""
import copy
import importlib.util
import json
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from test_certification_roots import fixture, refresh_summary
from benchmark_checker import check


def main():
    original=ROOT/'results/local_verified_2026_09_29/source_before/benchmark_checker.py'
    spec=importlib.util.spec_from_file_location('checker_before_patch',original)
    before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
    out=ROOT/'results/local_verified_2026_09_29/checker_comparison';out.mkdir(parents=True,exist_ok=True)
    rows=[]
    for name,mutate in [('correct_shared_trunk',False),('wrong_zero_flow_trunk',True)]:
        src,result=fixture()
        if mutate:
            trunk=next(f for f in result['features'] if f['properties']['id']=='trunk')
            trunk['properties'].update(flow_tph=0.,diameter=50,cost=trunk['properties']['length']*74023)
            refresh_summary(src,result)
        folder=out/name;folder.mkdir(exist_ok=True)
        ip=folder/'input.geojson';rp=folder/'result.geojson'
        ip.write_text(json.dumps(src,indent=2),encoding='utf-8')
        rp.write_text(json.dumps(result,indent=2),encoding='utf-8')
        reports={}
        for label,fn in [('before',before.check),('after',check)]:
            report=fn(ip,rp,'regression_'+name)
            (folder/(label+'.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
            reports[label]={'status':report['status'],'violation_codes':[v['code'] for v in report['violations']],
                            'per_oks_paths':[x['path_segment_ids'] for x in report['per_oks']]}
        rows.append({'case':name,**reports})
    (out/'comparison.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
    print(json.dumps(rows,indent=2))

if __name__=='__main__':main()
