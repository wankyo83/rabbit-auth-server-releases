import { LabError, safeImageUrl } from './core.mjs';

// URL families, not a fixed current domain. Only the extension's requested chapter is opened.
export function chapter(value, kind = 'images') {
  let u;
  try { u = new URL(value); } catch { throw new LabError(400, 'invalid_chapter_url'); }
  if (u.protocol !== 'https:' || u.port || u.username || u.password || u.hash || value.length > 8192)
    throw new LabError(400, 'chapter_url_not_allowed');
  let family;
  if (/^(newtoki\d+\.org|toki\d+\.com|sbxh\d+\.com)$/.test(u.hostname)) {
    family = kind === 'novel' ? 'novel' : 'toki';
    const numericOrSlugWebtoon = /^\/(webtoon|manhwa)\/[A-Za-z0-9_-]+\/[A-Za-z0-9_-]+\/?$/.test(u.pathname);
    const legacyNovel = /^\/novel\/\d+\/[^/]+\/?$/.test(u.pathname);
    if (!(numericOrSlugWebtoon || legacyNovel) || u.search)
      throw new LabError(400, 'chapter_path_not_allowed');
    if ((kind === 'novel') !== u.pathname.startsWith('/novel/')) throw new LabError(400, 'chapter_kind_mismatch');
  } else if (/^blacktoon\d+\.com$/.test(u.hostname)) family = 'blacktoon';
  else if (/^(www\.)?jjaptoon\d+\.com$/.test(u.hostname)) family = 'jjaptoon';
  else if (/^11toon\d*\.com$/.test(u.hostname)) family = 'toon11';
  else if (/^wfwf\d+\.com$/.test(u.hostname)) family = 'wfwf';
  else if (/^(www\.)?goodtoon\d+\.com$/.test(u.hostname)) {
    family = 'goodtoon';
    if (!/^\/manga\/[A-Za-z0-9_-]+\/(?:chapter-)?\d+\/?$/.test(u.pathname) || u.search)
      throw new LabError(400, 'chapter_path_not_allowed');
  }
  else if (/^(www\.)?newxtoon\d+\.com$/.test(u.hostname)) {
    family = 'newxtoon';
    if (!/^\/comics\/\d+\/chapters\/\d+\/?$/.test(u.pathname) || u.search)
      throw new LabError(400, 'chapter_path_not_allowed');
  }
  else if (['comic.naver.com', 'm.comic.naver.com'].includes(u.hostname)) family = 'naver';
  else throw new LabError(400, 'unsupported_site');
  if (kind === 'novel' && family !== 'novel') throw new LabError(400, 'unsupported_novel_site');
  if (!['images','novel'].includes(kind)) throw new LabError(400, 'invalid_kind');
  return {url:u.href, family, kind, origin:u.origin};
}

// Executed in the site's own rendered page. No fingerprint spoofing, challenge solving,
// token generation, direct protected-API calls, or paywall interaction.
export function readViewer(family) {
  // Newtoki occasionally publishes a chapter entry before every source image
  // has finished processing. Treat the site's exact notice as a completed,
  // non-authentication state so callers do not wait for the manual viewer.
  if (family === 'toki') {
    const bodyText = (document.body?.innerText || document.body?.textContent || '').replace(/\s+/g, ' ').trim();
    const paidTitle = document.querySelector?.('#theme-paid-title');
    if (paidTitle && /PREMIUM EPISODE/i.test(bodyText) && /로그인|구매|잠긴|이용/.test(paidTitle.innerText || ''))
      return {notice:'paid_content_locked'};
    if (bodyText.includes('이미지 처리 중인 회차입니다. 잠시 후 다시 확인해주세요.'))
      return {notice:'source_upload_pending'};
  }
  if (family === 'novel') {
    const viewer = document.querySelector('.novel-viewer');
    const alert = document.querySelector('[data-novel-unlock-status], .novel-gate, .novel-error, .novel-viewer [role=alert]');
    if (/구매가 필요|잠긴 회차|로그인이 필요|이용할 수 없는 회차/.test(alert?.innerText || '')) return {error:'manual_login_or_paid_content'};
    let root = viewer;
    for (const node of [viewer, ...Array.from(viewer?.querySelectorAll('*') || [])]) {
      if (node?.shadowRoot || node?.__novelShadow) { root = node.shadowRoot || node.__novelShadow; break; }
    }
    const paragraphs = Array.from(root?.querySelectorAll('p') || []).map(p => p.innerText || p.textContent || '').filter(t => t.trim());
    return {text:paragraphs.join('\n\n'), title:document.querySelector('.ne-h1, .novel-viewer h1')?.textContent || document.title};
  }
  if (family === 'toon11') {
    const scripts = Array.from(document.querySelectorAll('script:not([src])')).map(s => s.textContent || '').join('\n');
    const parse = name => {
      const raw = scripts.match(new RegExp(name + '\\s*=\\s*(\\[[\\s\\S]*?\\])'))?.[1];
      if (!raw) return [];
      try { const rows = JSON.parse(raw); return Array.isArray(rows) ? rows : []; } catch { return []; }
    };
    const one = parse('img_list'), two = parse('img_list_2');
    if (one.length) return {expected:one.length, rows:one.map((src, i) => ({page:i+1, urls:[src, two[i]].filter(v => typeof v === 'string')}))};
  }
  const selectors = {
    toki: '.vw-imgs img, img.viewer-ratio-img',
    blacktoon: '#toon_content_imgs img',
    jjaptoon: '[data-reading-image-index] > img',
    wfwf: '.viewer-wrap img[data-src]',
    goodtoon: '.reading-content img, div.page-break img',
    newxtoon: '#comic-reader [data-reader-page] img[data-reader-image][src]',
    naver: '.wt_viewer img, .toon_view_lst img',
    toon11: '#comic-viewer img, #toon_content_imgs img',
  };
  const images = Array.from(document.querySelectorAll(selectors[family] || 'NOT_A_VIEWER'));
  const expected = Number(document.querySelector('[data-viewer-image-count]')?.getAttribute('data-viewer-image-count')) || null;
  const rows = images.map((img, i) => {
    const number = Number(img.getAttribute('data-page') || img.getAttribute('data-page-number') || img.alt?.match(/(?:page|페이지)\s*(\d+)/i)?.[1]);
    const src = img.getAttribute('data-src') || img.getAttribute('data-original') || img.currentSrc || img.src;
    return {page: family === 'toki' ? number : i+1, urls:[src]};
  });
  return {rows, expected: family === 'toki' ? expected : rows.length, stable:document.readyState === 'complete'};
}

export function normalizeRows(rows, base) {
  if (!Array.isArray(rows) || rows.length > 2000) return [];
  return rows.flatMap(row => {
    const page = Number(row.page);
    if (!Number.isInteger(page) || page < 1 || page > 2000 || !Array.isArray(row.urls)) return [];
    const urls = [...new Set(row.urls.map(src => typeof src === 'string' ? safeImageUrl(src, base) : null).filter(Boolean))];
    return urls.length && urls.length <= 8 ? [{page, urls}] : [];
  });
}

export function complete(rows, expected) {
  return Number.isInteger(expected) && expected > 0 && expected <= 2000 && rows.length === expected &&
    [...rows].sort((a,b) => a.page-b.page).every((r,i) => r.page === i+1);
}
