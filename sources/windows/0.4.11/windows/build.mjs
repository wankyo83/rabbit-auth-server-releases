import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const source=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const workspace=path.basename(path.dirname(source)).toLowerCase()==='build-kit'?path.dirname(path.dirname(source)):path.dirname(source);
const target=path.resolve(process.argv[2]||'');
if(!process.argv[2]||!target.startsWith(workspace+path.sep+'build-kit'+path.sep))throw Error('Use a new path under workspace/build-kit');
try{await fs.access(target);throw Error('Build directory already exists');}catch(e){if(e.code!=='ENOENT')throw e;}
await fs.mkdir(target,{recursive:true});
await fs.mkdir(path.join(target,'runtime'),{recursive:true});
await fs.copyFile(process.execPath,path.join(target,'runtime','node.exe'));
await fs.copyFile(path.join(path.dirname(process.execPath),'LICENSE'),path.join(target,'runtime','LICENSE-Node.txt'));
for(const name of ['chromium-1234','ffmpeg-1011','winldd-1007'])
  await fs.cp(path.join(workspace,'.tools','rabbit-windows-browsers',name),path.join(target,'runtime','browsers',name),{recursive:true,errorOnExist:true});
for(const name of ['src','node_modules','package.json','package-lock.json'])
  await fs.cp(path.join(source,name),path.join(target,'server',name),{recursive:true,errorOnExist:true});
const windowsTarget=path.join(target,'server','windows');
await fs.mkdir(windowsTarget,{recursive:true});
await fs.cp(path.join(source,'windows','public'),path.join(windowsTarget,'public'),{recursive:true,errorOnExist:true});
for(const name of ['app.manifest','build.mjs','config.mjs','RabbitServer.cs','ServerForm.Designer.cs','README-Windows.txt','server-smoke.mjs','server.mjs','smoke-worker.mjs','viewer.mjs'])
  await fs.copyFile(path.join(source,'windows',name),path.join(windowsTarget,name));
const extensionIcon=path.join(workspace,'dc-toki-pages-deploy','icon','eu.kanade.tachiyomi.extension.ko.ntk.png');
await fs.copyFile(extensionIcon,path.join(windowsTarget,'newtoki-webtoon-icon.png'));
const manual=path.join(source,'windows','README-Windows.txt');
await fs.copyFile(manual,path.join(target,'Windows-설치방법.md'));
await fs.copyFile(manual,path.join(target,'사용방법.txt'));
const result=spawnSync('C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe',[
  '/nologo','/target:winexe','/platform:x64','/optimize+',
  '/reference:System.Windows.Forms.dll','/reference:System.Drawing.dll','/reference:System.Web.Extensions.dll','/reference:System.Security.dll','/reference:System.IO.Compression.dll','/reference:System.IO.Compression.FileSystem.dll',
  '/win32manifest:'+path.join(source,'windows','app.manifest'),'/resource:'+extensionIcon+',RabbitAuthServer.NewtokiWebtoonIcon','/out:'+path.join(target,'RabbitAuthServer.exe'),path.join(source,'windows','RabbitServer.cs'),path.join(source,'windows','ServerForm.Designer.cs')
],{encoding:'utf8',windowsHide:true});
process.stdout.write(result.stdout||'');process.stderr.write(result.stderr||'');if(result.status!==0)throw Error('C# compilation failed');
const hashes={};
async function walk(dir){for(const item of await fs.readdir(dir,{withFileTypes:true})){const p=path.join(dir,item.name);if(item.isSymbolicLink())throw Error('No links in release');if(item.isDirectory())await walk(p);else hashes[path.relative(target,p).replaceAll('\\','/')]=crypto.createHash('sha256').update(await fs.readFile(p)).digest('hex');}}
await walk(target);await fs.writeFile(path.join(target,'checksums.json'),JSON.stringify({version:'0.4.11-windows',files:hashes},null,2));
console.log(JSON.stringify({target,files:Object.keys(hashes).length}));
