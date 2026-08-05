# InScreen

Asistente local para Windows que combina el material visible en la ventana activa con
una pregunta hablada desde InScreen Mic para Android o escrita directamente en el overlay.

Con el celular conectado, la primera pulsación de `-` comienza a grabar y la segunda
detiene el audio. Sin celular, `-` captura la ventana y abre el editor; Enter envía la
pregunta. En ambos casos Qwen recibe directamente la captura y la consulta; el OCR
separado solo se usa como recuperación automática si falla la respuesta visual.

## Requisitos

- Windows 10/11.
- Android 8 o posterior si se utilizará entrada por voz; el flujo está validado para Android 12/13.
- PC y celular en la misma red Wi-Fi, sin aislamiento de clientes, cuando se use Android.
- Python 3.11 o posterior para ejecutar desde el código.
- Una clave de Groq.

## Instalación

Desde PowerShell, en la carpeta del proyecto:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

Crea `.env` junto a `app.py`:

```text
GROQ_API_KEY=tu_clave_de_groq
```

Luego inicia la aplicación:

```powershell
.\.venv\Scripts\python.exe app.py
```

## Instalar y conectar el celular por primera vez

Instala `mobile\InScreenMic.apk` y abre la app con ambos dispositivos en la misma red
Wi-Fi. Pulsa **CONECTAR** o el botón de cámara: el celular solicitará la vinculación y
recién entonces Windows mostrará el QR. Léelo con la cámara integrada, concede
micrófono y notificaciones y acepta excluir InScreen de la optimización de batería.

La app verifica la huella del certificado local incluida en el enlace de vinculación y
guarda la PC. En los siguientes usos basta con abrir InScreen Mic: se conecta y queda
reintentando en segundo plano. Si cambia la IP de la PC, Android la descubre en la red
local mediante mensajes autenticados y conserva la misma vinculación.

Para sincronizar música, abre la página de conexión del APK y pulsa **HABILITAR
CONTROL MULTIMEDIA**. Android abrirá el ajuste de acceso a notificaciones: habilita
InScreen Mic allí y vuelve a la app. InScreen no lee ni transmite el contenido de las
notificaciones; el acceso se usa solamente para conocer y controlar sesiones multimedia.

Si Windows no muestra el QR:

- Autoriza InScreen para redes privadas cuando aparezca el aviso del Firewall de Windows.
- Confirma que ambos dispositivos estén en la misma Wi-Fi.
- Algunas redes de invitados impiden que dos dispositivos se comuniquen.
- La red debe permitir broadcast UDP en el puerto `8763`.

## Uso

- Con el celular conectado, el primer `-` captura la ventana e inicia el audio; el
  segundo `-` detiene la grabación y comienza la transcripción. Durante la grabación
  puedes usar el switch para decidir si se enviará la captura.
- Sin celular conectado, `-` captura la ventana y reemplaza el resultado anterior por
  un editor. Escribe la pregunta y pulsa Enter para enviarla.
- Después de una respuesta, otro `-` inicia una consulta nueva y captura otra imagen.
  La imagen y la pregunta se envían juntas al modelo multimodal al pulsar Enter.
- `|`: cancela la consulta en cualquier estado, descarta sus resultados pendientes,
  oculta el panel y reanuda la música.
- `Ctrl+Shift+Q`: cierra completamente InScreen.

InScreen se inicia en segundo plano y aparece en la bandeja de Windows, junto al reloj.
Desde su menú puedes comprobar el estado, abrir el registro de errores o salir.
Con el celular conectado y el control multimedia habilitado, un clic izquierdo sobre
el icono pausa o reanuda la música del teléfono. El clic derecho conserva el menú.
Si vuelves a ejecutar `InScreen.exe` mientras ya está abierto, la segunda copia termina
silenciosamente: se conserva una sola instancia y un único servidor en los puertos configurados.

Mientras se procesa una consulta se ignoran pulsaciones adicionales de `-`. El panel
solo recibe foco durante la escritura sin celular. La rueda desplaza la respuesta cuando el puntero está sobre su área negra;
fuera del panel continúa desplazando el PDF o navegador. El panel crece con el contenido
hasta ocupar como máximo el 85% de la pantalla y, si la respuesta es más larga, muestra
una barra de desplazamiento verde.

Las fórmulas se renderizan localmente con KaTeX: `\(m=2\)` se muestra en línea y
`\[\frac{a+b}{c}\]` centrada. El texto sigue siendo breve, verde y sin Markdown;
las fórmulas son blancas y no necesitan internet. La app del celular puede permanecer
conectada con la pantalla apagada durante una clase completa. Tener
concedido el permiso no significa que el micrófono esté abierto: Android solo lo activa
entre las dos pulsaciones y la app lo libera antes de subir el archivo.

El celular guarda temporalmente AAC/M4A durante la grabación. Al detener, primero cierra
el micrófono y después transmite el archivo por fragmentos cifrados a la PC. Ambos lados
eliminan sus temporales al terminar. La captura y la voz transcritas son contexto interno:
con el celular solo se muestra la respuesta. En modo teclado se ve lo escrito hasta Enter
y luego ese mismo bloque se reemplaza por el estado de proceso y la respuesta.

Las transcripciones de voz exitosas se conservan en
`Documentos\InScreen\Transcripciones\<Materia>`. La materia se toma del primer elemento
de la cola Apriori al comenzar la grabación; las preguntas escritas y las respuestas no
se guardan allí.

## Configuración

InScreen usa `qwen/qwen3.6-27b` cuando la consulta incluye una captura y para el OCR de
respaldo. Las consultas sin imagen y la respuesta textual posterior al OCR usan
`llama-3.3-70b-versatile`. Whisper permanece dedicado a convertir la voz en texto. Los
modelos son internos y los campos antiguos `vision_model`/`text_model` se ignoran.

`config.json` permite ajustar las demás opciones:

```json
{
  "audio_model": "whisper-large-v3-turbo",
  "hotkey": "-",
  "language": "es",
  "font_size": 18,
  "prompt": null,
  "setup_port": 8764,
  "https_port": 8765,
  "lan_host": null,
  "max_recording_seconds": 300,
  "vision_wait_seconds": 8.0,
  "pause_media_during_recording": true,
  "overlay_width_ratio": 0.76,
  "send_image": true
}
```

`hotkey` se mantiene en `-` para este flujo. Si la detección automática elige una
interfaz VPN o una IP incorrecta, define `lan_host` con la IPv4 local de la PC, por
ejemplo `"192.168.0.14"`.

El campo `prompt` controla el contenido y el estilo de la respuesta. InScreen agrega
siempre al final un contrato de formato para KaTeX (`\(...\)` y `\[...\]`), por lo
que un prompt personalizado no puede habilitar `$`, HTML, Markdown ni bloques de código.

`send_image` refleja el switch disponible al escribir y al grabar desde el celular. La
aplicación actualiza este valor automáticamente: activado adjunta la captura y usa Qwen;
desactivado envía solo la pregunta y usa Llama.

Los puertos deben estar libres. `max_recording_seconds` limita cada pregunta, no el
tiempo que la app puede permanecer conectada. `vision_wait_seconds` controla cuánto se
espera el OCR de recuperación cuando falla la respuesta multimodal directa.
Con `pause_media_during_recording` activado, InScreen consulta la sesión multimedia de
Windows. Solo pausa si el contenido está reproduciéndose y solo reanuda la misma sesión
si fue InScreen quien la pausó. Un video que ya estaba pausado permanece pausado.

La sincronización multimedia comienza a observar después de conectar, sin alterar lo
que ya estaba reproduciéndose. Pausar música en el celular inicia la sesión multimedia
actual de Windows si estaba pausada. Iniciar música en el celular pausa Windows, e
iniciar una sesión en Windows pausa el celular si estaba reproduciendo. Pausar el video
de Windows no reanuda música. Los finales de pista y estados detenidos no se consideran
una pausa intencional.

## Widget Apriori

El APK incluye un widget de pantalla de inicio de tamaño objetivo 4×1. Replica la
tarjeta grande de la primera materia de la cola, con su sigla y color. Al tocarlo abre
el módulo de esa materia; si todavía no tiene módulo, abre el buscador. Con la cola
vacía muestra **Sin materias** y abre la app al tocarlo.

Para agregarlo, mantén presionado un espacio libre de la pantalla de inicio, abre
**Widgets**, busca **InScreen Mic** y arrastra el widget Apriori.

## Módulos de estudio

Los módulos se publican en el mismo repositorio, dentro de `modules/`. El catálogo
`modules/index.json` lista cada `id`, `nombre` y `entry`; cada entrada apunta a un
`modules/<id>/index.html` público. Al tocar una materia de la segunda pestaña (o el
widget), InScreen permite elegir un módulo si todavía no tiene uno asignado.

El HTML se desarrolla de forma independiente y recibe solamente `window.InScreen.module`:

```js
const materia = InScreen.module.context();
const resultado = await InScreen.module.respyPreg(6);
```

También están disponibles `paginasLeidas(dia)` y `traduccion(dia)`. Por ahora las tres
funciones devuelven `{ ok: false, error: "provider_not_configured", day }`; el futuro
proveedor Vercel conservará este contrato y no requiere credenciales en el APK.

## Actualizaciones del APK

En la pestaña de Conexiones, el ícono verde de recarga de la esquina superior derecha busca
la versión más reciente publicada en GitHub. Si hay una nueva, InScreen descarga
`InScreenMic.apk` y abre el instalador de Android. La primera vez, Android puede pedir
permiso para instalar aplicaciones desde InScreen; luego hay que confirmar cada
instalación.

Las versiones se publican desde etiquetas `vX.Y.Z`. El workflow de GitHub Actions
ejecuta las pruebas, `lintDebug`, compila el APK firmado y lo adjunta al Release. El
repositorio debe contener estos secretos de Actions, todos asociados a la misma firma
usada en versiones anteriores:

- `INSCREEN_KEYSTORE_BASE64`
- `INSCREEN_STORE_PASSWORD`
- `INSCREEN_KEY_ALIAS`
- `INSCREEN_KEY_PASSWORD`

Si Groq responde con un límite temporal (`429`), el panel muestra únicamente una cuenta
regresiva numérica pequeña y reintenta una vez. `|` también cancela esta espera.

## Crear el `.exe` portable

El build completo requiere JDK 17 y Android SDK Platform 36. La primera compilación crea
una firma local en `mobile_android/.signing`; guarda una copia de seguridad de esa
carpeta, porque Android exige la misma firma para instalar actualizaciones sobre el APK
existente.

```powershell
powershell -ExecutionPolicy Bypass -File .\build_portable.ps1
```

Para compilar únicamente el APK:

```powershell
powershell -ExecutionPolicy Bypass -File .\build_android.ps1
```

Si el APK ya existe y solo quieres rehacer Windows, ejecuta
`powershell -ExecutionPolicy Bypass -File .\build_portable.ps1 -SkipAndroidBuild`.

El resultado queda en:

```text
dist\InScreen\InScreen.exe
```

Copia la carpeta `dist\InScreen` completa. El APK queda incluido dentro del portable y
puede descargarse desde la página HTTP local de instalación. Deben permanecer junto al ejecutable:

- `.env`
- `config.json`
- `_internal`

La identidad persistente se guarda en `%LOCALAPPDATA%\InScreen\runtime`, incluyendo el
token, la autoridad local y las claves HTTPS. Una instalación anterior migra esos datos
desde `runtime` junto al ejecutable sin sobrescribir una identidad nueva. No publiques
ni compartas esa carpeta; si la eliminas, deberás volver a vincular el celular.

El portable no abre una consola. Queda disponible en la bandeja de Windows y acepta `-`
desde el inicio. El visor matemático se carga recién cuando hace falta mostrar una
respuesta.

## Solución de problemas

- **CELULAR NO LISTO:** abre la app y espera la notificación `InScreen conectado`; no
  hace falta pulsar `CONECTAR` si ya estaba vinculada.
- **No se puede vincular:** pulsa la cámara dentro del APK y verifica que Windows haya
  mostrado el QR solicitado por el celular.
- **La señal tarda con la pantalla apagada:** confirma en Ajustes de batería que InScreen
  figure como `Sin restricciones` o excluido de la optimización.
- **NO LLEGÓ EL AUDIO:** revisa la Wi-Fi y abre la app para comprobar su estado.
- **NO SE PUDO CAPTURAR:** activa una ventana normal; el Escritorio o una ventana
  minimizada no son capturas válidas.
- **Puerto ocupado:** cambia `setup_port` y `https_port` en `config.json`.
- **Necesitas vincular nuevamente:** pulsa **CONECTAR** o la cámara en el celular para
  solicitar un QR nuevo.
- **Parece quedar esperando:** abre el registro desde el icono de bandeja o revisa
  `%LOCALAPPDATA%\InScreen\runtime\inscreen.log`. El archivo rotativo conserva solo
  advertencias y errores, no la actividad normal.

Groq admite el M4A/AAC generado por Android en su endpoint de transcripción. InScreen
limita cada archivo a 24 MB para mantenerse debajo del límite de 25 MB del nivel
gratuito.
