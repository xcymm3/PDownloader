/* Read-only DOM discovery. No native bridge, network proxy, clicks or page timers. */
(() => {
  const videos = [], images = [], frames = [], requests = [];
  const absolute = (s, base) => { if (typeof s !== 'string' || !s.trim()) return ''; try { const u = new URL(s, base); return /^https?:$/.test(u.protocol) ? u.href : ''; } catch (_) { return ''; } };
  const state = window.__pdScanV2 || (window.__pdScanV2 = { ids: new WeakMap(), next: 1 });
  const id = el => { if (!state.ids.has(el)) state.ids.set(el, state.next++); return state.ids.get(el); };
  const visit = (root, depth) => {
    if (depth > 2) return;
    const doc = root.ownerDocument || root;
    const base = doc.baseURI || location.href;
    for (const el of Array.from(root.querySelectorAll('video')).slice(0, 40)) {
      const variants = [];
      const add = (src, quality, mime) => { const url = absolute(src, base); if (url && (url !== doc.URL || (doc.contentType || '').startsWith('video/')) && !variants.some(v => v.url === url)) variants.push({ url, quality: quality || '', mime: mime || '' }); };
      add(el.currentSrc || el.src, el.getAttribute('label'), el.getAttribute('type'));
      for (const source of el.querySelectorAll('source')) if (!source.type || source.type.startsWith('video/') || /mpegurl/i.test(source.type)) add(source.src, source.getAttribute('label') || source.getAttribute('size') || source.title, source.type);
      if (variants.length) videos.push({ id: base + '#video-' + id(el), title: el.title || doc.title, thumbnail: absolute(el.poster, base), duration: Number.isFinite(el.duration) ? Math.round(el.duration * 1000) : -1, page: base, variants });
    }
    for (const el of Array.from(root.querySelectorAll('img')).slice(0, 200)) {
      const url = absolute(el.currentSrc || el.src || el.getAttribute('data-src'), base);
      if (url) images.push({ url, title: el.alt || doc.title, width: el.naturalWidth || 0, height: el.naturalHeight || 0, page: base });
    }
    for (const el of Array.from(root.querySelectorAll('iframe')).slice(0, 8)) {
      const url = absolute(el.src, base); if (url) frames.push(url);
      try { if (el.contentDocument) visit(el.contentDocument, depth + 1); } catch (_) {}
    }
    for (const el of Array.from(root.querySelectorAll('*')).slice(0, 1200)) if (el.shadowRoot) visit(el.shadowRoot, depth + 1);
  };
  visit(document, 0);
  try { for (const item of performance.getEntriesByType('resource').slice(-200)) if (/\.(m3u8|mp4|webm|mov)([?#]|$)/i.test(item.name)) requests.push(item.name); } catch (_) {}
  return JSON.stringify({ url: location.href, title: document.title, videos, images, frames, requests });
})();
