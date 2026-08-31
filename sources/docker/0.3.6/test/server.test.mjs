import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import http from 'node:http';
import fs from 'node:fs/promises';
import {Jobs} from '../src/jobs.mjs';
import {chapter,readViewer,normalizeRows,complete} from '../src/adapters.mjs';
import {publicIp,publicUrl,imageRows} from '../src/core.mjs';
import {createServer} from '../src/server.mjs';
const tick=()=>new Promise(r=>setImmediate(r));
const data=(n=1)=>({url:`https://newtoki1.org/manhwa/1/chapter-${n}`,requestId:crypto.randomUUID(),kind:'images'});

test('password minimum: reject missing/under-four/example values; accept four, eight and long keys',async()=>{
  const jobs={ready:true,concurrency:2};
  for(const password of [undefined,'','1','12','123','replace-with-your-key','replace-with-at-least-32-random-characters'])
    assert.throws(()=>createServer({password,jobs}),/at least 4/);
  for(const password of ['0123','k7P2','a7B3c9D2','a'.repeat(40)]) {
    const server=createServer({password,jobs});
    await new Promise(r=>server.listen(0,'127.0.0.1',r));
    const url=`http://127.0.0.1:${server.address().port}/health`;
    try {
      assert.equal((await fetch(url)).status,401);
      assert.equal((await fetch(url,{headers:{Authorization:'Bearer wrong-key'}})).status,401);
      for(const Authorization of ['Bearer '+password,'Basic '+Buffer.from('lab:'+password).toString('base64')]) {
        const response=await fetch(url,{headers:{Authorization}});
        assert.equal(response.status,200);
        assert.equal((await response.json()).version,'0.3.6');
      }
    } finally {server.closeAllConnections();await new Promise(r=>server.close(r));}
  }
  const server=createServer({authMode:'none',jobs});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  try {assert.equal((await fetch(`http://127.0.0.1:${server.address().port}/health`)).status,200);}
  finally {server.closeAllConnections();await new Promise(r=>server.close(r));}
});

test('single YAML installation: literal port, no env file, sandbox off, four-character startup validation',async()=>{
  const compose=await fs.readFile(new URL('../docker-compose.yml',import.meta.url),'utf8');
  assert(compose.includes('"9870:9870"'));
  assert(!compose.includes('${'));
  assert(!compose.includes('env_file:'));
  assert(compose.includes('BROWSER_SANDBOX: "false"'));
  assert(compose.includes('ALLOWED_HOSTS: "*"'));
  assert(compose.includes('image: ghcr.io/wankyo83/rabbit-auth-server:stable'));
  assert(compose.includes('pull_policy: always'));
  assert(!compose.includes('build: .'));
  assert(!compose.includes('192.168.0.7'));
  for(const name of ['compose.yaml','.env.example'])
    await assert.rejects(fs.access(new URL('../'+name,import.meta.url)),{code:'ENOENT'});
  const dockerfile=await fs.readFile(new URL('../Dockerfile',import.meta.url),'utf8');
  assert(dockerfile.includes('BROWSER_SANDBOX=false'));
  const jobs=await fs.readFile(new URL('../src/jobs.mjs',import.meta.url),'utf8');
  assert(jobs.includes("chromiumSandbox:process.env.BROWSER_SANDBOX === 'true'"));
  const entrypoint=await fs.readFile(new URL('../entrypoint.sh',import.meta.url),'utf8');
  assert(entrypoint.includes('"${#lab_password_value}" -lt 4'));
  assert(entrypoint.includes('== replace-with-*'));
});
test('independent jobs: two active, per-chapter queue, close A does not cancel B',async()=>{
  const release=new Map();const j=new Jobs({worker:job=>new Promise(r=>release.set(job.id,r))});
  const a=j.open(data(1)),b=j.open(data(2)),c=j.open(data(3));await tick();
  assert.equal(j.active,2);assert.equal(j.get(c.id).state,'queued');
  await j.closeJob(a.id);release.get(a.id)({expected:1});await tick();await tick();
  assert.equal(j.get(b.id).state,'authenticating');assert.equal(j.get(c.id).state,'authenticating');
  release.get(b.id)({expected:1});release.get(c.id)({expected:1});await tick();await tick();
  assert.equal(j.get(b.id).state,'ready');assert.equal(j.active,0);await j.close();
});
test('idempotency, URL mismatch, queue limit and expiry',async()=>{
  let clock=1;const j=new Jobs({now:()=>clock,worker:async()=>({expected:1})});
  const d=data(),a=j.open(d);assert.equal(j.open(d).id,a.id);
  assert.throws(()=>j.open({...d,url:data(2).url}),/conflict/);
  await tick();await tick();clock=200000;j.expire();await tick();assert.equal(j.jobs.size,0);await j.close();
});
test('chapter families follow supplied domain; private/credential/unknown URLs rejected',()=>{
  for(const host of ['newtoki123.org','toki42.com','sbxh11.com','blacktoon421.com','jjaptoon6.com','11toon149.com','wfwf400.com','comic.naver.com'])
    assert.equal(chapter(`https://${host}/manhwa/1/chapter-1`,'images').origin,`https://${host}`);
  for(const u of ['http://newtoki1.org/manhwa/1/a','https://newtoki1.org.evil.com/manhwa/1/a','https://127.0.0.1/a','https://user:pass@newtoki1.org/manhwa/1/a','https://newtoki1.org:8443/manhwa/1/a'])
    assert.throws(()=>chapter(u,'images'));
});
test('Goodtoon numbered domains and chapter paths are accepted without opening list endpoints',()=>{
  for(const path of ['/manga/gt-14556/1/','/manga/gt-14556/chapter-2040/']) {
    const result=chapter(`https://www.goodtoon002.com${path}`,'images');
    assert.equal(result.family,'goodtoon');assert.equal(result.origin,'https://www.goodtoon002.com');
  }
  for(const path of ['/manga/gt-14556/ajax/chapters/','/manga/gt-14556/','/?q=test'])
    assert.throws(()=>chapter(`https://www.goodtoon002.com${path}`,'images'));
});
test('no partial list: order, total, duplicates and unsafe image URL',()=>{
  const rows=normalizeRows([{page:1,urls:['https://cdn.example/1.png']},{page:2,urls:['//cdn.example/2.png']}],'https://newtoki1.org');
  assert(complete(rows,2));assert(!complete(rows,3));assert(!complete([rows[0],rows[0]],2));
  assert.equal(normalizeRows([{page:1,urls:['file:///etc/passwd','javascript:alert(1)']}],'https://newtoki1.org').length,0);
  const api=imageRows({ok:true,images:[{page:1,src:'https://cdn.example/1.png'}]},'https://newtoki1.org');assert.equal(api.total,1);
});
test('source upload pending notice is detected before viewer image collection',()=>{
  const previous=globalThis.document;
  globalThis.document={body:{innerText:'이미지 처리 중인 회차입니다.\n잠시 후 다시 확인해주세요.'}};
  try {assert.deepEqual(readViewer('toki'),{notice:'source_upload_pending'});}
  finally {if (previous === undefined) delete globalThis.document; else globalThis.document=previous;}
});
test('public destination guard blocks all private/linklocal records',async()=>{
  for(const ip of ['127.0.0.1','192.168.0.7','100.79.100.62','169.254.169.254','::1','::ffff:127.0.0.1','fc00::1'])assert.equal(publicIp(ip),false,ip);
  assert.equal(await publicUrl('https://example.com',async()=>[{address:'1.1.1.1'},{address:'127.0.0.1'}]),false);
});
test('HTTP auth, host guard, CSRF, no image relay, secret-free status',async()=>{
  const jobs=new Jobs({worker:async()=>({kind:'images',expected:1,pages:[{page:1,urls:['https://cdn.example/image?token=secret']}],userAgent:'UA',referer:'https://newtoki1.org/'})});jobs.ready=true;
  const password='a'.repeat(40);const server=createServer({password,jobs});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}`;
  const headers={Authorization:'Bearer '+password,'Content-Type':'application/json','X-Lab-Request':'1'};
  try {
    assert.equal((await fetch(base+'/health')).status,401);
    const badHostStatus=await new Promise((resolve,reject)=>{
      const req=http.get(base+'/health',{headers:{...headers,Host:'evil.test'}},res=>{res.resume();resolve(res.statusCode);});req.on('error',reject);
    });
    assert.equal(badHostStatus,403);
    assert.equal((await fetch(base+'/health',{headers:{...headers,Origin:'https://evil.test'}})).status,403);
    assert.equal((await fetch(base+'/health',{headers})).status,200);
    const opened=await (await fetch(base+'/v1/jobs',{method:'POST',headers,body:JSON.stringify(data())})).json();await tick();await tick();
    const status=await (await fetch(base+`/v1/jobs/${opened.id}`,{headers})).text();assert(!status.includes('token=secret'));
    assert.equal((await fetch(base+`/v1/jobs/${opened.id}/images/1`,{headers})).status,404);
    const manifest=await (await fetch(base+`/v1/jobs/${opened.id}/manifest`,{method:'POST',headers,body:'{}'})).json();assert.equal(manifest.expected,1);
    assert.equal((await fetch(base+'/v1/jobs',{method:'POST',headers:{Authorization:headers.Authorization},body:'{}'})).status,415);
  } finally {server.closeAllConnections();await new Promise(r=>server.close(r));await jobs.close();}
});
test('pending manifest returns one fixed public PNG address without exposing API access',async()=>{
  const jobs=new Jobs({worker:async()=>({kind:'images',expected:1,pages:[],notice:'source_upload_pending',userAgent:'UA',referer:'https://newtoki1.org/'})});jobs.ready=true;
  const password='test-key';const server=createServer({password,jobs});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}`;
  const headers={Authorization:'Bearer '+password,'Content-Type':'application/json','X-Lab-Request':'1'};
  try {
    const opened=await (await fetch(base+'/v1/jobs',{method:'POST',headers,body:JSON.stringify(data())})).json();await tick();await tick();
    const manifest=await (await fetch(base+`/v1/jobs/${opened.id}/manifest`,{method:'POST',headers,body:'{}'})).json();
    assert.equal(manifest.notice,'source_upload_pending');assert.equal(manifest.expected,1);assert.equal(manifest.pages.length,1);
    assert.equal(manifest.pages[0].urls[0],'https://raw.githubusercontent.com/wankyo83/rabbit-auth-server-releases/main/assets/source-upload-pending.png');
    const asset=await fetch(base+'/assets/source-upload-pending.png');assert.equal(asset.status,200);assert.equal(asset.headers.get('content-type'),'image/png');
    assert.equal((await fetch(base+'/health')).status,401);
  } finally {server.closeAllConnections();await new Promise(r=>server.close(r));await jobs.close();}
});
