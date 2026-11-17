# NexCrono Pistola

App Android (`com.codevnexus.pistola`) para capturar chips con pistolas RFID UHF en **salidas controladas** y **puntos de control**, y enviar cada pasada a **NexCrono**.

NexCrono · CodevNexus · codevnexus.tech

## Versión 1.3.1
- **Vinculación con NexCrono por QR.** Se escanea con la app, con la cámara del teléfono (enlace `nexcrono://`) o con el lector 2D de la pistola. Cada teléfono recibe su propio token, que se puede revocar desde NexCrono. La URL se puede dejar vacía y configurar después.
- **Número de chip en cualquier formato:**
  - Opción *Segmento del EPC* (posición, largo, hexadecimal o decimal).
  - Preajuste **TimingSense** (`00D6 01BE 0000…` → 446).
  - *Detectar con un chip*: lees un chip, escribes el número impreso y la app encuentra el formato sola.
- **Lista de inscritos:** al capturar un chip, la app muestra **dorsal, nombre y categoría** y avisa si el chip no está inscrito.
- **Pestaña Control:** muestra quién **falta por pasar** (en la salida, quién no se leyó en el corral), con buscador. Si tocas un atleta, lo registras a mano.
- **Dorsal manual** para chips que no leen. También se pueden leer dorsales con el **código de barras** si la pistola tiene lector 2D. Quedan marcados como `manual` o `barras`.
- **Eventos separados:** cada evento guarda sus pasadas. Se elige de NexCrono o se crea uno local.
- **Avisos más claros:** destello de color, sonido y vibración distintos para chip conocido, no inscrito y manual.
- **Pistola configurada al conectar** (según *TSL ASCII Protocol 2.5*):
  - Mantener el gatillo lee sin parar (`.sa -s inv -rs 50`).
  - RSSI y fecha/hora en cada lectura.
  - Sesión S0, para que la «última lectura» de la salida sea precisa.
  - Potencia ajustable y pitido de la pistola opcional.
  - **Reloj de la pistola sincronizado** con NexCrono.
- **Memoria / micro SD:** descarga el registro `LOG.TXT` (`.rl`) con lo leído cuando la pistola no estaba conectada y lo convierte en pasadas del evento, sin duplicados. También permite borrarla.
- **AUTO.TXT:** graba la configuración en la micro SD para que se aplique cada vez que la pistola despierta. Así funciona también **sin el teléfono** y lo leído queda con hora en la SD.
- **Lectura de la memoria más segura:**
  - Solo importa lo leído **sin conexión**, porque lo leído con el teléfono conectado ya está registrado en vivo.
  - Solo usa la hora que viene dentro de cada inventario. Las lecturas sin hora se cuentan y se avisa, en vez de asignarles una hora equivocada.
- **Grabar número en chips:** lee el chip más cercano a baja potencia, escribe el número en su EPC (`.ws` con selección por el EPC actual), lo verifica y pasa al siguiente.
- **Comandos al conectar** para ajustes extra.
- **Actualización automática** desde tu hosting, con verificación SHA-256 y firma, y **APK firmado** con la llave de CodevNexus.
- Lo que ya traía la 1.0:
  - Número de chip en lugar del EPC.
  - Segundo plano con la pantalla bloqueada y reconexión automática.
  - Hora sincronizada con NexCrono.
  - Cola sin internet.
  - Respaldo en CSV o Excel.
  - Info y batería de la pistola, y potencia ajustable.

## Documentos
- `docs/INSTRUCCIONES_NEXCRONO.md`: lo que hay que agregar en NexCrono (panel, QR, API y seguridad). Se pega en el proyecto de NexCrono.
- `docs/GUIA_FIRMA_Y_ACTUALIZACIONES.md`: crear la llave, firmar el APK y subir el servidor de actualizaciones.
- `servidor-actualizaciones/`: panel PHP para publicar versiones.

## Compilar
Cada `git push` compila en **Actions** (*Compilar APK*). El artefacto `NexCronoPistola-apk` trae:
- **Sin secretos de firma:** `app-debug.apk`.
- **Con secretos de firma:** `app-release.apk`, más su `apk.sha256`.

## Pistolas
| Driver | Modelos | Estado |
|---|---|---|
| TSL · ASCII 2 | 1128, 1153, 1166, 2128 | ✅ lectura, gatillo, batería y carga, info, potencia, reloj, memoria SD, código de barras |
| Genérica Bluetooth (texto) | Las que envían el EPC como texto por SPP | ✅ solo recibe |
| Zebra / Chainway | — | Pendiente: requiere sus SDK y un equipo para probar |

**Memoria / micro SD (TSL):** las lecturas hechas sin conexión quedan en la SD con hora al segundo (reloj de la pistola, que la app sincroniza en cada conexión). Se marcan con origen `memoria`.

## Firmware de la pistola
Se recomienda tener el 1128 con el firmware **5.1.2**. Se actualiza desde Windows con *TSL PC Firmware Downloader* y el cable USB. La pistola debe estar en modo **SPP**: en modo HID funciona como teclado y no le envía datos a la app.

## Primer uso
1. Aceptar los permisos y tocar *Permitir* en el aviso de batería.
2. En **Pistola**: buscar, emparejar (PIN 0000) y conectar.
3. En **Ajustes**: *Escanear QR de NexCrono*. Si NexCrono aún no tiene la vinculación, se puede usar un evento local y vincular después.
4. Leer con el gatillo o con *INICIAR LECTURA*.
