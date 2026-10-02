# PokéOfertas: lector Android para Telegram

Aplicación independiente del antiguo cliente de precios/API. El teléfono lee el listado del vendedor **550072427** en un Android WebView normal y manda los productos nuevos, las bajadas y las subidas de precio a **tu chat privado con tu bot**. El PC puede estar apagado. No necesita Render ni la API de Mercado Libre.

Versión **0.1.3**: añade **Abrir Mercado Libre en Chrome** y **Reiniciar sesión del navegador interno** para probar el acceso. Permite rutas de autenticación de Chile en el dominio global de Mercado Libre y muestra una explicación si se interrumpe una navegación. Conserva los avisos de productos nuevos y de cualquier bajada o subida respecto del último precio válido leído, con precio anterior, actual y diferencia. Un precio igual no genera otro aviso; una lectura sin precio conserva la última referencia válida.

Si tienes **0.1.1 o 0.1.2**, instala esta actualización directamente para conservar el bot, el historial y los avisos pendientes. Después abre la app, comprueba la lectura y activa de nuevo la vigilancia.

Si ya instalaste la versión 0.1.0 distribuida antes, desinstálala una vez e instala este APK: aquella compilación utilizaba una firma temporal y no puede actualizarse con la nueva firma. Se borra su configuración local: vuelve a conectar el bot y a registrar el listado. Desde 0.1.1, los APK de GitHub usan una firma privada fija para permitir futuras actualizaciones sin ese reinicio.

## Prueba en tu teléfono

1. Instala `reader-debug.apk`. El nombre instalado es **PokéOfertas · lector Telegram** y puede convivir con la app antigua.
2. Pulsa **Abrir tienda**. Comprueba que el navegador de la app muestra productos reales. Si solicita iniciar sesión o verificar el acceso, complétalo manualmente en esa vista y vuelve a **Abrir tienda**. Si la sesión quedó atascada, con la vigilancia pausada pulsa **Reiniciar sesión del navegador interno** y confirma: borra cookies, datos de páginas y caché de esta app, conservando el bot, el historial y los avisos pendientes. La tienda creará sus cookies normales; puede pedir iniciar sesión otra vez. No garantiza resolver un bloqueo y no se ejecuta automáticamente.
   Para entrar desde el navegador del teléfono, pulsa **Abrir Mercado Libre en Chrome**. Si Chrome no está disponible, aparece un selector de navegador. **La sesión de Chrome no se comparte con la app**: iniciar sesión allí permite usar la tienda en Chrome, pero la vigilancia todavía necesita una lectura completa dentro de la app. [Sesiones de WebView y navegador](https://developer.android.com/develop/ui/views/layout/webapps/webview#handle-page-navigation).
3. En la primera página, pulsa **Guardar esta primera página** y **Probar lectura sin avisos**. Debe decir lectura completa y mostrar productos. Si falla, usa **Copiar diagnóstico sin claves**; no actives la vigilancia hasta que funcione.
4. Pulsa **Conectar mi bot**, introduce el token vigente localmente, **Preparar conexión**, **Abrir mi bot**, pulsa **Iniciar** en Telegram y regresa a **Confirmar conexión**. Solo vincula el chat privado que envió el código temporal.
5. Pulsa **Enviar prueba con foto de un producto real**. Solo confirma éxito si Telegram aceptó un `sendPhoto` con nombre, precio y enlace de una lectura nueva. No cambia el historial de novedades ni manda el listado entero.
6. Permite notificaciones y pulsa **Activar vigilancia cada 2 min**. La primera lectura completa guarda los productos y sus precios sin avisar. Después se encolan los identificadores nuevos y las bajadas o subidas respecto del último precio válido leído, y se envían hasta cinco avisos por consulta. No repite el aviso si el precio permanece igual. Mantén el teléfono encendido y con Internet; aparecerá una notificación permanente con botón **Pausar**.

Usa un token nuevo si el anterior se compartió en un chat: revócalo con BotFather y pega el reemplazo solo en la app. Se guarda cifrado con Android Keystore y la aplicación no permite copias de seguridad. El diagnóstico no incluye tokens, cookies, HTML ni parámetros de autenticación; también oculta los segmentos variables de las rutas de inicio de sesión. Nunca importa ni exporta cookies de Chrome.

## Límites que debes conocer

- **Android 15/16 limita los servicios `dataSync` a 6 horas en segundo plano por cada 24 horas. Abrir la aplicación reinicia ese tiempo.** Al alcanzar el límite, la app se detiene y conserva historial y avisos pendientes. Abre la app y reactiva la vigilancia. Esta versión no promete un monitor permanente de 24 horas. [Documentación de Android](https://developer.android.com/develop/background-work/services/fgs/timeout).
- El intervalo es aproximadamente dos minutos; ahorro de batería, pérdida de red o cierre del proceso pueden retrasarlo. El servicio no se reactiva solo al reiniciar el teléfono ni después de una pausa.
- Una página bloqueada, vacía, que cambia mientras se lee o con tarjetas no reconocidas pausa la vigilancia y conserva el historial. No hay reintentos de CAPTCHA ni evasión de bloqueos. El listado completo encontrado puede tener hasta diez páginas; las lecturas parciales no generan novedades.
- Se vigila el listado guardado, con sus filtros. Un producto que no aparece en ese listado no puede detectarse. La identidad usa catálogo más vendedor cuando está disponible. Un producto antiguo que vuelve a aparecer no cuenta como nuevo, pero avisa si su precio leído cambió respecto de su última referencia válida.
- Si no se reconoce una foto, o Telegram rechaza expresamente su URL, una novedad se manda como texto con aviso de foto no disponible. La prueba con foto nunca se sustituye por texto. Un fallo de red deja los avisos pendientes; un fallo después de que Telegram haya aceptado un mensaje puede producir un duplicado al reintentar.
- Los enlaces son originales de Mercado Libre. Genera tu enlace de referido antes de compartir en Facebook; la app no publica en grupos.

## Compilación y comprobaciones

La acción **Build Android APK** genera el artefacto `pokeofertas-reader-apk` además del APK anterior. No necesita instalar Android Studio en tu PC.

Para desarrollo: Java 17, Gradle 8.11.1 y Android SDK 35.

```sh
node reader/scripts/build-reader.mjs
gradle :reader:testDebugUnitTest :reader:assembleDebug :reader:lintDebug
```

El script generado se incluye en el repositorio y la acción vuelve a generarlo antes de compilar. Las pruebas JVM comprueban historial silencioso, productos nuevos, bajadas y subidas de precio, conservación ante errores, límites de los mensajes, rutas de autenticación permitidas y ocultación de datos de sesión en el diagnóstico. `node reader/scripts/test-dom.cjs` prueba el extractor generado con fixtures offline en Edge y Playwright, interceptando toda la red.

La firma fija de CI se restaura del secreto del repositorio `READER_SIGNING_CONFIG`; nunca se guarda la clave privada en el código ni en los artefactos. Para un fork, configura tu propia identidad. Una compilación local sin ese secreto utiliza su firma de depuración local y puede requerir reinstalación si se mezcla con los APK de CI.

Estas pruebas no confirman acceso real a la tienda desde WebView ni entrega real a Telegram: los pasos 2–5 del teléfono son necesarios para comprobar ambos.
