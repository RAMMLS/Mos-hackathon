import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler,HTTPServer
import unittest
import yaml

DELIVERY=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('qa_runner', DELIVERY/'local_iteration/qa_runner.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
class RunnerTests(unittest.TestCase):
 def test_single_keeps_obstacles(self):
  src={'type':'FeatureCollection','features':[
    {'properties':{'id':0,'object_type':'oks_connection_point'}},
    {'properties':{'id':2,'object_type':'oks_connection_point'}},
    {'properties':{'id':'b','object_type':'restriction'}}]}
  out=r.single_target(src,0)
  self.assertEqual([0,'b'],[f['properties']['id'] for f in out['features']])
  out['features'][0]['properties']['id']='changed'
  self.assertEqual(0,src['features'][0]['properties']['id'])
 def test_unknown_target_rejected(self):
  with self.assertRaises(ValueError):r.single_target({'features':[]},5)
 def test_duplicate_target_rejected(self):
  f={'properties':{'id':5,'object_type':'oks_connection_point'}}
  with self.assertRaises(ValueError):r.single_target({'features':[f,f]},5)
 def test_classification_is_coverage_aware(self):
  common={'contract_valid':True,'status':'VALID','geometry_metrics':{'connected_oks_count':14,'total_oks_count':17}}
  self.assertEqual('PARTIAL',r.classify(common))
  common['geometry_metrics']['connected_oks_count']=17
  self.assertEqual('FULL',r.classify(common))
  common['contract_valid']=False
  self.assertEqual('INVALID',r.classify(common))
 def test_multipart_original_bytes_preserved(self):
  src='{"данные":"тест"}'.encode()
  body,typ=r.multipart(src)
  self.assertIn(src,body);self.assertIn(b'name="file"',body)
  self.assertTrue(body.endswith(b'--\r\n'));self.assertIn('boundary=',typ)
 def test_compose_readable_and_no_public_port(self):
  d=yaml.safe_load((DELIVERY/'local_iteration/compose.yml').read_text())
  self.assertEqual({'app','qa','java-test'},set(d['services']))
  self.assertTrue(d['services']['app']['ports'][0].startswith('127.0.0.1:'))
  self.assertNotIn('-DskipTests',d['services']['java-test']['command'])
  self.assertIn('-DskipTests=false',d['services']['java-test']['command'])
 def test_http_error_saved(self):
  class H(BaseHTTPRequestHandler):
   def do_POST(self):
    self.rfile.read(int(self.headers['Content-Length']))
    self.send_response(422);self.end_headers();self.wfile.write(b'{"status":"NO_CERTIFIED_SOLUTION"}')
   def log_message(self,*a):pass
  server=HTTPServer(('127.0.0.1',0),H);t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
  try:
   with tempfile.TemporaryDirectory() as td:
    p=Path(td);src=p/'in.json';src.write_text('{"type":"FeatureCollection","features":[]}')
    v=r.run_case(f'http://127.0.0.1:{server.server_port}/api/trace',src,'B3',1000,p/'case')
    self.assertEqual('HTTP_ERROR',v['status']);self.assertEqual(422,v['http_status'])
    self.assertTrue((p/'case/http-error-body.txt').exists());self.assertTrue((p/'case/case-summary.json').exists())
  finally:server.shutdown();server.server_close();t.join()
 def test_valid_response_passed_to_real_checker(self):
  # A local mock transports a previously constructed synthetic project; this is not a solver run.
  testroot=DELIVERY
  sys.path.insert(0,str(testroot/'scripts'))
  from test_certification_roots import fixture
  src_data,out_data=fixture()
  result=json.dumps(out_data).encode()
  class H(BaseHTTPRequestHandler):
   def do_POST(self):
    self.rfile.read(int(self.headers['Content-Length']))
    self.send_response(200);self.send_header('X-Ruleset-Id',r.RULESET)
    self.send_header('X-Solution-Complete','true');self.end_headers();self.wfile.write(result)
   def log_message(self,*a):pass
  server=HTTPServer(('127.0.0.1',0),H);t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
  oldroot=r.ROOT;r.ROOT=testroot
  try:
   with tempfile.TemporaryDirectory() as td:
    p=Path(td);src=p/'in.json';src.write_text(json.dumps(src_data))
    v=r.run_case(f'http://127.0.0.1:{server.server_port}/api/trace',src,'B3',1000,p/'case')
    self.assertEqual('FULL',v['status'],v);self.assertEqual(0,v['violation_count'])
    self.assertTrue(v['api_checker_coverage_agree']);self.assertTrue((p/'case/checker-report.json').exists())
  finally:r.ROOT=oldroot;server.shutdown();server.server_close();t.join()
if __name__=='__main__':unittest.main(verbosity=2)
