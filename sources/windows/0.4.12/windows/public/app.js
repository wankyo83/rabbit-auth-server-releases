const $=id=>document.getElementById(id);
async function call(route,data){const r=await fetch(route,{cache:'no-store',headers:{'Content-Type':'application/json','X-Lab-Request':'1'},...(data?{method:'POST',body:JSON.stringify(data)}:{})});const v=await r.json();if(!r.ok)throw Error(v.error||'연결 실패');return v;}
const labels={queued:'대기 중',authenticating:'인증·주소 확보 중',ready:'주소 확보 완료',failed:'확인 필요'};
let selected='';
async function refresh(){try{const h=await call('/health');$('health').textContent=`${h.ready?'정상':'시작 중'} · ${h.version} · 동시 인증 ${h.concurrency}개`;
const {jobs}=await call('/v1/jobs');$('jobs').replaceChildren();if(!jobs.length)$('jobs').textContent='대기 중인 회차가 없습니다.';
for(const job of jobs){const row=document.createElement('div');row.className='job';const span=document.createElement('span');span.textContent=`${job.site} · ${job.id.slice(0,8)} · ${labels[job.state]||job.state}${job.error?' · '+job.error:''}`;row.append(span);
const view=document.createElement('button');view.textContent='인증 화면 보기';view.disabled=!job.browserOpen;view.onclick=()=>{selected=job.id;$('viewer').hidden=false;$('desktop').src='/viewer.html?job='+job.id;};row.append(view);
const close=document.createElement('button');close.textContent='이 작업 종료';close.onclick=async()=>{try{await call(`/v1/jobs/${job.id}/close`,{});await refresh();}catch(e){$('error').textContent=e.message;}};row.append(close);$('jobs').append(row);}
if(selected&&!jobs.some(j=>j.id===selected&&j.browserOpen))hide();$('error').textContent='';}catch(e){$('error').textContent=e.message;}finally{setTimeout(refresh,2000);}}
function hide(){selected='';$('desktop').removeAttribute('src');$('viewer').hidden=true;}$('hide').onclick=hide;refresh();
