function readSnapshot() {
  const sellerId = '550072427';
  const url = location.href;
  const raw = inspectPage();
  const classification = classifyPage({ url, text: raw.body, http: 200 });
  const base = { products: [], completeFirstPage: false, status: classification,
    visibleCards: raw.products.length, documentState: raw.documentState, loading: !!raw.loading };
  if (classification !== 'ok') return { ...base, reason: 'Mercado Libre pide una verificación o rechaza la lectura.' };
  if (!validListingUrl(url, sellerId)) return { ...base, status: 'wrong_page', reason: 'La página actual no es el listado del vendedor.' };
  const products = raw.products.map(p => normalizeProduct(p, url, sellerId)).filter(Boolean);
  if (!raw.recognized || !products.length || products.length !== raw.products.length || raw.loading) {
    return { ...base, status: 'incomplete', validProducts: products.length,
      reason: 'Todavía no se reconoce un listado completo de productos.' };
  }
  if (raw.nextUrl && !validListingUrl(raw.nextUrl, sellerId)) {
    return { ...base, status: 'incomplete', reason: 'La siguiente página no pertenece al mismo vendedor.' };
  }
  return { ...base, status: 'ok', products: [...new Map(products.map(p => [p.key, p])).values()].sort((a, b) => a.key.localeCompare(b.key)),
    completeFirstPage: true, validProducts: products.length, nextUrl: raw.nextUrl || null };
}
