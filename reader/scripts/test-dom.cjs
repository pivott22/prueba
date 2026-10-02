// Offline fixtures only. All network requests are intercepted.
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const assert = require('node:assert/strict');
const script = readFileSync(join(__dirname, '../src/main/assets/probe.js'), 'utf8');
const listing = 'https://listado.mercadolibre.cl/pokemon_CustId_550072427_NoIndex_True';
function card({ id = 'MLC123', price = '18.990', currency = '$', seller = '550072427', image = true, wid = '' } = {}) {
  return `<li class="ui-search-layout__item"><a class="poly-component__title" href="https://www.mercadolibre.cl/fixture/p/${id}?pdp_filters=seller_id%3A${seller}${wid ? '&wid=' + wid : ''}">Producto de prueba offline</a>
    <span class="poly-price__current"><span class="andes-money-amount"><span class="andes-money-amount__currency-symbol">${currency}</span><span class="andes-money-amount__fraction">${price}</span></span></span>
    ${image ? '<img class="poly-component__picture" data-src="https://http2.mlstatic.com/D_FIXTURE.webp">' : ''}</li>`;
}
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage(); let html = '';
  await page.route('**/*', route => route.request().isNavigationRequest()
    ? route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><body>' + html + '</body></html>' }) : route.abort());
  async function snapshot(body, url = listing) { html = body; await page.goto(url); return JSON.parse(await page.evaluate(script)); }
  const root = body => '<ul class="ui-search-layout">' + body + '</ul>';
  try {
    let data = await snapshot(root(card()));
    assert.equal(data.completeFirstPage, true); assert.equal(data.products[0].price, 18990);
    assert.equal(data.products[0].imageUrl, 'https://http2.mlstatic.com/D_FIXTURE.webp');
    const key = data.products[0].key;
    data = await snapshot(root(card({ wid: 'MLC456', price: '19.990' })));
    assert.equal(data.products[0].key, key);
    data = await snapshot(root(card({ image: false, currency: 'USD' })));
    assert.equal(data.products[0].imageUrl, null); assert.equal(data.products[0].price, null);
    data = await snapshot(root(card({ seller: '999' })));
    assert.equal(data.completeFirstPage, false); assert.equal(data.products.length, 0);
    data = await snapshot(root(''));
    assert.equal(data.completeFirstPage, false);
    data = await snapshot('<h1>Verifica que eres humano</h1>');
    assert.equal(data.status, 'blocked'); assert.equal(data.products.length, 0);
    data = await snapshot(root(card()), 'https://www.mercadolibre.cl/gz/account-verification?go=private');
    assert.equal(data.status, 'blocked'); assert.equal(data.products.length, 0);
    data = await snapshot(root(card()) + '<a rel="next" href="' + listing + '_Desde_49">Siguiente</a>');
    assert.equal(data.nextUrl, listing + '_Desde_49');
    data = await snapshot(root(card()) + '<a rel="next" href="https://evil.example/">Siguiente</a>');
    assert.equal(data.completeFirstPage, false); assert.equal(data.products.length, 0);
    data = await snapshot(root(card({ id: 'not-an-id' })));
    assert.equal(data.completeFirstPage, false);
    data = await snapshot('<h1>Ingresa tu e-mail o teléfono</h1>', 'https://www.mercadolibre.com/jms/mlc/lgz/login');
    assert.equal(data.status, 'blocked'); assert.equal(data.accessKind, 'signin');
    data = await snapshot('<h1>Para continuar, ingresa a tu cuenta</h1>');
    assert.equal(data.status, 'blocked'); assert.equal(data.accessKind, 'signin');
    data = await snapshot('<h1>Ingresa tu e-mail o teléfono</h1>', 'https://www.mercadolibre.cl/gz/account-verification');
    assert.equal(data.accessKind, 'signin');
    data = await snapshot(root(card()) + '<nav>Iniciar sesión</nav>');
    assert.equal(data.completeFirstPage, true); assert.equal(data.accessKind, '');
    data = await snapshot(root(card()) + '<nav>Para continuar, ingresa a tu cuenta</nav>');
    assert.equal(data.completeFirstPage, true); assert.equal(data.accessKind, '');
    data = await snapshot('<h1>Iniciar sesión</h1><p>Verifica que eres humano</p>', 'https://www.mercadolibre.com/jms/mlc/lgz/login');
    assert.equal(data.status, 'blocked'); assert.equal(data.accessKind, 'verification');
    data = await snapshot('<h1>Iniciar sesión</h1>', 'https://evil.example/login');
    assert.equal(data.accessKind, ''); assert.equal(data.products.length, 0);
    console.log('17 escenarios de DOM offline correctos; ninguna consulta real a Mercado Libre o Telegram.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
