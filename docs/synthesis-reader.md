# Lector de Síntesis sin recompilar Android

El widget mantiene sus placas y navegación. `modules/sintesis/reader.html` es
un visor de solo lectura: recibe HTML escapado por el APK y renderiza las
fórmulas con KaTeX 0.16.22, la misma versión y opciones usadas por Síntesis en v0.

## Generar el módulo

Editar `scripts/synthesis-reader.template.html` y ejecutar:

```powershell
node scripts/build-synthesis-reader.mjs ../v0-neon-database-persistence2-main/node_modules/katex
```

El script incorpora JavaScript, CSS, fuentes WOFF2 y licencia en un único HTML.
No usa CDN ni requiere cambios nativos. El APK bloquea recursos externos y
solo resuelve `https://synthesis.local/assets/...` y `/images/{id}`. Las fuentes
incorporadas usan `data:` y están permitidas por la política de contenido.

## Contrato con v0

El endpoint autenticado `/api/inscreen/provider/synthesis` proyecta cada nodo
`synthesisMath` como texto `[[INSCREEN-MATH-V1:<base64url>]]`. El contenido es
JSON UTF-8 con `{latex: string, display: boolean}`. Así sobrevive al conversor
nativo existente, que ignora nodos matemáticos pero conserva texto. Se mantienen
las marcas y no se modifican los documentos originales ni los bloques de código.

Solo los H1 representan ramas en v0. Los H2–H6 se transportan dentro de un nodo
`inscreenReaderContent`, que el renderizador nativo recorre sin crear otra rama.
Cada H1 lleva un párrafo con un enlace de etiqueta invisible (`U+2060`) y URL
`https://synthesis.local/#INSCREEN-NODE-V1:<base64url>` con `{id, parentId}`.
El payload vive en el atributo, por lo que tampoco aparece como texto al usar
un lector anterior en caché. No se consulta esa URL. El lector retira ese párrafo
y los antiguos marcadores textuales `[[INSCREEN-NODE-V1:…]]`, aunque estos no
estén al principio o no puedan decodificarse. Restaura las relaciones de v0 sin el
límite de tres niveles del renderizador nativo. «Leer» incluye el contenido propio
del nivel abierto y todos sus descendientes; desde la raíz incluye todas las ramas.
Cada apertura comienza arriba, sin desplazamiento horizontal ni zoom anterior.

El lector procesa únicamente nodos de texto fuera de `pre` y `code`, decodifica
los marcadores y llama a KaTeX con `throwOnError: false`, `trust: false` y
`strict: 'ignore'`. Un fallo conserva el LaTeX; datos inválidos quedan literales.

Las imágenes siguen usando los IDs y el token del proveedor en el APK. El
endpoint de imágenes de v0 identifica PNG, JPEG, GIF y WebP por sus bytes y
devuelve el MIME correcto, aun con metadatos R2 antiguos o genéricos. No admite
SVG y conserva el límite de 5 MB. El lector muestra un aviso si la carga falla.

## Validación y publicación

Desde InScreen: `node modules/sintesis/browser-smoke.js` verifica navegación y
zoom. Desde v0: `npm run test:inscreen-reader` verifica sus endpoints con R2
simulado y el HTML real de este repositorio, incluyendo imágenes, fórmulas,
fuentes incorporadas, código literal y ausencia de edición. Los repositorios
deben estar juntos para esta prueba; `npm test` ejecuta los tests unitarios de v0.

Publicar primero el lector en `main` de InScreen y después desplegar v0. No
crear una etiqueta Android: las etiquetas `v*` disparan la compilación de APK.
Reabrir el widget conectado para que descargue el módulo. Si ya tenía un lector
en caché, la descarga ocurre en segundo plano; volver a abrirlo y actualizar la
semana después de completarla. Los documentos e imágenes ya consultados usan
la caché nativa al perder conexión. Las semanas antiguas deben actualizarse
con conexión para recibir los nuevos marcadores de fórmulas.

La prueba final en el celular requiere el APK instalado con soporte de lector
descargable. Estos tests no confirman la configuración R2 ni el estado de
sincronización de un dispositivo real.
