"""Regression fixtures for whole-path connectivity, not just the first chamber."""
import copy
import json
import math
import tempfile
import unittest
from pathlib import Path
from benchmark_checker import check, to_metric, chamber_cost_for_diameter


def feature(id_, kind, coordinates, geom_type='Point', **attrs):
    return {'type':'Feature', 'properties':{'id':id_, 'object_type':kind, **attrs},
            'geometry':None if geom_type is None else {'type':geom_type,'coordinates':coordinates}}


def fixture():
    # Geographically isolated, synthetic fixture; no edited competition geometry.
    p1=[37.6000,55.7000]; p2=[37.6000,55.6995]
    j=[37.6010,55.7000]; r=[37.6020,55.7000]
    source={'type':'FeatureCollection','features':[
        feature('p1','oks_connection_point',p1,flow_tph=3.),
        feature('p2','oks_connection_point',p2,flow_tph=3.),
        feature('old','heat_network',[[37.6020,55.6990],[37.6020,55.7010]],'LineString',diameter=100),
        feature('root','heat_chamber',r),
    ]}
    result={'type':'FeatureCollection','features':[
        feature('junction','heat_chamber',j,variant_id='v',diameter=65,cost=3_000_000.),
    ]}
    for id_,a,b,pts,q,d,price in [
        ('e1','p1','junction',[p1,j],3.,50,74023),
        ('e2','p2','junction',[p2,j],3.,50,74023),
        ('trunk','junction','root',[j,r],6.,65,78631),
    ]:
        length=sum(math.dist(to_metric(x),to_metric(y)) for x,y in zip(pts,pts[1:]))
        result['features'].append(feature(id_,'heat_network',pts,'LineString',variant_id='v',
            start_node_id=a,end_node_id=b,flow_tph=q,diameter=d,length=length,
            cost=length*price,laying_method='base',depth_start=None,depth_end=None))
    refresh_summary(source,result)
    return source,result


def refresh_summary(source,result):
    result['features']=[f for f in result['features'] if f['properties']['object_type']!='variant_summary']
    edges=[f for f in result['features'] if f['properties']['object_type']=='heat_network']
    chambers=[f for f in result['features'] if f['properties']['object_type']=='heat_chamber']
    oldids={str(f['properties']['id']) for f in source['features'] if f['properties']['object_type']=='heat_chamber'}
    chamber=sum(f['properties']['cost'] for f in chambers)
    tie=sum(str(f['properties']['end_node_id']) in oldids for f in edges)
    cost=sum(f['properties']['cost'] for f in edges)+chamber+5e6*tie
    length=sum(f['properties']['length'] for f in edges)
    result['features'].append(feature('summary','variant_summary',None,None,variant_id='v',rank=1,
        construction_cost=cost,chamber_construction_cost=chamber,
        existing_chamber_tie_in_count=tie,existing_chamber_tie_in_cost=5e6*tie,
        unconnected_penalty=0.,calculated_cost=cost,new_network_length=length,
        score=.7*cost/25e6+.3*length/100,unconnected_oks_ids=[]))


class RootCertificationTest(unittest.TestCase):
    def run_check(self, src, result):
        with tempfile.TemporaryDirectory() as td:
            a=Path(td)/'input.geojson';b=Path(td)/'result.geojson'
            a.write_text(json.dumps(src),encoding='utf-8');b.write_text(json.dumps(result),encoding='utf-8')
            return check(a,b,'root-regression')

    def test_shared_trunk_is_in_both_paths_and_has_sum_flow(self):
        r=self.run_check(*fixture())
        self.assertEqual('VALID',r['status'],r['violations'])
        for oks in r['per_oks']:
            self.assertIn('trunk',oks['path_segment_ids'])
            self.assertEqual('root',oks['tie_in_id'])

    def test_zero_flow_trunk_must_not_get_false_certificate(self):
        src,out=fixture()
        e=next(f for f in out['features'] if f['properties']['id']=='trunk')
        e['properties'].update(flow_tph=0.,diameter=50,cost=e['properties']['length']*74023)
        refresh_summary(src,out)
        r=self.run_check(src,out)
        self.assertIn('SEGMENT_FLOW_MISMATCH',{v['code'] for v in r['violations']})

    def test_disconnected_junction_is_not_a_supply_root(self):
        src,out=fixture();out['features']=[f for f in out['features'] if f['properties']['id']!='trunk']
        refresh_summary(src,out)
        r=self.run_check(src,out)
        self.assertEqual(0,r['geometry_metrics']['connected_oks_count'])
        self.assertIn('OKS_PATH_MISSING',{v['code'] for v in r['violations']})

    def test_numeric_node_id_zero_is_allowed(self):
        src,out=fixture()
        for f in src['features']:
            if f['properties']['id']=='p1':f['properties']['id']=0
        for f in out['features']:
            if f['properties'].get('start_node_id')=='p1':f['properties']['start_node_id']=0
        r=self.run_check(src,out)
        self.assertEqual('VALID',r['status'],r['violations'])

    def test_new_terminal_chamber_on_old_pipe_is_valid_root(self):
        src,out=fixture();src['features']=[f for f in src['features'] if f['properties']['id']!='root']
        out['features'].append(feature('root','heat_chamber',[37.602,55.7],variant_id='v',diameter=100,cost=3e6))
        refresh_summary(src,out)
        r=self.run_check(src,out)
        self.assertEqual('VALID',r['status'],r['violations'])

    def test_partial_is_not_marked_as_full_expectation(self):
        src,out=fixture()
        out['features']=[f for f in out['features'] if f['properties']['id']!='e2']
        trunk=next(f for f in out['features'] if f['properties']['id']=='trunk')
        trunk['properties'].update(flow_tph=3.,diameter=50,cost=trunk['properties']['length']*74023)
        next(f for f in out['features'] if f['properties']['id']=='junction')['properties']['diameter']=50
        refresh_summary(src,out)
        summary=next(f for f in out['features'] if f['properties']['object_type']=='variant_summary')['properties']
        summary['unconnected_oks_ids']=['p2'];summary['unconnected_penalty']=101_500_000.
        summary['calculated_cost']=summary['construction_cost']+summary['unconnected_penalty']
        summary['score']=.7*summary['calculated_cost']/25e6+.3*summary['new_network_length']/100
        r=self.run_check(src,out)
        self.assertEqual('VALID',r['status'],r['violations'])
        self.assertEqual('PARTIAL',r['solution_status'])
        self.assertFalse(r['complete'])
        self.assertFalse(r['case_expectations_met'])

    def test_ambiguous_sourceward_parent_is_rejected(self):
        src,out=fixture()
        e=copy.deepcopy(next(f for f in out['features'] if f['properties']['id']=='trunk'))
        e['properties']['id']='extra_parent'
        out['features'].append(e);refresh_summary(src,out)
        r=self.run_check(src,out)
        self.assertIn('MULTIPLE_SOURCEWARD_PARENTS',{v['code'] for v in r['violations']})

    def test_cycle_is_rejected_before_connectivity_is_claimed(self):
        src,out=fixture()
        e=copy.deepcopy(next(f for f in out['features'] if f['properties']['id']=='trunk'))
        e['geometry']['coordinates'].reverse()
        e['properties'].update(id='back',start_node_id='root',end_node_id='junction')
        out['features'].append(e);refresh_summary(src,out)
        r=self.run_check(src,out)
        self.assertIn('NEW_NETWORK_CYCLE',{v['code'] for v in r['violations']})

if __name__=='__main__':unittest.main()
