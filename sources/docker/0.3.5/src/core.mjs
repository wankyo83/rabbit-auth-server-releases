import crypto from 'node:crypto';
import dns from 'node:dns/promises';
import ipaddr from 'ipaddr.js';

export class LabError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

export function authorized(header, password) {
  if (typeof header !== 'string') return false;
  let supplied = '';
  if (header.startsWith('Bearer ')) supplied = header.slice(7);
  if (header.startsWith('Basic ')) {
    const value = Buffer.from(header.slice(6), 'base64').toString();
    if (value.startsWith('lab:')) supplied = value.slice(4);
  }
  const a = Buffer.from(supplied), b = Buffer.from(password);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export function chapterUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw new LabError(400, 'invalid_chapter_url'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      !/^newtoki[0-9]+\.org$/.test(url.hostname) || !/^\/(webtoon|manhwa)\/\d+\/[^/]+\/?$/.test(url.pathname) ||
      url.search || url.hash) throw new LabError(400, 'chapter_url_not_allowed');
  return url.href;
}

export function publicIp(address) {
  try {
    let ip = ipaddr.parse(address);
    if (ip.kind() === 'ipv6' && ip.isIPv4MappedAddress()) ip = ip.toIPv4Address();
    return ip.range() === 'unicast';
  } catch { return false; }
}

export async function publicUrl(value, lookup = dns.lookup) {
  let url;
  try { url = new URL(value); } catch { return false; }
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password ||
      (url.port && !['80', '443'].includes(url.port))) return false;
  const host = url.hostname.replace(/^\[|\]$/g, '');
  if (ipaddr.isValid(host)) return publicIp(host);
  try {
    const records = await lookup(host, { all: true, verbatim: true });
    return records.length > 0 && records.every(r => publicIp(r.address));
  } catch { return false; }
}

export function safeImageUrl(value, base) {
  try {
    const url = new URL(value, base);
    if (url.protocol !== 'https:' || url.username || url.password || url.port) return null;
    return url.href;
  } catch { return null; }
}

export function imageRows(json, base) {
  const body = json?.data?.images ? json.data : json;
  if (!Array.isArray(body?.images) || body.ok === false) return { rows: [], total: null };
  const rows = body.images.slice(0, 1000).flatMap(item => {
    if (!item || typeof item !== 'object') return [];
    const page = Number(item.page);
    const candidates = [item.src, ...(Array.isArray(item.srcCandidates) ? item.srcCandidates : [])]
      .filter(x => typeof x === 'string').map(x => safeImageUrl(x, base)).filter(Boolean);
    if (!Number.isInteger(page) || page < 1 || page > 1000 || !candidates.length) return [];
    return [{ page, urls: [...new Set(candidates)], width: Number(item.width) || 0, height: Number(item.height) || 0 }];
  });
  const total = Number(body.total);
  if (Number.isInteger(total) && total > 0 && total <= 1000) return { rows, total };
  // Observed /api/manhwa-images response: { ok: true, images: [...] },
  // with the complete chapter array and no separate total. Do not apply this
  // fallback to unknown/paginated schemas, truncated arrays or invalid rows.
  const completeArray = body.ok === true && Object.keys(body).every(key => key === 'ok' || key === 'images') &&
    rows.length > 0 && rows.length === body.images.length &&
    rows.map(row => row.page).sort((a, b) => a - b).every((page, index) => page === index + 1);
  return { rows, total: completeArray ? rows.length : null };
}

export function imageType(buffer) {
  if (buffer.length < 12) return null;
  if (buffer.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) return 'image/png';
  if (buffer[0] === 255 && buffer[1] === 216 && buffer[2] === 255) return 'image/jpeg';
  if (['GIF87a', 'GIF89a'].includes(buffer.toString('ascii', 0, 6))) return 'image/gif';
  if (buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  if (buffer.toString('ascii', 4, 8) === 'ftyp' && ['avif', 'avis'].includes(buffer.toString('ascii', 8, 12))) return 'image/avif';
  return null;
}

export function snapshot(job) {
  const rows = [...job.rows.values()].sort((a, b) => a.page - b.page);
  const pages = rows.map(row => {
    const captured = row.urls.map(url => job.bodies.get(url)).find(Boolean);
    return {
      page: row.page, width: row.width, height: row.height,
      captured: !!captured, bytes: captured?.bytes.length || 0,
      type: captured?.type || null,
      relay: captured ? `/v1/jobs/${job.id}/images/${row.page}` : null,
    };
  });
  const captured = pages.filter(p => p.captured).length;
  const complete = job.expected !== null && pages.length === job.expected && captured === job.expected &&
    pages.every((p, index) => p.page === index + 1);
  return {
    id: job.id, chapterUrl: job.url, mode: job.mode || 'relay', browserOpen: !!job.page, state: complete ? 'ready' : captured ? 'partial' : pages.length ? 'images_discovered' : 'waiting_for_viewer',
    expected: job.expected, discovered: pages.length, captured, pages,
    bodyQueue: job.bodyQueueStats ? { ...job.bodyQueueStats } : undefined,
    bodyCollection: job.bodyCollection ? { ...job.bodyCollection, pending: job.pendingBodies } : undefined,
    diagnostic: job.diagnostic, events: job.events.slice(-20),
    expiresAt: new Date(job.expiresAt).toISOString(),
    note: '목록 발견과 실제 이미지 확보는 다릅니다. 인증 유효기간은 사이트가 정합니다.',
  };
}
