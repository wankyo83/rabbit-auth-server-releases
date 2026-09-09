import os from 'node:os';

export function validateConfig(c, key) {
  if (!c || typeof c !== 'object' || !Number.isInteger(c.port) || c.port < 1024 || c.port > 65535)
    throw Error('포트는 1024~65535 사이의 숫자여야 합니다.');
  if (typeof key !== 'string' || !/^[\x21-\x7e]{4,128}$/.test(key) || key.startsWith('replace-with-'))
    throw Error('접속 키는 공백 없는 숫자·영문·기호 4~128자로 입력하세요.');
  if (c.concurrency !== 2) throw Error('이 배포판의 동시 인증 작업 수는 2개입니다.');
  return c;
}

// Address discovery is for the GUI only, not a restriction on incoming names.
// MagicDNS aliases can differ from os.hostname() and change after startup.
export function localAddresses(interfaces=os.networkInterfaces()) {
  const addresses=[...new Set(Object.values(interfaces).flat().filter(x=>x && !x.internal && x.family==='IPv4').map(x=>x.address))];
  return {addresses};
}
