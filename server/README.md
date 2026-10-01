# Servidor de consulta opcional

Requiere Python 3.11+, una aplicación de Mercado Libre con acceso autorizado a estos recursos y un token OAuth válido. Ser afiliado NO demuestra que tengas permisos de API. El token puede vencer; esta versión no realiza renovación automática y registra el error en la app.

Crear variables de entorno `MELI_ACCESS_TOKEN` y `APP_API_KEY` (clave aleatoria larga), y ejecutar `python server.py` desde esta carpeta. No guardar secretos en el repositorio. El servidor escucha únicamente en 127.0.0.1:8080. Publicarlo a través de un proxy HTTPS de confianza antes de usarlo desde Android; no exponer directamente el servidor HTTP de desarrollo a internet.

La app necesita la URL HTTPS del proxy y APP_API_KEY, nunca el token de Mercado Libre. El proxy debe conservar la cabecera Authorization. `/health` y `/products` exigen la clave. La consulta `/products` es la prueba efectiva del acceso al vendedor: si devuelve 401/403 del proveedor, esta integración no está habilitada y debe mantenerse el modo manual. No se eluden restricciones ni se extraen cookies.

Se consultan hasta 500 publicaciones activas, se filtran títulos con la palabra Pokémon y se verifican moneda CLP, vendedor, URL y precio. La respuesta declara cobertura parcial si hay más publicaciones. Esto también puede incluir accesorios o artículos que no sean TCG: revisar el contenido antes de publicar. Un precio bajo no acredita autenticidad.

Las respuestas válidas se conservan por cinco minutos para evitar consultas repetidas. Si una consulta falla, se devuelve error; no se presentan precios anteriores como nuevos. Las publicaciones que desaparecen de la búsqueda conservan su última consulta en el teléfono y deben verificarse al abrirlas. No se infiere stock exacto.

No hay base de datos del mercado externo. Las referencias comparativas se registran manualmente en la app.

Pruebas de validación: `python -m unittest discover -s . -p test_server.py`. Las cuatro pruebas pasaron en la sesión de creación. Una consulta sin credenciales a la API oficial del vendedor devolvió HTTP 403; el acceso autorizado sigue pendiente.

Documentación de recursos: https://developers.mercadolibre.com.ar/es_ar/guia-para-carrito-de-compras/items-y-busquedas
