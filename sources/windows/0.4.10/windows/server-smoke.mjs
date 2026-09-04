// Offline QA of the actual Windows worker startup, auth, port conflict and stop path.
import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import http from 'node:http';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const report=process.argv[2],dir=path.dirname(report),children=[],run=path.basename(report).replace(/[^a-z0-9]/gi,'-');
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function availablePort(){const s=net.createServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function hostStatus(port,Host,key) {
  return new Promise((resolve,reject)=>{
    const req=http.get({hostname:'127.0.0.1',port,path:'/health',headers:{Host,...(key?{Authorization:'Bearer '+key}:{})}},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));});
    req.on('error',reject);req.setTimeout(2000,()=>req.destroy(Error('Host check timeout')));
  });
}
async function launch(name,port){
  name=run+'-'+name;const config=path.join(dir,name+'.json');
  await fs.writeFile(config,JSON.stringify({port,concurrency:2,dataDir:path.join(dir,name),parentPid:process.pid}));
  const p=spawn(process.execPath,[fileURLToPath(new URL('./server.mjs',import.meta.url)),config],{
    stdio:'ignore',windowsHide:true,env:{...process.env,SERVER_KEY:'test-key-only',PLAYWRIGHT_BROWSERS_PATH:path.join(dir,'runtime','browsers')}
  });
  children.push(p);p.on('error',e=>{p.launchError=e;});return {p,config};
}
async function state(child,wanted){
  for(let i=0;i<150;i++){
    let s;try{s=JSON.parse(await fs.readFile(child.config+'.status','utf8'));}catch{}
    if(s?.state===wanted)return s;
    if(s?.state==='failed')throw Error(s.error);
    if(child.p.exitCode!==null||child.p.launchError)throw Error('Worker exited before '+wanted);
    await wait(200);
  }throw Error('Timed out waiting for '+wanted);
}
async function exited(p){for(let i=0;i<300 && p.exitCode===null;i++)await wait(100);assert.notEqual(p.exitCode,null);}
try {
  const port=await availablePort(),a=await launch('worker-a',port),ready=await state(a,'ready');
  const base='http://127.0.0.1:'+port,headers={Authorization:'Bearer test-key-only'};
  assert.equal((await fetch(base+'/health')).status,401);
  const h=await fetch(base+'/health',{headers});assert.equal(h.status,200);const health=await h.json();
  assert.equal(health.version,'0.4.10-windows');assert.equal(health.protocol,1);
  for(const host of ['custom-pc.tail-example.ts.net','10.123.123.123','100.99.88.77','WANKYO_PC']) {
    assert.equal(await hostStatus(port,host+':9870','test-key-only'),200);
    assert.equal(await hostStatus(port,host+':9870'),401);
  }
  assert.equal((await fetch(base+'/',{headers})).status,200);
  assert.equal((await fetch(base+'/viewer.js',{headers})).status,200);
  assert.equal((await fetch(base+'/v1/jobs',{headers})).status,200);
  const b=await launch('worker-conflict',port);await state(b,'failed');await exited(b.p);assert.equal(b.p.exitCode,1);
  // A failed second worker must not interrupt the first server.
  assert.equal((await fetch(base+'/health',{headers})).status,200);
  await fs.writeFile(a.config+'.stop','stop');await exited(a.p);assert.equal(a.p.exitCode,0);
  assert.equal(JSON.parse(await fs.readFile(a.config+'.status','utf8')).state,'stopped');
  await wait(1000);const c=await launch('worker-restart',port);await state(c,'ready');await fs.writeFile(c.config+'.stop','stop');await exited(c.p);
  await fs.writeFile(report,JSON.stringify({ok:true,health,discoveredAddressCount:ready.addresses.length,checks:['real-worker','auth-required','generic-hosts-key-required','protocol-compatible','management-assets','port-conflict-safe','graceful-stop','same-port-restart']},null,2));
}catch(e){await fs.writeFile(report,JSON.stringify({ok:false,error:e.message,stack:e.stack},null,2));process.exitCode=1;}
finally{for(const p of children)if(p.exitCode===null)p.kill();}
