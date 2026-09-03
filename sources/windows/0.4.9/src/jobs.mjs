import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';
import { LabError, publicUrl, imageRows } from './core.mjs';
import { chapter, readViewer, normalizeRows, complete } from './adapters.mjs';

export class Jobs {
  constructor({dataDir='/data', concurrency=2, worker, now=Date.now, launchOptions={}} = {}) {
    if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 4) throw new Error('Concurrency must be 1..4');
    this.dataDir=dataDir; this.concurrency=concurrency; this.worker=worker || (job => this.collect(job)); this.now=now;
    this.jobs=new Map(); this.queue=[]; this.active=0; this.browser=null; this.ready=false; this.stopping=false;
    this.stateWrites=new Map();
    this.launchOptions=launchOptions;
  }
  async init() {
    await fs.mkdir(path.join(this.dataDir,'sessions'), {recursive:true, mode:0o700});
    this.browser=await chromium.launch({headless:false, chromiumSandbox:process.env.BROWSER_SANDBOX === 'true',
      args:['--window-size=1280,900','--no-first-run'], ...this.launchOptions});
    this.ready=true;
    this.timer=setInterval(() => this.expire(),10000).unref();
  }
  open(data) {
    const target=chapter(data.url, data.kind);
    if (!/^[a-f0-9-]{36}$/.test(data.requestId || '')) throw new LabError(400,'request_id_required');
    const old=[...this.jobs.values()].find(j => j.requestId === data.requestId);
    if (old) {
      if (old.url !== target.url || old.kind !== target.kind) throw new LabError(409,'request_id_conflict');
      return this.snapshot(old);
    }
    if (this.stopping || this.jobs.size >= 64 || this.queue.length >= 32) throw new LabError(429,'auth_queue_full');
    const job={...target,id:crypto.randomUUID(),requestId:data.requestId,state:'queued',created:this.now(),expires:this.now()+120000,
      result:null,error:null,context:null,page:null,cancelled:false};
    this.jobs.set(job.id,job); this.queue.push(job); this.pump(); return this.snapshot(job);
  }
  snapshot(job) {
    return {id:job.id,state:job.state,site:new URL(job.url).hostname,kind:job.kind,error:job.error,
      browserOpen:!!job.page,expected:job.result?.expected || null,
      queuePosition:job.state === 'queued' ? this.queue.indexOf(job)+1 : 0};
  }
  get(id) { const j=this.jobs.get(id); if (!j) throw new LabError(404,'job_not_found'); return j; }
  pump() {
    while (!this.stopping && this.active < this.concurrency && this.queue.length) {
      const job=this.queue.shift(); if (job.cancelled) continue;
      this.active++; job.state='authenticating';
      Promise.resolve().then(() => this.worker(job)).then(result => {
        if (!job.cancelled) { job.result=result; job.state='ready'; job.expires=this.now()+120000; }
      }).catch(error => {
        if (!job.cancelled) { job.error=error instanceof LabError ? error.code : 'browser_job_failed'; job.state='failed'; job.expires=this.now()+60000; }
      }).finally(async () => {
        await job.context?.close().catch(() => {}); job.context=null; job.page=null;
        this.active--; this.pump();
      });
    }
  }
  manifest(id) { const j=this.get(id); if (j.state !== 'ready') throw new LabError(409,'job_not_ready'); return {...j.result,id:j.id,chapterUrl:j.url}; }
  async closeJob(id) {
    const j=this.get(id); j.cancelled=true; j.state='closed'; this.queue=this.queue.filter(other => other !== j);
    await j.context?.close().catch(() => {}); this.jobs.delete(id);
  }
  expire() {
    for (const j of this.jobs.values()) if (j.expires < this.now()) this.closeJob(j.id).catch(() => {});
  }
  async close() {
    this.stopping=true; this.ready=false; clearInterval(this.timer);
    await Promise.all([...this.jobs.keys()].map(id => this.closeJob(id).catch(() => {})));
    await this.browser?.close();
  }
  async saveState(job) {
    if (!job.context || job.cancelled) return;
    const key=crypto.createHash('sha256').update(job.origin).digest('hex');
    const location=path.join(this.dataDir,'sessions',key+'.json');
    const state=await job.context.storageState();
    const host=new URL(job.url).hostname;
    // Persist only first-party cookies. No local storage, passwords, third-party sessions or exports.
    const safe={cookies:state.cookies.filter(c => c.domain.replace(/^\./,'') === host),origins:[]};
    const pending=(this.stateWrites.get(key) || Promise.resolve()).catch(() => {}).then(async () => {
      const tmp=location+'.'+job.id+'.tmp';
      await fs.writeFile(tmp,JSON.stringify(safe),{mode:0o600}); await fs.rename(tmp,location);
    });
    this.stateWrites.set(key,pending); await pending;
    if (this.stateWrites.get(key) === pending) this.stateWrites.delete(key);
  }
  async collect(job) {
    if (!(await publicUrl(job.url))) throw new LabError(400,'chapter_host_not_public');
    const key=crypto.createHash('sha256').update(job.origin).digest('hex');
    let state;
    try { state=JSON.parse(await fs.readFile(path.join(this.dataDir,'sessions',key+'.json'),'utf8')); } catch {}
    const context=await this.browser.newContext({viewport:null,acceptDownloads:false,serviceWorkers:'block',storageState:state});
    job.context=context;
    if (job.cancelled) { await context.close(); throw new LabError(409,'cancelled'); }
    await context.route('**/*',async route => {
      const request=route.request();
      if (await publicUrl(request.url())) await route.continue(); else await route.abort('blockedbyclient');
    });
    await context.routeWebSocket('**/*',socket => socket.close());
    const page=await context.newPage(); job.page=page;
    page.on('download',d => d.cancel().catch(() => {}));
    page.on('dialog',d => d.dismiss().catch(() => {}));
    page.on('popup',p => p.close().catch(() => {}));
    let rows=[], expected=null, lastSignature='', stableSince=0;
    page.on('response',response => {
      const url=new URL(response.url());
      if (url.origin !== job.origin || !/^\/api\/(webtoon|manhwa|manga)-images\/?$/.test(url.pathname) || response.status() !== 200) return;
      (async () => {
        if (page.url().split('#')[0] !== job.url || Number(response.headers()['content-length'] || 0)>2*1024*1024) return;
        const raw=await response.body(); if (raw.length>2*1024*1024 || job.cancelled) return;
        const parsed=imageRows(JSON.parse(raw),job.url);
        const normalized=normalizeRows(parsed.rows,job.url);
        if (complete(normalized,parsed.total)) { rows=normalized; expected=parsed.total; }
      })().catch(() => {});
    });
    await page.goto(job.url,{waitUntil:'domcontentloaded',timeout:30000}).catch(() => {});
    const until=this.now()+75000;
    while (!job.cancelled && this.now()<until) {
      if (page.url().split('#')[0] !== job.url) { await new Promise(r => setTimeout(r,1000)); continue; }
      if (!complete(rows,expected)) {
        const dom=await page.evaluate(readViewer,job.family).catch(() => null);
        if (dom?.error) throw new LabError(424,dom.error);
        if (dom?.notice === 'source_upload_pending') {
          await this.saveState(job);
          return {kind:'images',expected:1,referer:refererFor(job),userAgent:await page.evaluate(() => navigator.userAgent),
            notice:'source_upload_pending',pages:[]};
        }
        if (job.kind==='novel' && dom?.text?.trim().length>30) {
          if (dom.text.length>1000000) throw new LabError(413,'novel_too_large');
          await this.saveState(job);
          return {kind:'novel',text:dom.text,title:dom.title};
        }
        const list=normalizeRows(dom?.rows,job.url);
        const signature=JSON.stringify(list);
        if (signature !== lastSignature) {lastSignature=signature; stableSince=this.now();}
        if (complete(list,dom?.expected) && this.now()-stableSince >= 2000) {rows=list; expected=dom.expected;}
      }
      if (complete(rows,expected)) {
        for (const host of new Set(rows.flatMap(r => r.urls.map(u => new URL(u).origin)))) {
          if (!(await publicUrl(host))) throw new LabError(400,'image_host_not_public');
        }
        const userAgent=await page.evaluate(() => navigator.userAgent);
        await this.saveState(job);
        return {kind:'images',expected,referer:refererFor(job),userAgent,pages:rows.sort((a,b)=>a.page-b.page)};
      }
      await new Promise(r => setTimeout(r,750));
    }
    throw new LabError(424,'manual_viewer_confirmation_required');
  }
}

export function refererFor(job) {
  return job.family === 'newxtoon' ? job.url : job.origin + '/';
}
