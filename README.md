# NexCrono Pistola

App Android (`com.codevnexus.pistola`) para capturar chips con pistolas RFID UHF en **salidas controladas** y **puntos de control**, y enviar cada pasada a **NexCrono**.

NexCrono · CodevNexus · codevnexus.tech

## Versión 1.1.0
- **Vinculación con NexCrono por QR.** Se escanea con la app, con la cámara del teléfono (enlace `nexcrono://`) o con el lector 2D de la pistola. Cada teléfono recibe su propio token, que se puede revocar desde NexCrono. La URL se puede dejar vacía y configurar después.
- **Lista de inscritos:** al capturar un chip, la app muestra **dorsal, nombre y categoría** y avisa si el chip no está inscrito.
- **Pestaña Control:** muestra quién **falta por pasar** (en la salida, quién no se leyó en el corral), con buscador. Si tocas un atleta, lo registras a mano.
- **Dorsal manual** para chips que no leen. También se pueden leer dorsales con el **código de barras** si la pistola tiene lector 2D. Quedan marcados como `manual` o `barras`.
- **Eventos separados:** cada evento guarda sus pasadas. Se elige de NexCrono o se crea uno local.
- **Avisos más claros:** destello de color, sonido y vibración distintos para chip conocido, no inscrito y manual.
- **Comandos al conectar:** configuran la pistola (por ejemplo el gatillo) cada vez que se conecta.
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
| TSL · ASCII 2 | 1128, 1153, 1166, 2128 | ✅ lectura, batería, info, potencia, código de barras |
| Genérica Bluetooth (texto) | Las que envían el EPC como texto por SPP | ✅ solo recibe |
| Zebra / Chainway | — | Pendiente: requiere sus SDK y un equipo para probar |

**Memoria / micro SD (TSL):** pendiente. Necesita la nota de TSL *Using the Micro SD Log and Autorun Files*.

## Primer uso
1. Aceptar los permisos y tocar *Permitir* en el aviso de batería.
2. En **Pistola**: buscar, emparejar (PIN 0000) y conectar.
3. En **Ajustes**: *Escanear QR de NexCrono*. Si NexCrono aún no tiene la vinculación, se puede usar un evento local y vincular después.
4. Leer con el gatillo o con *INICIAR LECTURA*.
