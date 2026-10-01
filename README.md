# PokéOfertas Chile

Aplicación Android de uso personal para vigilar Pokémon TCG del vendedor 550072427. El APK de prueba fue compilado en GitHub Actions y tiene aproximadamente 2,4 MB. No hay un servicio de vigilancia desplegado.

## Instalar sin Android Studio

Abrir https://github.com/pivott22/prueba/actions/runs/36916587577 con sesión de GitHub, bajar el archivo `pokeofertas-debug-apk` de Artifacts y descomprimirlo. Transferir `app-debug.apk` al teléfono, abrirlo y autorizar la instalación desde esa fuente si Android lo solicita. Compatible desde Android 8.0. Esta es una versión de prueba; no está publicada en Google Play y todavía no se probó en un teléfono.

## Estado

- Registro manual de productos, precio actual, envío y precio objetivo.
- Historial local de precios, texto para copiar y apertura del producto/vendedor.
- Comparación con precio de referencia ingresado por el usuario: no se presenta como una tasación del mercado.
- Sincronización y alertas locales mediante un servidor opcional, incluido en `server/`.
- El servidor intenta consultar la API oficial de Mercado Libre con un token proporcionado por su operador. El acceso a publicaciones de este vendedor NO está confirmado; errores 401/403 se muestran y nunca se sustituyen por datos ficticios.
- No genera enlaces de afiliado. Se generan en Mercado Libre y se pueden pegar en la app.
- No publica en Facebook automáticamente.

## Compilar

Abrir esta carpeta en Android Studio, instalar JDK 17 y Android SDK 35, sincronizar Gradle y ejecutar la app. Se necesita Gradle 8.11.1 si se usa la terminal; no se incluye un wrapper binario. Ejecutar `gradle assembleDebug` desde esta carpeta. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

La carpeta `.github/workflows` incluye un flujo de compilación para GitHub. Se ejecuta al subir cambios a main o manualmente desde Actions → Build Android APK → Run workflow. Cuando termina correctamente, el APK se descarga en la sección Artifacts del resultado. No se requiere Android Studio local. El código está en pivott22/prueba y la ejecución 36916587577 terminó correctamente.

## Primer uso

1. Abrir el vendedor y copiar una publicación.
2. Agregar nombre, URL, precio y precio objetivo. Los valores son pesos chilenos enteros.
3. Registrar cambios manualmente o configurar un servidor HTTPS.
4. Si se alcanza el precio objetivo, copiar el mensaje. Generar el enlace de afiliado en Mercado Libre y pegarlo en el campo correspondiente antes de compartir.
5. Para comparar, ingresar un precio de referencia del mismo producto, idioma y estado sellado, incluyendo el envío. La app indica que esta referencia es manual.

## Vigilancia automática

Leer `server/README.md`. El teléfono consulta el servidor aproximadamente cada 15 minutos con WorkManager; Android puede retrasar ese trabajo por batería o conectividad. Las alertas dependen de permiso de notificaciones y de que el servidor siga funcionando. No son instantáneas. El servidor vuelve a consultar precios cuando un cliente sincroniza; no es un sistema de notificaciones push.

Los precios y el stock pueden cambiar. El costo de envío depende del comprador y NO lo calcula la API del servidor. La app marca las ofertas automáticas como envío sin confirmar y nunca las compara como si fueran precio final.

## Privacidad

Datos manuales almacenados solo en el teléfono. El token OAuth de Mercado Libre permanece en el servidor. El teléfono guarda la URL y clave de acceso del servidor en preferencias privadas de la aplicación; copias de seguridad Android deshabilitadas. Usar solo HTTPS para servidores remotos.

## Verificación

Las cuatro pruebas del servidor pasaron, además de assembleDebug y lintDebug en GitHub Actions. El APK fue descargado correctamente. Las pruebas de ejecución en un teléfono siguen pendientes. La API oficial devolvió HTTP 403 en la consulta pública del vendedor: falta comprobar acceso autorizado con un token válido y desplegar el servidor. Por ahora se puede usar el registro manual de productos, historial y mensajes para copiar; todavía no hay vigilancia automática operativa.
