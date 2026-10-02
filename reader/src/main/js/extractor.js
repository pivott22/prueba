// Runs in the selected listing tab. Does not receive credentials or access cookies.
export function inspectPage() {
  const root = document.querySelector('.ui-search-layout, .ui-search-results');
  const visible = node => !!node && node.getClientRects().length > 0;
  let cards = root ? [...root.querySelectorAll('.ui-search-layout__item')] : [];
  if (!cards.length && root) cards = [...root.querySelectorAll('.poly-card')];
  const products = cards.filter(visible).map(card => {
    const title = card.querySelector('.poly-component__title, .ui-search-item__title, h2');
    const anchor = title?.closest('a') || card.querySelector('a.poly-component__title, a.ui-search-link');
    const price = card.querySelector('.poly-price__current .andes-money-amount__fraction, .ui-search-price__second-line .andes-money-amount__fraction');
    const currency = price?.closest('.andes-money-amount')?.querySelector('.andes-money-amount__currency-symbol')?.innerText;
    const picture = card.querySelector('img.poly-component__picture, img.ui-search-result-image__element, img');
    const imageUrl = [picture?.currentSrc, picture?.getAttribute('data-src'), picture?.getAttribute('data-lazy-src'), picture?.getAttribute('src')]
      .find(value => {
        try {
          const url = new URL(value);
          return ['http:', 'https:'].includes(url.protocol)
            && ['http2.mlstatic.com', 'http.mlstatic.com'].includes(url.hostname)
            && !url.username && !url.password && !url.port && url.pathname.startsWith('/D_');
        } catch { return false; }
      });
    return { title: title?.innerText, href: anchor?.href,
      price: !currency || currency.trim() === '$' ? price?.innerText : null, imageUrl: imageUrl || null };
  });
  const next = document.querySelector('.andes-pagination__button--next a, a[rel="next"]');
  return {
    url: location.href, recognized: !!root, products,
    documentState: document.readyState,
    loading: document.readyState !== 'complete' || root?.getAttribute('aria-busy') === 'true',
    nextUrl: visible(next) && next.getAttribute('aria-disabled') !== 'true' ? next.href : null,
    // Only used to recognize error pages. Never persisted or sent to Telegram.
    body: document.body.innerText.slice(0, 30000)
  };
}
