const HOSTS = new Set(['www.mercadolibre.cl', 'articulo.mercadolibre.cl', 'listado.mercadolibre.cl']);

function sellerInUrl(value) {
  const url = new URL(value);
  const filter = url.searchParams.get('pdp_filters') || '';
  return url.pathname.match(/_CustId_(\d+)(?:_|$)/i)?.[1]
    || url.searchParams.get('seller_id') || filter.match(/(?:^|[|,])seller_id:(\d+)(?:$|[|,])/)?.[1];
}

function validListingUrl(value, sellerId) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname === 'listado.mercadolibre.cl'
      && !url.username && !url.password && sellerInUrl(value) === sellerId;
  } catch { return false; }
}

function normalizeImageUrl(value) {
  try {
    const url = new URL(value);
    if (!['http2.mlstatic.com', 'http.mlstatic.com'].includes(url.hostname)
      || url.username || url.password || url.port || !url.pathname.startsWith('/D_')) return null;
    if (url.protocol === 'http:') url.protocol = 'https:';
    if (url.protocol !== 'https:') return null;
    url.hash = '';
    return url.href;
  } catch { return null; }
}

function normalizeProduct(raw, listingUrl, sellerId) {
  try {
    if (!validListingUrl(listingUrl, sellerId)) return null;
    const url = new URL(raw.href, listingUrl);
    if (url.protocol !== 'https:' || !HOSTS.has(url.hostname) || url.username || url.password) return null;
    const explicitSeller = sellerInUrl(url.href);
    if (explicitSeller && explicitSeller !== sellerId) return null;
    const wid = url.searchParams.get('wid');
    const direct = url.hostname === 'articulo.mercadolibre.cl' ? url.pathname.match(/\/MLC-?(\d+)(?:-|\/|$)/i)?.[1] : null;
    const catalog = url.pathname.match(/\/(?:p|up)\/(MLCU?\d+)(?:\/|$)/i)?.[1];
    const itemId = /^MLC\d+$/.test(wid || '') ? wid : direct ? `MLC${direct}` : null;
    if (!itemId && !catalog) return null;
    const title = String(raw.title || '').replace(/\s+/g, ' ').trim();
    if (!title) return null;
    url.hash = '';
    if (catalog) url.searchParams.set('pdp_filters', `seller_id:${sellerId}`);
    for (const key of [...url.searchParams.keys()]) {
      if (!['wid', 'pdp_filters'].includes(key)) url.searchParams.delete(key);
    }
    const priceText = String(raw.price || '').trim();
    const price = /^\d[\d.,\s]*$/.test(priceText) ? Number(priceText.replace(/[.,\s]/g, '')) : null;
    return {
      // A catalog ID identifies a product; it is not an individual offer ID.
      key: catalog ? `catalog:${catalog}:seller:${sellerId}` : `item:${itemId}`,
      itemId, catalogId: catalog || null, title,
      price: Number.isSafeInteger(price) && price > 0 ? price : null,
      url: url.href, sellerId, imageUrl: normalizeImageUrl(raw.imageUrl)
    };
  } catch { return null; }
}

function classifyPage({ url, http, text }) {
  if ([401, 403, 429].includes(http)) return 'blocked';
  if (http >= 400) return 'http_error';
  if (/\/gz\/|\/account-verification|\/login|\/registration/i.test(new URL(url).pathname)) return 'blocked';
  if (/hubo un error accediendo|para continuar,?\s+ingresa|verifica que eres humano|completa el captcha|access denied/i.test(text)) return 'blocked';
  return 'ok';
}

function emptyState() { return { version: 1, baselineComplete: false, seen: {}, pending: [] }; }
function reconcile(state, scan, now = new Date().toISOString()) {
  if (scan.status !== 'ok' || !scan.complete || !scan.products.length) return { state, added: [], seeded: false };
  const next = structuredClone(state);
  const current = [...new Map(scan.products.map(p => [p.key, p])).values()];
  const added = next.baselineComplete ? current.filter(p => !next.seen[p.key]) : [];
  for (const product of current) {
    next.seen[product.key] = { ...product, firstSeen: next.seen[product.key]?.firstSeen || now, lastSeen: now };
  }
  next.pending.push(...added);
  const seeded = !next.baselineComplete;
  next.baselineComplete = true;
  next.lastSuccessfulCheck = now;
  return { state: next, added, seeded };
}

function telegramText(product, { test = false, missingPhoto = false } = {}) {
  const amount = product.price ? `$${new Intl.NumberFormat('es-CL').format(product.price)} CLP` : 'Precio no visible';
  const prefix = `${test ? '🧪 Prueba con un producto real (no es una novedad)' : '🆕 Nueva publicación detectada'}\n\n`;
  const suffix = `\n💰 ${amount}${missingPhoto ? '\n📷 Foto no disponible.' : ''}\n\n${product.url}`;
  const budget = Math.min(700, 1024 - Array.from(prefix + suffix).length);
  if (budget < 1) throw new Error('El enlace del producto es demasiado largo para el mensaje con foto.');
  const title = Array.from(product.title);
  const short = title.length > budget ? title.slice(0, budget - 1).join('') + '…' : title.join('');
  return prefix + short + suffix;
}


export { validListingUrl, normalizeImageUrl, normalizeProduct, classifyPage, emptyState, reconcile, telegramText };
