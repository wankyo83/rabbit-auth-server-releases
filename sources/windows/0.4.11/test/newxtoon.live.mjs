import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {Jobs} from '../src/jobs.mjs';

const output=path.resolve(process.argv[2] || 'newxtoon-live-report.json');
const dataDir=path.join(path.dirname(output),'newxtoon-live-data');
const chapterUrl='https://newxtoon1.com/comics/14531/chapters/998410';
const executablePath=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../.tools/rabbit-windows-browsers/chromium-1234/chrome-win64/chrome.exe');
const jobs=new Jobs({dataDir,concurrency:1,launchOptions:{headless:true,executablePath}});
const started=Date.now();

try {
  await jobs.init();
  const job=jobs.open({url:chapterUrl,kind:'images',requestId:crypto.randomUUID()});
  while (true) {
    const current=jobs.get(job.id);
    if (current.state==='ready') break;
    if (current.state==='failed') throw new Error(current.error || 'browser_job_failed');
    if (Date.now()-started>90000) throw new Error('live_test_timeout');
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  const manifest=jobs.manifest(job.id);
  assert.equal(manifest.referer,chapterUrl);
  assert.ok(manifest.pages.length>=10);
  const first=manifest.pages[0].urls[0];
  const response=await fetch(first,{headers:{'User-Agent':manifest.userAgent,Referer:manifest.referer}});
  assert.ok(response.ok,`image HTTP ${response.status}`);
  const bytes=(await response.arrayBuffer()).byteLength;
  assert.ok(bytes>1000);
  const report={testedAt:new Date().toISOString(),chapterUrl,pages:manifest.pages.length,referer:manifest.referer,firstImageBytes:bytes};
  await fs.writeFile(output,JSON.stringify(report,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify(report,null,2));
} finally {
  await jobs.close();
}
