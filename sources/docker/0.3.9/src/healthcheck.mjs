const headers={Host:'localhost:9870'};
if (process.env.SERVER_AUTH !== 'none') headers.Authorization='Bearer '+process.env.SERVER_KEY;
try {
  const response=await fetch('http://127.0.0.1:9870/health',{headers,signal:AbortSignal.timeout(5000)});
  const health=await response.json();
  process.exit(response.ok && health.ready && health.service==='rabbit-auth-server' ? 0 : 1);
} catch { process.exit(1); }
