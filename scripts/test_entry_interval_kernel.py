"""Executable numerical tests against the actual new Java class via javac/java."""
import math
import random
import unittest
from shapely.geometry import Point, LineString, Polygon
from coverage_lab.verify_entry_kernel import compile_kernel, execute, analyze, nearest_all, boundary_segments


class EntryIntervalKernelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.dest=compile_kernel()

    def solve(self,p,poly,r):
        q=nearest_all(p,boundary_segments(poly))[0]
        case=analyze(p,poly,q,r,'single')
        rows,_,_=execute([case],self.dest)
        return case,rows['single']

    def test_narrow_window_missed_by_old_metre_probes(self):
        poly=Polygon([(-5,-5),(0,-5),(0,2.2),(1,2.2),(1,10),
                      (11.4,10),(11.4,-5),(16,-5),(16,20),(-5,20)])
        p=(-1,0);r=5.255
        case,windows=self.solve(p,poly,r)
        self.assertEqual(1,len(windows))
        lo,hi=windows[0]
        self.assertLess(hi-lo,1.)
        # Reproduce the OLD generator, using the selected Q plus r + integer offset.
        old_candidates=[case['boundary_distance_m']+r+i for i in range(161)]
        self.assertFalse(any(lo<=t<=hi for t in old_candidates))
        t=(lo+hi)/2
        a=Point(p[0]+case['u'][0]*t,p[1]+case['u'][1]*t)
        self.assertGreaterEqual(a.distance(poly),r-1e-7)

    def test_no_reentry_for_convex_rectangle(self):
        _,windows=self.solve((10,10),Polygon([(0,0),(20,0),(20,20),(0,20)]),5.255)
        self.assertTrue(windows)

    def test_small_courtyard_remains_blocked(self):
        p=Polygon([(0,0),(20,0),(20,20),(0,20)],holes=[[(8,8),(12,8),(12,12),(8,12)]])
        _,windows=self.solve((7,10),p,5.255)
        self.assertFalse(windows)

    def test_corner_capsules_not_infinite_lines(self):
        case={'id':'end','origin':(0,0),'u':(1,0),'from':0.,'to':20.,'r':2.,
              'segments':[((5,0),(5,3))]}
        out,_,_=execute([case],self.dest)
        self.assertEqual([(0.,3.),(7.,20.)],out['end'])

    def test_side_wall_can_block_a_large_first_gap(self):
        case={'id':'wall','origin':(0,0),'u':(1,0),'from':1.,'to':19.,'r':5.,
              'segments':[((0,0),(0,1)),((20,0),(20,1)),((0,1),(20,1))]}
        out,_,_=execute([case],self.dest)
        self.assertFalse(out['wall'])

    def test_diameter_increase_does_not_create_clearance(self):
        cases=[{'id':str(i),'origin':(0,0),'u':(1,0),'from':0.,'to':20.,'r':r,
                'segments':[((0,-10),(0,10)),((20,-10),(20,10))]}
               for i,r in enumerate((5.255,5.3,7.835,10.725))]
        out,_,_=execute(cases,self.dest)
        measures=[sum(b-a for a,b in out[str(i)]) for i in range(len(cases))]
        self.assertEqual(measures,sorted(measures,reverse=True))

    def test_no_160_metre_cutoff(self):
        case={'id':'long','origin':(0,0),'u':(1,0),'from':0.,'to':400.,'r':5.,
              'segments':[((0,1),(250,1))]}
        out,_,_=execute([case],self.dest)
        self.assertTrue(out['long']);self.assertGreater(out['long'][0][0],250.)

    def test_utm_translation_does_not_change_intervals(self):
        c={'id':'base','origin':(0,0),'u':(1,0),'from':0.,'to':20.,'r':2.,
           'segments':[((5,-3),(5,3))]}
        tx,ty=415000.,6170000.
        d={**c,'id':'utm','origin':(tx,ty),'segments':[((5+tx,-3+ty),(5+tx,3+ty))]}
        out,_,_=execute([c,d],self.dest)
        self.assertEqual(out['base'],out['utm'])

    def test_random_capsules_match_independent_segment_distances(self):
        rng=random.Random(290926)
        cases=[]
        for i in range(250):
            angle=rng.uniform(-math.pi,math.pi)
            cases.append({'id':str(i),'origin':(rng.uniform(-3,3),rng.uniform(-3,3)),
                'u':(math.cos(angle),math.sin(angle)), 'from':0.,'to':30.,'r':rng.uniform(.2,5.),
                'segments':[((rng.uniform(-15,30),rng.uniform(-15,30)),
                             (rng.uniform(-15,30),rng.uniform(-15,30))) for _ in range(8)]})
        out,_,_=execute(cases,self.dest)
        comparisons=0
        for c in cases:
            segs=[LineString(s) for s in c['segments']]
            for j in range(121):
                t=30*j/120
                point=Point(c['origin'][0]+c['u'][0]*t,c['origin'][1]+c['u'][1]*t)
                gap=min(point.distance(s) for s in segs)-c['r']
                if abs(gap)<1e-7:continue
                expected=gap>0
                actual=any(lo-1e-8<=t<=hi+1e-8 for lo,hi in out[c['id']])
                self.assertEqual(expected,actual,(c['id'],t,gap,out[c['id']]))
                comparisons+=1
        self.assertEqual(comparisons,30250)

if __name__=='__main__':unittest.main()
