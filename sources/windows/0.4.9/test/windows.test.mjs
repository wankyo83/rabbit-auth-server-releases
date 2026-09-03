import test from 'node:test';import assert from 'node:assert/strict';import http from 'node:http';import crypto from 'node:crypto';
import {validateConfig,localAddresses} from '../windows/config.mjs';import {createViewer,validateInput} from '../windows/viewer.mjs';import {createServer} from '../src/server.mjs';
test('Windows settings validate ports and printable four-character keys',()=>{
  for(const key of ['0123','ab!?','a'.repeat(128)])assert(validateConfig({port:9870,concurrency:2},key));
  for(const key of ['', '123','abc\n','has space','replace-with-your-key'])assert.throws(()=>validateConfig({port:9870,concurrency:2},key));
  for(const port of [0,80,65536,'9870'])assert.throws(()=>validateConfig({port,concurrency:2},'test'));
});
test('Windows address discovery is display-only, not a Host restriction',()=>{
  const n=localAddresses({eth:[{internal:false,family:'IPv4',address:'10.123.123.123'}],ts:[{internal:false,family:'IPv4',address:'100.80.1.2'}]});
  assert.deepEqual(n.addresses,['10.123.123.123','100.80.1.2']);assert(!('allowedHosts' in n));
});
test('Viewer accepts bounded input, rejects arbitrary keyboard shortcuts/script/invalid coordinates',()=>{
  for(const d of [{type:'click',x:100,y:200},{type:'wheel',deltaY:-650},{type:'key',key:'Enter'},{type:'text',text:'1234'}])assert.equal(validateInput(d),d);
  for(const d of [{type:'click',x:Infinity,y:1},{type:'wheel',deltaY:99999},{type:'key',key:'Control+L'},{type:'evaluate',text:'alert(1)'},{type:'text',text:'x'.repeat(501)}])assert.throws(()=>validateInput(d));
});
test('Windows viewer requires auth and same-origin, emits page-only JPEG, closes with job',async()=>{
  const id=crypto.randomUUID(),events=[];let closed=false;
  const page={isClosed:()=>closed,screenshot:async()=>Buffer.from('fake-jpeg'),mouse:{click:async(x,y)=>events.push([x,y])}};
  const jobs={ready:true,concurrency:2,get:()=>({id,page})};
  const s=createServer({password:'test',jobs,allowedHosts:new Set(['*']),viewer:createViewer(jobs,new URL('../windows/public/',import.meta.url)),version:'0.4.4-windows'});
  await new Promise(r=>s.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+s.address().port;const headers={Authorization:'Bearer test','Content-Type':'application/json','X-Lab-Request':'1'};
  try{
    assert.equal((await fetch(base+`/v1/jobs/${id}/frame`)).status,401);
    assert.equal((await fetch(base+`/v1/jobs/${id}/frame`,{headers:{...headers,Origin:'http://evil.test'}})).status,403);
    const frame=await fetch(base+`/v1/jobs/${id}/frame`,{headers});assert.equal(frame.status,200);assert.equal(frame.headers.get('content-type'),'image/jpeg');
    assert.equal((await fetch(base+'/viewer.html',{headers})).status,200);
    assert.equal((await fetch(base+'/desktop/vnc.html',{headers})).status,404);
    assert.equal((await fetch(base+`/v1/jobs/${id}/input`,{method:'POST',headers,body:JSON.stringify({type:'click',x:20,y:30})})).status,200);assert.deepEqual(events,[[20,30]]);
    closed=true;assert.equal((await fetch(base+`/v1/jobs/${id}/frame`,{headers})).status,409);
  }finally{s.closeAllConnections();await new Promise(r=>s.close(r));}
});
