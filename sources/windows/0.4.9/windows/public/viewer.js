const id=new URLSearchParams(location.search).get('job'),img=document.getElementById('frame'),state=document.getElementById('state');
let closed=!/^[a-f0-9-]{36}$/.test(id||''),objectUrl='',pending=Promise.resolve();
// Serialize screenshots and input so a click cannot collide with this viewer's capture.
// Always recover the queue after a failed request; never replay an uncertain input.
function enqueue(work){const result=pending.then(work);pending=result.catch(e=>{state.textContent=e.message;});return pending;}
function input(data){return enqueue(async()=>{if(closed)return;const r=await fetch(`/v1/jobs/${id}/input`,{method:'POST',headers:{'Content-Type':'application/json','X-Lab-Request':'1'},body:JSON.stringify(data)});if(!r.ok)throw Error(r.status===429?'다른 화면이 사용 중입니다. 입력을 다시 눌러주세요.':'입력 실패: '+r.status);});}
async function refresh(){if(closed)return;await enqueue(async()=>{if(closed)return;const r=await fetch(`/v1/jobs/${id}/frame`,{cache:'no-store'});if([404,409].includes(r.status)){closed=true;state.textContent='작업이 완료되었거나 종료되었습니다.';return;}if(!r.ok)throw Error('화면 대기: '+r.status);const next=URL.createObjectURL(await r.blob());const old=objectUrl;objectUrl=next;img.src=next;if(old)URL.revokeObjectURL(old);state.textContent='클릭·스크롤·키 입력 가능 (화면은 약 1초마다 갱신)';});if(!closed)setTimeout(refresh,900);}
img.onclick=e=>{const r=img.getBoundingClientRect();input({type:'click',x:(e.clientX-r.left)*img.naturalWidth/r.width,y:(e.clientY-r.top)*img.naturalHeight/r.height});img.focus();};
img.onwheel=e=>{e.preventDefault();input({type:'wheel',deltaY:Math.max(-1000,Math.min(1000,e.deltaY))});};
img.onkeydown=e=>{const allowed=['Enter','Tab','Backspace','Escape','ArrowUp','ArrowDown','ArrowLeft','ArrowRight','PageUp','PageDown','Home','End'];if(allowed.includes(e.key)){e.preventDefault();input({type:'key',key:e.key});}};
document.getElementById('up').onclick=()=>input({type:'wheel',deltaY:-650});document.getElementById('down').onclick=()=>input({type:'wheel',deltaY:650});
document.getElementById('tab').onclick=()=>input({type:'key',key:'Tab'});document.getElementById('enter').onclick=()=>input({type:'key',key:'Enter'});
document.getElementById('send').onclick=()=>{const t=document.getElementById('text');input({type:'text',text:t.value});t.value='';};
addEventListener('pagehide',()=>{closed=true;if(objectUrl)URL.revokeObjectURL(objectUrl);});refresh();
