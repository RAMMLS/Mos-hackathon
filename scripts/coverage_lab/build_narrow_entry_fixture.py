"""Construct and check a full 1/1 SYNTHETIC regression via the actual Java kernel.
The route beyond its entry is a fixed clear L-corridor of this fixture, not a general solver.
"""
import json
import math
import sys
from pathlib import Path
from pyproj import Transformer
from shapely.geometry import Polygon

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from coverage_lab.verify_entry_kernel import analyze, nearest_all, boundary_segments, compile_kernel, execute
from benchmark_checker import check, to_metric, DOCUMENT_NEAREST_V1, ruleset_hash

INV=Transformer.from_crs(32637,4326,always_xy=True)
TX,TY=415000.,6170000.

def ll(p):
    return [round(x,9) for x in INV.transform(TX+p[0],TY+p[1])]

def f(id_,kind,geom,coords,**attrs):
    return {'type':'Feature','geometry':None if geom is None else {'type':geom,'coordinates':coords},
            'properties':{'id':id_,'object_type':kind,**attrs}}

def build(out):
    out.mkdir(parents=True,exist_ok=True)
    ring=[(-5,-5),(0,-5),(0,2.2),(1,2.2),(1,10),(11.4,10),
          (11.4,-5),(16,-5),(16,20),(-5,20),(-5,-5)]
    owner=Polygon(ring);p=(-1.,0.)
    q=nearest_all(p,boundary_segments(owner))[0]
    case=analyze(p,owner,q,5.255+.05,'narrow')
    rows,_,_=execute([case],compile_kernel())
    interval=rows['narrow'][0];t=sum(interval)/2
    a=(p[0]+case['u'][0]*t,p[1]+case['u'][1]*t)
    root=(20.,-20.)
    path=[ll(p),ll(a),ll((a[0],-20.)),ll(root)]
    length=sum(math.dist(to_metric(x),to_metric(y)) for x,y in zip(path,path[1:]))
    cost=length*89748
    source={'type':'FeatureCollection','features':[
        f('demand','oks_connection_point','Point',ll(p),flow_tph=15.),
        f('owner','restriction','Polygon',[[ll(v) for v in ring]],restriction_type='oks'),
        f('old','heat_network','LineString',[ll((20.,-40.)),ll((20.,0.))],diameter=100),
        f('root','heat_chamber','Point',ll(root)),
        f('source','source','Point',ll((20.,-40.))),
    ]}
    result={'type':'FeatureCollection','features':[
        f('pipe','heat_network','LineString',path,variant_id='v',start_node_id='demand',end_node_id='root',
          flow_tph=15.,diameter=100,length=length,laying_method='base',depth_start=None,depth_end=None,cost=cost),
        f('summary','variant_summary',None,None,variant_id='v',rank=1,construction_cost=cost+5e6,
          chamber_construction_cost=0.,existing_chamber_tie_in_count=1,existing_chamber_tie_in_cost=5e6,
          unconnected_penalty=0.,calculated_cost=cost+5e6,new_network_length=length,
          score=.7*(cost+5e6)/25e6+.3*length/100,unconnected_oks_ids=[],
          diagnostics=['SYNTHETIC_REGRESSION: Java interval kernel; fixed external corridor; not a full solver run',
                       'ruleset='+DOCUMENT_NEAREST_V1,'ruleset_hash='+ruleset_hash(DOCUMENT_NEAREST_V1)])]}
    inp=out/'input.geojson';res=out/'result.geojson'
    inp.write_text(json.dumps(source,indent=2),encoding='utf-8')
    res.write_text(json.dumps(result,indent=2),encoding='utf-8')
    report=check(inp,res,'continuous_entry_regression',DOCUMENT_NEAREST_V1)
    (out/'checker-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    if report['status']!='VALID' or report['geometry_metrics']['connected_oks_count']!=1:
        raise AssertionError(report['violations'])
    print('SYNTHETIC VALID 1/1; length=',length,'portal=',a,'violations=0')
    return source,result,report

if __name__=='__main__':build(ROOT/'data/benchmark_fixtures/continuous_nearest_entry')
