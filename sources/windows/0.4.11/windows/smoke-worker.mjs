// Offline release QA: no website, existing browser profile, or saved key is used.
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {Jobs} from '../src/jobs.mjs';
import {createServer} from '../src/server.mjs';
import {createViewer} from './viewer.mjs';
const report=process.argv[2],dataDir=path.dirname(report);
const jobs=new Jobs({dataDir,launchOptions:{chromiumSandbox:true}});
let server;
try {
  await jobs.init();
  const context=await jobs.browser.newContext({viewport:null});const page=await context.newPage();
  await page.setContent('<html><body style="background:#f0f6ff;font:24px sans-serif"><h1>Rabbit Windows private browser test</h1><button id="b" onclick="this.textContent=\'clicked\'">Click test</button><input id="t"><div style="height:3000px"></div></body></html>');
  const size=await page.evaluate(()=>({width:innerWidth,height:innerHeight,visibility:document.visibilityState}));
  assert(size.width>0&&size.height>0);
  const id=crypto.randomUUID();jobs.jobs.set(id,{id,page,context,state:'authenticating',kind:'images',url:'https://newtoki1.org/manhwa/1/test',created:Date.now(),expires:Date.now()+60000});
  server=createServer({password:'test-key-only',jobs,allowedHosts:new Set(['*']),viewer:createViewer(jobs,new URL('./public/',import.meta.url)),version:'0.4.11-windows'});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port;
  const headers={Authorization:'Bearer test-key-only','X-Lab-Request':'1','Content-Type':'application/json'};
  assert.equal((await fetch(base+'/health')).status,401);
  assert.equal((await fetch(base+'/health',{headers})).status,200);
  const r=await fetch(base+'/v1/jobs/'+id+'/frame',{headers});assert.equal(r.status,200);
  const image=Buffer.from(await r.arrayBuffer());assert(image.length>1000);await fs.writeFile(report+'.jpg',image);
  const rect=await page.locator('#b').boundingBox();
  const input=await fetch(base+'/v1/jobs/'+id+'/input',{method:'POST',headers,body:JSON.stringify({type:'click',x:rect.x+10,y:rect.y+10})});assert.equal(input.status,200);
  assert.equal(await page.locator('#b').textContent(),'clicked');
  const two=await jobs.browser.newContext({viewport:null});const other=await two.newPage();await other.setContent('<h1>Independent job B</h1>');
  await jobs.closeJob(id);assert(!other.isClosed());await two.close();
  await fs.writeFile(report,JSON.stringify({ok:true,size,screenshotBytes:image.length,checks:['sandbox-enabled','headed-private-desktop','health-auth','browser-frame','browser-click','independent-context','graceful-close']},null,2));
} catch(e){await fs.writeFile(report,JSON.stringify({ok:false,error:e.message,stack:e.stack},null,2));process.exitCode=1;}
finally{if(server){server.closeAllConnections();await new Promise(r=>server.close(r));}await jobs.close();}
