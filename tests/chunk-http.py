#!/usr/bin/env python3
"""Real localhost HTTP integration; host Java MOCK_IMPORTER, no models loaded.
Protocol is configurable with --protocol JSON. Run --self-test without UploadStore.
"""
import argparse, concurrent.futures, hashlib, http.client, json, os, pathlib, random, shutil, socket, subprocess, sys, time, zipfile
M=1024*1024
DEFAULT=dict(base='/api/v1/uploads', create='POST', chunk='PUT', status='GET', cancel='DELETE', complete='POST', chunk_path='/{id}/chunks/{index}', complete_path='/{id}/complete', id='id', name='name', size='total_size', sha='sha256', count='chunks', chunk_size='chunkSize', chunk_hash='sha256', file='file', create_ok=[200,201], chunk_ok=[200,201,204], complete_ok=[200,201,202], cancel_ok=[200,204], missing=[404,410], bad=[400,409,422], expiry_seconds=0)
def digest(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  while b:=f.read(M):h.update(b)
 return h.hexdigest()
def fixture(path, random_data, size):
 rng=random.Random(47291);h=hashlib.sha256()
 with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=1,allowZip64=True) as z:
  with z.open('mock-model.pth','w',force_zip64=True) as out:
   for offset in range(0,size,M):
    b=rng.randbytes(min(M,size-offset)) if random_data else (b'compressed-mock-model\0'*60000)[:min(M,size-offset)]
    out.write(b);h.update(b)
 return digest(path)
class Suite:
 def __init__(self,args):
  self.a=args;self.p=DEFAULT.copy()
  if args.protocol:self.p.update(json.loads(pathlib.Path(args.protocol).read_text()))
  self.root=pathlib.Path(args.work);self.root.mkdir(parents=True,exist_ok=True);self.log=open(self.root/'report.jsonl','a',buffering=1);self.proc=None;self.checks=0
 def record(self,**kw):self.log.write(json.dumps(dict(time=time.time(),**kw))+'\n');print(json.dumps(kw),flush=True)
 def http(self,method,path,fields=None,data=None):
  parts=[data if data is not None else json.dumps(fields or {}).encode()];c=http.client.HTTPConnection('127.0.0.1',self.port,timeout=120)
  try:
   c.putrequest(method,path);c.putheader('Content-Type','application/octet-stream' if data is not None else 'application/json');c.putheader('Content-Length',sum(map(len,parts)));c.endheaders()
   for part in parts:
    for off in range(0,len(part),65536):c.send(part[off:off+65536])
   r=c.getresponse();body=r.read(1024*1024);return r.status,json.loads(body) if body else {}
  finally:c.close()
 def expect(self,result,codes,label):
  status,body=result
  if status not in codes:raise AssertionError(f'{label}: {status} {body}; expected {codes}')
  self.checks+=1;self.record(check=label,status=status);return body
 def command(self,s):
  self.proc.stdin.write(s+'\n');self.proc.stdin.flush();line=self.proc.stdout.readline().strip()
  if not line:raise RuntimeError('Harness terminated')
  self.record(command=s,response=line);return line
 def start(self):
  with socket.socket() as s:s.bind(('127.0.0.1',0));self.port=s.getsockname()[1]
  err=open(self.root/'java.log','a')
  self.proc=subprocess.Popen(['java','-Xmx192m','-cp',self.a.classpath,'ChunkHttpTest',str(self.root/'store'),str(self.port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True)
  line=self.proc.stdout.readline().strip();self.record(harness=line)
  if not line.startswith('READY'):raise RuntimeError('Java harness failed; see java.log')
 def create(self,path,sha,name='mock-role'):
  p=self.p;n=path.stat().st_size
  body=self.expect(self.http(p['create'],p['base'],{'filename':path.name,p['name']:name,p['size']:n,p['sha']:sha,p['count']:(n+8*M-1)//(8*M),p['chunk_size']:8*M}),p['create_ok'],'create')
  for key in p['id'].split('.'):body=body[key]
  return str(body)
 def chunk(self,uid,index,b,sha=None):
  p=self.p;return self.http(p['chunk'],p['base']+p['chunk_path'].format(id=uid,index=index),{p['chunk_hash']:sha or hashlib.sha256(b).hexdigest()},b)
 def status(self,uid):return self.http(self.p['status'],self.p['base']+'/'+uid)
 def complete(self,uid,name='mock-role'):return self.http(self.p['complete'],self.p['base']+self.p['complete_path'].format(id=uid),{'name':name})
 def wait_done(self,uid,expected='succeeded'):
  deadline=time.monotonic()+180
  while time.monotonic()<deadline:
   status,b=self.status(uid)
   if b.get('state') in ['succeeded','failed','cancelled']:
    if b['state']!=expected:raise AssertionError(b)
    self.record(check='async '+expected,status=status);return
   time.sleep(.1)
  raise TimeoutError('async completion')
 def cancel(self,uid):return self.http(self.p['cancel'],self.p['base']+'/'+uid)
 def upload(self,path,sha,label):
  p=self.p;uid=self.create(path,sha,label);n=(path.stat().st_size+8*M-1)//(8*M)
  with open(path,'rb') as f:first=f.read(8*M)
  # Per-chunk hash is not in the production protocol; whole-file hash tested below.
  self.expect(self.chunk(uid,0,first),p['chunk_ok'],'first chunk')
  self.expect(self.chunk(uid,0,first),p['chunk_ok'],'duplicate retry')
  changed=bytes([first[0]^1])+first[1:]
  self.expect(self.chunk(uid,0,changed),[409],'conflicting duplicate')
  self.command('restart');self.expect(self.status(uid),[200],'persistent after restart')
  order=list(range(1,n));random.Random(71).shuffle(order)
  def send(i):
   with open(path,'rb') as f:f.seek(i*8*M);b=f.read(8*M)
   return i,self.chunk(uid,i,b)
  with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
   for i,res in pool.map(send,order):self.expect(res,p['chunk_ok'],f'out-of-order concurrent {i}')
  self.command('busy')
  try:self.expect(self.complete(uid),[409],'Busy409')
  finally:self.command('idle')
  before=self.command('imports');self.expect(self.complete(uid),p['complete_ok'],'complete '+label);self.wait_done(uid);after=self.command('imports')
  if int(after.split()[-1])!=int(before.split()[-1])+1:raise AssertionError('MOCK_IMPORTER must run exactly once')
  self.expect(self.complete(uid),p['complete_ok']+[404,409,410],'complete retry')
  if self.command('imports')!=after:raise AssertionError('duplicate completion re-imported')
 def run(self):
  files=[]
  try:
   self.start()
   for random_data,label in [(True,'random-zip'),(False,'compressed-zip')]:
    path=self.root/(label+'.zip');files.append(path);sha=fixture(path,random_data,self.a.mib*M)
    self.record(fixture=label,zip_bytes=path.stat().st_size,uncompressed_bytes=self.a.mib*M,sha256=sha,mock_importer=True)
    self.upload(path,sha,label)
   path=files[-1];sha=digest(path);uid=self.create(path,sha,'cancel-me');self.expect(self.cancel(uid),self.p['cancel_ok'],'cancel');assert self.expect(self.status(uid),[200],'cancel state')['state']=='cancelled'
   self.expect(self.chunk(uid,0,b'x'),[409],'cancel blocks chunks')
   uid=self.create(path,sha,'paths')
   for bad in ['../escape','/absolute','..\\escape']:
    self.expect(self.complete(uid,bad),[400,422],'unsafe name '+bad)
   self.expect(self.status('%2e%2e'),[400,404,422],'unsafe upload id')
   self.command('expire '+uid);self.expect(self.status(uid),self.p['missing'],'expired after metadata age + restart')
   uid=self.create(path,'0'*64,'bad-whole-hash')
   with open(path,'rb') as f:
    i=0
    while b:=f.read(8*M):self.expect(self.chunk(uid,i,b),self.p['chunk_ok'],'bad-hash upload');i+=1
   self.expect(self.complete(uid),self.p['complete_ok'],'bad hash queued');self.wait_done(uid,'failed')
   ttl=True
   self.record(result='PASS',checks=self.checks,large=self.a.mib>500,expiry_tested=bool(ttl))
  finally:
   if self.proc:
    try:self.proc.stdin.write('quit\n');self.proc.stdin.flush();self.proc.wait(timeout=15)
    except Exception:self.proc.kill();self.proc.wait()
   for path in files:path.unlink(missing_ok=True)
   shutil.rmtree(self.root/'store',ignore_errors=True)
   self.log.close()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--work',default=os.environ.get('TEST_WORK', '.build/http-tests'));ap.add_argument('--protocol');ap.add_argument('--mib',type=int,default=513);ap.add_argument('--classpath',default='tests/business-classes:'+os.environ.get('JSON_JAR', 'libs/json.jar'));ap.add_argument('--self-test',action='store_true');a=ap.parse_args()
 if a.self_test:
  root=pathlib.Path(a.work);root.mkdir(parents=True,exist_ok=True)
  for rnd in [True,False]:
   p=root/'fixture-selftest.zip'
   try:
    sha=fixture(p,rnd,3*M)
    with zipfile.ZipFile(p) as z:assert z.testzip() is None;assert z.getinfo('mock-model.pth').file_size==3*M
    assert len(sha)==64
   finally:p.unlink(missing_ok=True)
  print('PASS streaming random/compressed ZIP fixture self-test');return
 Suite(a).run()
if __name__=='__main__':main()
