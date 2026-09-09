import fs from 'node:fs/promises';
import {LabError} from '../src/core.mjs';

const keys=new Set(['Enter','Tab','Backspace','Escape','ArrowUp','ArrowDown','ArrowLeft','ArrowRight','PageUp','PageDown','Home','End','Space']);
export function validateInput(data) {
  if (!data || typeof data!=='object') throw new LabError(400,'invalid_input');
  if (data.type==='click' && ['x','y'].every(k=>Number.isFinite(data[k]) && data[k]>=0 && data[k]<=8192)) return data;
  if (data.type==='wheel' && Number.isFinite(data.deltaY) && Math.abs(data.deltaY)<=1200) return data;
  if (data.type==='key' && keys.has(data.key)) return data;
  if (data.type==='text' && typeof data.text==='string' && data.text.length<=500 && !/[\x00-\x08\x0b-\x1f]/.test(data.text)) return data;
  throw new LabError(400,'invalid_input');
}

// All routes run AFTER the shared server's key/Host/Origin checks.
// Only the selected browser page is visible; never capture the Windows desktop.
export function createViewer(jobs, publicDir) {
  const busy=new Set();
  return async ({req,res,url,body,json}) => {
    if (req.method==='GET' && ['/viewer.html','/viewer.js'].includes(url.pathname)) {
      const data=await fs.readFile(new URL('.'+url.pathname,publicDir));
      res.writeHead(200,{'Content-Type':url.pathname.endsWith('.js')?'text/javascript; charset=utf-8':'text/html; charset=utf-8'});
      res.end(data); return true;
    }
    const m=url.pathname.match(/^\/v1\/jobs\/([a-f0-9-]{36})\/(frame|input)$/);
    if (!m) return false;
    const job=jobs.get(m[1]);
    if (!job.page || job.cancelled || job.page.isClosed()) throw new LabError(409,'browser_closed');
    if (busy.has(job.id)) throw new LabError(429,'viewer_busy');
    busy.add(job.id);
    try {
      if (m[2]==='frame' && req.method==='GET') {
        const frame=await job.page.screenshot({type:'jpeg',quality:65,timeout:5000});
        res.writeHead(200,{'Content-Type':'image/jpeg'});res.end(frame); return true;
      }
      if (m[2]==='input' && req.method==='POST') {
        const data=validateInput(await body());
        if (data.type==='click') await job.page.mouse.click(data.x,data.y);
        if (data.type==='wheel') await job.page.mouse.wheel(0,data.deltaY);
        if (data.type==='key') await job.page.keyboard.press(data.key);
        if (data.type==='text') await job.page.keyboard.insertText(data.text);
        json(200,{ok:true}); return true;
      }
      throw new LabError(405,'method_not_allowed');
    } finally {busy.delete(job.id);}
  };
}
