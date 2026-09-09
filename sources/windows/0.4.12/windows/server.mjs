import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {Jobs} from '../src/jobs.mjs';
import {createServer} from '../src/server.mjs';
import {validateConfig,localAddresses} from './config.mjs';
import {createViewer} from './viewer.mjs';

const configPath=process.argv[2];
const c=validateConfig(JSON.parse(await fs.readFile(configPath,'utf8')),process.env.SERVER_KEY);
const root=path.dirname(fileURLToPath(import.meta.url));
const publicDir=new URL('./public/',import.meta.url);
const statusPath=configPath+'.status';
const stopPath=configPath+'.stop';
const logPath=path.join(c.dataDir,'server.log');
await fs.mkdir(c.dataDir,{recursive:true});
async function log(message) {
  try {if ((await fs.stat(logPath)).size>2*1024*1024) await fs.rename(logPath,logPath+'.previous');}catch{}
  await fs.appendFile(logPath,new Date().toISOString()+' '+message+'\n').catch(()=>{});
}
const network=localAddresses();
const jobs=new Jobs({dataDir:c.dataDir,concurrency:2,launchOptions:{chromiumSandbox:true}});
const server=createServer({password:process.env.SERVER_KEY,jobs,allowedHosts:new Set(['*']),
  publicDir:fileURLToPath(publicDir),viewer:createViewer(jobs,publicDir),version:'0.4.12-windows'});
let stopping=false,initialization;
async function status(state,error='') {
  const text=JSON.stringify({state,error,pid:process.pid,port:c.port,addresses:network.addresses});
  await fs.writeFile(statusPath+'.tmp',text);await fs.rename(statusPath+'.tmp',statusPath);
}
async function stop() {
  if(stopping)return;stopping=true;clearInterval(timer);server.close();server.closeIdleConnections();
  await initialization?.catch(()=>{});await jobs.close();await status('stopped');await log('Server stopped');process.exit(0);
}
process.on('SIGINT',stop);process.on('SIGTERM',stop);
const timer=setInterval(async()=>{
  // Local launcher owns this randomly named control file. Never a remote HTTP shutdown endpoint.
  try {if(await fs.readFile(stopPath,'utf8')==='stop')await stop();}catch{}
  if(c.parentPid){try{process.kill(c.parentPid,0);}catch{await stop();}}
},500);
try {
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(c.port,'0.0.0.0',resolve);});
  await status('starting');await log('Browser starting (private desktop, sandbox enabled)');
  initialization=jobs.init();await initialization;
  if(!stopping){await status('ready');await log('Ready; protocol=1; URL + scoped cookie headers; concurrency=2');}
} catch(e) {
  const reason=e.code==='EADDRINUSE'?'포트가 이미 사용 중입니다. 다른 포트를 선택하세요.':
    e.code==='EACCES'?'이 포트에 연결할 권한이 없습니다. 다른 포트를 선택하세요.':'브라우저 시작 실패. runtime 폴더 누락 또는 보안 프로그램 차단을 확인하세요.';
  await status('failed',reason);await log('Startup failed: '+(e.code||e.name||'Error'));
  clearInterval(timer);await jobs.close();server.close();process.exit(1);
}
