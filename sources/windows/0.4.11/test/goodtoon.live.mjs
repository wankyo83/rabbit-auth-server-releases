import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { Jobs } from '../src/jobs.mjs';

const reportPath = path.resolve(process.argv[2] || 'goodtoon-live-report.json');
const dataDir = path.join(path.dirname(reportPath), 'goodtoon-live-data');
const jobs = new Jobs({ dataDir, concurrency: 1, launchOptions: { headless: false, chromiumSandbox: true } });

try {
  await jobs.init();
  const opened = jobs.open({
    url: 'https://www.goodtoon002.com/manga/gt-14556/1/',
    requestId: crypto.randomUUID(),
    kind: 'images',
  });
  let current;
  for (let attempt = 0; attempt < 180; attempt++) {
    current = jobs.get(opened.id);
    if (current.state === 'ready' || current.state === 'failed') break;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.equal(current?.state, 'ready', current?.error || 'Goodtoon job timed out');
  const manifest = jobs.manifest(opened.id);
  assert.equal(manifest.kind, 'images');
  assert(manifest.expected >= 5, `Only ${manifest.expected} images were found`);
  assert.equal(manifest.pages.length, manifest.expected);
  assert(manifest.pages.every((row, index) => row.page === index + 1 && row.urls.length > 0));
  const firstUrl = manifest.pages[0].urls[0];
  const image = await fetch(firstUrl, {
    headers: { 'User-Agent': manifest.userAgent, Referer: manifest.referer },
    signal: AbortSignal.timeout(30000),
  });
  const bytes = Buffer.from(await image.arrayBuffer());
  assert(image.ok, `Image HTTP ${image.status}`);
  assert(image.headers.get('content-type')?.startsWith('image/'), 'First response is not an image');
  assert(bytes.length > 1000, 'First image is empty');
  await fs.writeFile(reportPath, JSON.stringify({
    ok: true,
    chapterUrl: manifest.chapterUrl,
    images: manifest.expected,
    firstImageHost: new URL(firstUrl).hostname,
    firstImageBytes: bytes.length,
  }, null, 2), { flag: 'wx' });
} catch (error) {
  await fs.writeFile(reportPath, JSON.stringify({ ok: false, error: error.message, stack: error.stack }, null, 2), { flag: 'wx' });
  process.exitCode = 1;
} finally {
  await jobs.close().catch(() => {});
}
