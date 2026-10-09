#!/usr/bin/env python3
"""Boot the packaged app twice against a disposable MySQL database.
Requires DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD and a Java 21 runtime.
Never run this against a production database. A test account is removed on success.
"""
from pathlib import Path
import os,subprocess,time,json,urllib.request,uuid,socket,tempfile,base64,secrets
root=Path(__file__).resolve().parent.parent
jar=root/'target/done-api-0.0.1-SNAPSHOT.jar'
if not jar.exists(): raise SystemExit('Build first: ./mvnw package')
if not os.environ.get('DATABASE_URL'): raise SystemExit('Set DATABASE_URL to a disposable MySQL database')
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
env=dict(os.environ,PORT=str(port),JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),APPLE_ENABLED='false')
java=str(Path(env['JAVA_HOME'])/'bin/java') if env.get('JAVA_HOME') else 'java'
process=None
log=tempfile.TemporaryFile(mode='w+b')
def call(method,path,payload=None,token=None):
    body=None if payload is None else json.dumps(payload).encode()
    headers={'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=body,headers=headers,method=method)
    with urllib.request.urlopen(req,timeout=15) as res:
        raw=res.read();return json.loads(raw) if raw else None

def start():
    global process
    process=subprocess.Popen([java,'-jar',str(jar),'--logging.level.root=WARN'],cwd=root,env=env,stdout=log,stderr=log)
    for _ in range(80):
        if process.poll() is not None:
            log.seek(0);raise RuntimeError(log.read().decode())
        try:call('GET','/actuator/health');return
        except Exception:time.sleep(.25)
    raise RuntimeError('Startup timeout')
def stop():
    global process
    if process:
        process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.wait()
        process=None
email='restart-smoke-'+str(uuid.uuid4())+'@example.com'
password=secrets.token_urlsafe(24)
try:
    start()
    auth=call('POST','/api/v1/auth/register',{'email':email,'password':password,'displayName':'Restart test'})
    token=auth['accessToken'];ids=[]
    for kind in ['washer','dryer','dishwasher','oven','hob']:
        a=call('POST','/api/v1/appliances',{'kind':kind},token)
        session=call('POST','/api/v1/appliances/'+a['id']+'/sessions',{'programId':a['programs'][0]['id']},token)
        ids.append((a['id'],session['id']))
    before=call('GET','/api/v1/state',token=token)
    stop();start()
    auth=call('POST','/api/v1/auth/login',{'email':email,'password':password})
    after=call('GET','/api/v1/state',token=auth['accessToken'])
    assert before['revision']==after['revision']
    assert [(a['id'],a['activeSession']['id']) for a in after['appliances']]==ids
    assert len(after['recentSessions'])==5
    call('DELETE','/api/v1/me',{'password':password},auth['accessToken'])
    print('PASS: real JAR, Flyway, HTTP auth, five appliance sessions and state restored after restart.')
finally:stop();log.close()
