"""Compile the new dependency-free Java kernel and verify it using Shapely.

Does NOT start Spring or claim that a portal equals a connection to a supply root.
The unchanged competition input and its ruleset are never rewritten.
"""
import argparse
import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from shapely.geometry import Point, LineString, shape, Polygon
from shapely.ops import transform, nearest_points
from pyproj import Transformer

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from benchmark_checker import CATALOG, required_centerline_clearance, DOCUMENT_NEAREST_V1, ruleset_hash

FWD = Transformer.from_crs(4326,32637,always_xy=True)


def pieces(g):
    if g.geom_type in ('LineString','LinearRing'):
        yield g
    elif hasattr(g,'geoms'):
        for part in g.geoms: yield from pieces(part)


def boundary_segments(owner):
    return [(tuple(a),tuple(b)) for line in pieces(owner.boundary)
            for a,b in zip(line.coords,list(line.coords)[1:]) if math.dist(a,b)>1e-9]


def nearest_all(p, segments):
    q=[]
    for a,b in segments:
        dx,dy=b[0]-a[0],b[1]-a[1]
        t=max(0.,min(1.,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy)))
        v=(a[0]+t*dx,a[1]+t*dy);q.append((math.dist(p,v),v))
    minimum=min(d for d,_ in q)
    result=[]
    for d,v in q:
        if d<=minimum+.001 and all(math.dist(v,z)>.001 for z in result):result.append(v)
    return sorted(result)


def analyze(p, owner, q, radius, id_):
    distance=math.dist(p,q)
    if distance<1e-8:raise ValueError('boundary target requires a separate directional treatment')
    u=((q[0]-p[0])/distance,(q[1]-p[1])/distance)
    minx,miny,maxx,maxy=owner.bounds
    horizon=max(math.dist(p,c) for c in [(minx,miny),(minx,maxy),(maxx,miny),(maxx,maxy)])+20
    ray=LineString([p,(p[0]+u[0]*horizon,p[1]+u[1]*horizon)])
    spans=[]
    for line in pieces(ray.intersection(owner)):
        t=[(x-p[0])*u[0]+(y-p[1])*u[1] for x,y in line.coords]
        if max(t)-min(t)>1e-6: spans.append([min(t),max(t)])
    spans.sort()
    material=[]
    for a,b in spans:
        if material and a<=material[-1][1]+1e-6:material[-1][1]=max(material[-1][1],b)
        else:material.append([a,b])
    if not material or material[0][0]>.001:raise ValueError('target outside initial material')
    exit_=material[0][1]; reentry=material[1][0] if len(material)>1 else None
    return {'id':id_, 'origin':list(p), 'u':list(u), 'from':exit_+.001,
            'to':(reentry-.001 if reentry is not None else horizon),
            'r':radius, 'segments':boundary_segments(owner),
            'boundary_point':q,'boundary_distance_m':distance,
            'first_exit_m':exit_,'first_reentry_m':reentry,
            'gap_m':None if reentry is None else reentry-exit_,
            'clearance_upper_bound_m':None if reentry is None else (reentry-exit_)/2}


def compile_kernel():
    dest=ROOT/'target'/'offline-interval-tests';dest.mkdir(parents=True,exist_ok=True)
    sources=[ROOT/'src/main/java/ru/moshackathon/heatnetwork/solver/RayClearanceIntervals.java',
             ROOT/'scripts/offline_java/RayClearanceCli.java']
    subprocess.run(['javac','--release','11','-d',str(dest),*[str(s) for s in sources]],check=True)
    return dest


def execute(cases, dest):
    tokens=[str(len(cases))]
    for c in cases:
        tokens.append(' '.join(map(str,[c['id'],*c['origin'],*c['u'],c['from'],c['to'],c['r'],len(c['segments'])])))
        tokens += [' '.join(map(str,(*a,*b))) for a,b in c['segments']]
    input_text='\n'.join(tokens)+'\n'
    p=subprocess.run(['java','-cp',str(dest),'ru.moshackathon.heatnetwork.solver.RayClearanceCli'],
                     input=input_text,text=True,capture_output=True,check=True)
    result={}
    for line in p.stdout.splitlines():
        items=line.split();n=int(items[1]);result[items[0]]=[tuple(map(float,items[2+2*j:4+2*j])) for j in range(n)]
    if len(result)!=len(cases):raise AssertionError('incomplete Java output')
    return result,input_text,p.stdout


def audit(input_path,out):
    out.mkdir(parents=True,exist_ok=True)
    raw=input_path.read_bytes();data=json.loads(raw.decode('utf-8-sig'))
    ftrs=data['features']
    obstacles=[(str(f['properties']['id']), transform(FWD.transform,shape(f['geometry'])))
               for f in ftrs if f['properties'].get('restriction_type')=='oks']
    infrastructure=[transform(FWD.transform,shape(f['geometry'])) for f in ftrs
                    if f['properties']['object_type'] in ('heat_network','heat_chamber')]
    cases=[];targets=[]
    for f in ftrs:
        props=f['properties']
        if props['object_type']!='oks_connection_point':continue
        id_=str(props['id']);p=tuple(FWD.transform(*f['geometry']['coordinates']))
        owners=[(id_,g) for id_,g in obstacles if g.covers(Point(p))]
        if len(owners)!=1:raise ValueError(f'{id_}: ambiguous owner or no owner; no union exception')
        oid,owner=owners[0]
        flow=props['flow_tph'];diameter=next(r[0] for r in CATALOG if r[1]>=flow)
        radius=required_centerline_clearance('oks',diameter)
        target={'id':id_,'owner_id':oid,'flow_tph':flow,'minimum_flow_diameter':diameter,
                'required_clearance_m':radius,'rays':[]}
        for index,q in enumerate(nearest_all(p,boundary_segments(owner))):
            case=analyze(p,owner,q,radius,f'oks_{id_}_{index}')
            cases.append(case);target['rays'].append(case['id'])
        targets.append(target)
    dest=compile_kernel();computed,text,stdout=execute(cases,dest)
    (out/'java_input.txt').write_text(text,encoding='utf-8')
    (out/'java_output.txt').write_text(stdout,encoding='utf-8')
    sample_assertions=0
    details={}
    for c in cases:
        valid_intervals=computed[c['id']]
        p,u=c['origin'],c['u']
        segments=[LineString(s) for s in c['segments']]
        for lo,hi in valid_intervals:
            for f in (.0001,.25,.5,.75,.9999):
                t=lo+(hi-lo)*f;point=Point(p[0]+u[0]*t,p[1]+u[1]*t)
                clearance=min(point.distance(s) for s in segments)
                if clearance+2e-7<c['r']:raise AssertionError((c['id'],clearance,c['r']))
                sample_assertions+=1
        free=LineString([(p[0]+u[0]*c['first_exit_m'],p[1]+u[1]*c['first_exit_m']),
                         (p[0]+u[0]*c['to'],p[1]+u[1]*c['to'])])
        root_distance=min(g.distance(free) for g in infrastructure)
        details[c['id']]={k:v for k,v in c.items() if k!='segments'}
        details[c['id']].update(free_clearance_intervals=valid_intervals,minimum_existing_root_distance_m=root_distance)
    blocked=[]
    for target in targets:
        rays=[details[x] for x in target['rays']]
        blocked_here=all(r['clearance_upper_bound_m'] is not None
                         and r['clearance_upper_bound_m']+.01<r['r']
                         and r['minimum_existing_root_distance_m']>.25 for r in rays)
        target['status']='FIXED_NEAREST_ENTRY_BLOCKED' if blocked_here else 'LOCAL_ENTRY_NOT_RULED_OUT'
        target['positive_width_entry_interval_exists']=any(r['free_clearance_intervals'] for r in rays)
        if blocked_here:blocked.append(target['id'])
    report={'input_sha256':hashlib.sha256(raw).hexdigest(),'ruleset':DOCUMENT_NEAREST_V1,
            'ruleset_hash':ruleset_hash(DOCUMENT_NEAREST_V1),'blocked_ids':blocked,
            'scope':'all fixed equal nearest straight rays; ordinary clearance or an existing root required',
            'full_solver_run':False,'java_kernel_compiled_and_executed':True,
            'shapely_cross_checks':sample_assertions,'target_count':len(targets),
            'targets':targets,'ray_details':details}
    (out/'entry_audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:report[k] for k in ['input_sha256','target_count','blocked_ids','java_kernel_compiled_and_executed','shapely_cross_checks']},ensure_ascii=False))
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--input',type=Path,default=ROOT/'data/tz_update_2026_09_19/corrected_dataset.geojson')
    parser.add_argument('--out',type=Path,default=ROOT/'results/local_verified_2026_09_29/entry_audit')
    args=parser.parse_args();audit(args.input,args.out)
