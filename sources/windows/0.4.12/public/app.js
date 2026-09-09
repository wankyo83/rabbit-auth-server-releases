const $=id=>document.getElementById(id);
async function call(route,data) {
  const response=await fetch(route,{cache:'no-store',headers:{'Content-Type':'application/json','X-Lab-Request':'1'},
    ...(data ? {method:'POST',body:JSON.stringify(data)} : {})});
  const value=await response.json(); if (!response.ok) throw Error(value.error || '연결 실패'); return value;
}
const labels={queued:'대기 중',authenticating:'인증/주소 확보 중',ready:'주소 확보 완료',failed:'확인 필요'};
async function refresh() {
  try {
    const h=await call('/health'); $('health').textContent=`${h.ready?'정상':'시작 중'} · 서버 ${h.version} · 동시 작업 ${h.concurrency}개`;
    const {jobs}=await call('/v1/jobs'); $('jobs').replaceChildren();
    if (!jobs.length) $('jobs').textContent='대기 중인 회차가 없습니다.';
    for (const job of jobs) {
      const row=document.createElement('div'); row.className='job';
      const label=document.createElement('span'); label.textContent=`${job.site} · ${job.id.slice(0,8)} · ${labels[job.state]||job.state}${job.error?' · '+job.error:''}`;
      row.append(label);
      const view=document.createElement('button'); view.textContent='브라우저 보기'; view.disabled=!job.browserOpen;
      view.onclick=async()=>{try{await call(`/v1/jobs/${job.id}/focus`,{});$('viewer').hidden=false;
        if (!$('desktop').getAttribute('src')) $('desktop').src='/desktop/vnc.html?autoconnect=true&resize=scale&path=desktop/websockify';
      }catch(e){$('error').textContent=e.message;}};row.append(view);
      const close=document.createElement('button'); close.textContent='이 작업 종료';
      close.onclick=async()=>{try{await call(`/v1/jobs/${job.id}/close`,{});await refresh();}catch(e){$('error').textContent=e.message;}};row.append(close);$('jobs').append(row);
    }
    $('error').textContent='';
  }catch(e){$('error').textContent=e.message;}
}
$('hide').onclick=()=>{$('desktop').removeAttribute('src');$('viewer').hidden=true;};
await refresh();setInterval(refresh,2000);
