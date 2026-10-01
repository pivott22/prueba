# Configuración en Render y Mercado Libre

El código está preparado para Render. Todavía hace falta crear el servicio en tu cuenta y comprobar los permisos reales del vendedor. No se presupone acceso por ser afiliado.

## 1. Crear el servicio antes de registrar la Redirect URI

En https://dashboard.render.com crea una cuenta y selecciona New → Blueprint. Conecta GitHub y selecciona pivott22/prueba. El archivo render.yaml propone un servicio gratuito con claves aleatorias generadas por Render. Revisa el plan y crea el servicio. El proyecto no genera cargos de pago por su configuración; no cambies a un plan de pago sin revisar su precio.

También puedes abrir https://render.com/deploy?repo=https://github.com/pivott22/prueba para iniciar el formulario; el enlace no despliega nada por sí solo.

Cuando esté desplegado, abre la dirección HTTPS que Render te asigna. La página mostrará la Redirect URI exacta terminada en /oauth/callback. No inventes el dominio: usa el que muestra el servicio.

## 2. Mercado Libre Developers

- Redirect URI: la dirección exacta mostrada por tu servidor.
- OAuth: Authorization Code y Refresh Token activados; Client Credentials desactivado.
- PKCE: activado.
- Negocio: Mercado Libre; VIS desactivado.
- Usuarios: solo lectura si el formulario ofrece la opción; si no, conservar el permiso predeterminado.
- Publicación y sincronización: solo lectura, para consultar items y prices.
- Los demás permisos: sin acceso.
- Tópicos y URL de notificaciones: vacíos. Esta versión consulta periódicamente; no usa webhooks.

Si el formulario no permite lectura en Publicación y sincronización, revisar las opciones disponibles antes de elegir escritura. Autorizar tu cuenta no garantiza acceso al vendedor ajeno.

## 3. Configurar credenciales privadas en Render

Tras crear la aplicación en Mercado Libre, en Render → servicio → Environment agrega:

- MELI_CLIENT_ID: APP ID.
- MELI_CLIENT_SECRET: clave privada de la aplicación, solo en este campo privado de Render.

No subir esas credenciales a GitHub ni pegarlas en el chat. Las variables APP_API_KEY y TOKEN_ENCRYPTION_KEY ya las genera el Blueprint. APP_API_KEY es la clave para autorizar el navegador y conectar el teléfono; TOKEN_ENCRYPTION_KEY cifra los tokens guardados. No reutilizar la clave de Mercado Libre como clave del teléfono.

Render proporciona RENDER_EXTERNAL_HOSTNAME; no hace falta PUBLIC_BASE_URL salvo que uses otro dominio. Si configuras PUBLIC_BASE_URL, debe coincidir exactamente con el origen de la Redirect URI registrada.

Guarda los cambios y espera el despliegue. Abre la página del servicio, ingresa APP_API_KEY y pulsa Autorizar mi cuenta. Mercado Libre recibe tus credenciales de inicio de sesión directamente. El servidor usa state, PKCE S256 y una cookie vinculada al navegador. Debes completar la vuelta en el mismo navegador antes de 10 minutos.

## 4. Android

En Configurar vigilancia, coloca la URL HTTPS del servicio (sin /oauth/callback) y APP_API_KEY. Activa las alertas deseadas, guarda y pulsa Consultar ahora. Un error 403 con autorización válida indica que falta habilitar el recurso o el acceso al vendedor; no se sustituye por scraping ni datos ficticios.

## Límites de la prueba gratuita

Render puede suspender un servicio gratuito por inactividad. El primer intento de consulta podría demorarse y Android reintentará más tarde. No se prometen notificaciones instantáneas. Los tokens se cifran en reposo, pero en el plan gratuito el disco es efímero: un reinicio/despliegue puede requerir autorizar de nuevo. Para una vigilancia estable se necesita almacenamiento persistente y alojamiento adecuado; comprobar primero el acceso real antes de contratarlo.

El APK actual funciona con este servidor; no hace falta reinstalarlo para probar la conexión. Las referencias de mercado siguen siendo manuales hasta disponer de una fuente autorizada de comparaciones.

Fuentes: https://render.com/docs/web-services y https://render.com/docs/blueprint-spec
