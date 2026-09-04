import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {domainToASCII} from 'node:url';
import {createServer} from '../src/server.mjs';

function request(port, headers, upgrade=false) {
  return new Promise((resolve,reject)=>{
    const req=http.get({hostname:'127.0.0.1',port,setHost:false,path:upgrade?'/desktop/websockify':'/health',
      headers:upgrade?{Connection:'Upgrade',Upgrade:'websocket','Sec-WebSocket-Version':'13',
        'Sec-WebSocket-Key':Buffer.alloc(16).toString('base64'),...headers}:headers},res=>{
      res.resume();res.on('end',()=>resolve(res.statusCode));
    });
    req.on('upgrade',(_res,socket)=>{socket.destroy();reject(Error('Unexpected WebSocket authorization'));});
    req.on('error',reject);req.setTimeout(2000,()=>req.destroy(Error('Test timeout')));
  });
}

test('generic destination Host accepts arbitrary names/IPs only with a valid key',async()=>{
  const jobs={ready:true,concurrency:2};
  const allowedHosts=new Set(['*']);
  assert.throws(()=>createServer({jobs,allowedHosts,authMode:'none'}),/requires SERVER_AUTH=password/);
  assert.throws(()=>createServer({jobs,allowedHosts}),/at least 4/);
  const server=createServer({jobs,allowedHosts,password:'test-key-only'});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;
  const hosts=['localhost','rabbit-auth-server','reader-nas','WANKYO_PC',domainToASCII('쭈니'),
    'my-pc.tail-example.ts.net','10.123.123.123','172.22.9.14','192.168.77.20','100.99.88.77',
    '[::1]','[fd7a:115c:a1e0::1234]'];
  try {
    for(const name of hosts) {
      const Host=name+':9870';
      assert.equal(await request(port,{Host}),401,name+' without key');
      assert.equal(await request(port,{Host,Authorization:'Bearer incorrect'}),401,name+' wrong key');
      assert.equal(await request(port,{Host,Authorization:'Bearer test-key-only'}),200,name+' bearer');
      assert.equal(await request(port,{Host,Authorization:'Basic '+Buffer.from('lab:test-key-only').toString('base64')}),200,name+' basic');
    }
    const headers={Host:'custom-pc:9870',Authorization:'Bearer test-key-only'};
    assert.equal(await request(port,{...headers,Origin:'http://custom-pc:9870'}),200);
    assert.equal(await request(port,{...headers,Origin:'http://another-site.test'}),403);
    assert.equal(await request(port,{...headers,'Sec-Fetch-Site':'cross-site'}),403);
    assert.equal(await request(port,{...headers,Origin:'null'}),403);
    for(const Host of ['', 'user@server:9870','server/path','server\\path','server?query','server#fragment','server:bad','[broken'])
      assert.equal(await request(port,{...headers,Host}),403,'invalid Host '+Host);
    // The desktop transport must not bypass the same authentication/Origin guards.
    assert.equal(await request(port,{Host:'custom-pc:9870'},true),403);
    assert.equal(await request(port,{...headers,Authorization:'Bearer incorrect'},true),403);
    assert.equal(await request(port,{...headers,Origin:'http://another-site.test'},true),403);
    assert.equal(await request(port,{...headers,'Sec-Fetch-Site':'cross-site'},true),403);
    assert.equal(await request(port,{...headers,Host:'user@server'},true),403);
  } finally {server.closeAllConnections();await new Promise(r=>server.close(r));}
});

test('explicit allowlists retain destination restrictions, including no-auth local tests',async()=>{
  const server=createServer({jobs:{ready:true,concurrency:2},authMode:'none',allowedHosts:new Set(['selected-pc'])});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;
  try {
    assert.equal(await request(port,{Host:'selected-pc:9870'}),200);
    assert.equal(await request(port,{Host:'different-pc:9870'}),403);
    assert.equal(await request(port,{Host:'selected-pc:9870',Origin:'https://other.test'}),403);
  } finally {server.closeAllConnections();await new Promise(r=>server.close(r));}
});
