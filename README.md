# NexCrono Pistola

App Android (`com.codevnexus.pistola`) para capturar los chips de los competidores con pistolas RFID UHF en **salidas controladas** y **puntos de control**, y enviar cada pasada a **NexCrono**.

**Versión 1.4.0** · NexCrono · CodevNexus · codevnexus.tech

El uso completo está en el **manual**: `docs/MANUAL.md`, que también se ve dentro de la app con el botón **i**.

---

## Pantallas

| Pestaña | Para qué |
|---|---|
| **Lectura** | Captura. Muestra el último dorsal en grande con su nombre, los contadores, las pasadas recientes y el dorsal manual |
| **Control** | Indicadores de detectados y no detectados, listas *No detectados / Detectados / No inscritos* y carga de participantes desde Excel o CSV |
| **Eventos** | Historial en el teléfono: crear, abrir, editar, borrar y compartir eventos. **Compartir por WiFi** |
| **Pistola** | Emparejar, conectar, ver batería e información, potencia, configuración y AUTO.TXT, memoria SD, grabar número en chips y consola |
| **Ajustes** | Licencia, vinculación con NexCrono, punto de captura, número de chip, pasadas, respaldo, bitácora, manual y versión |

## Funciones

**Captura**
- Una **pasada** por chip mientras se sigue leyendo. Se cierra tras la *ventana* (5 s por defecto).
- La hora es la **última lectura** en la salida y la **primera** en los puntos de control (configurable).
- Trabaja en **segundo plano** con la pantalla bloqueada y reconecta la pistola sola.
- Avisos con color, sonido y vibración distintos para chip inscrito, no inscrito y manual.
- Dorsal manual y lectura de dorsales por código de barras.

**Número de chip**
- Formatos: *segmento del EPC* (con preajuste **TimingSense**: `00D6 01BE 0000…` → 446), últimos dígitos, hexadecimal → decimal, tabla EPC → chip o EPC completo.
- **Detectar con un chip:** lees un chip, escribes el número impreso y la app encuentra el formato sola.
- El número va **siempre ya convertido** en Excel, CSV, WiFi y NexCrono.

**Eventos y participantes** (SQLite en el teléfono)
- Historial con crear, abrir, editar, borrar y compartir.
- Participantes desde **Excel o CSV**, en eventos locales, o desde NexCrono.
- Indicadores de **detectados / no detectados / no inscritos**, con sus listas.
- Archivos de **pasadas** y de **participantes con su estado**, en Excel o CSV.
- **Compartir por WiFi:** servidor web en el teléfono con PIN, para descargar desde un navegador de la misma red o del hotspot.

**Pistola TSL** (protocolo *TSL ASCII 2.5*)
- Configuración al conectar:
  - gatillo mantenido = leer sin parar;
  - RSSI y fecha/hora en cada lectura;
  - sesión S0;
  - potencia y pitido;
  - **reloj de la pistola sincronizado**.
- **AUTO.TXT** en la micro SD, para que la configuración se mantenga aunque la pistola se duerma.
- **Memoria / SD:** importa lo leído **sin conexión**, con su hora y sin duplicados.
- **Grabar número en chips**, en el mismo formato con que la app los lee, con verificación.

**NexCrono**
- Vinculación por **QR**, con un token por teléfono que NexCrono puede revocar. La dirección se puede dejar para después.
- Hora sincronizada con NexCrono; la diferencia de reloj se guarda 12 horas.
- Cola sin internet: cada pasada guarda su estado de envío, intentos y error, y hay una **bitácora**.

**Licencia y actualizaciones**
- **Licencia .lic** firmada (ECDSA), vinculada al teléfono y con vencimiento. Sin licencia, modo demostración (25 pasadas por evento).
- **Actualización automática** desde tu hosting, con verificación SHA-256 y firma del APK.

## Estructura del proyecto

```
app/                          App Android (Kotlin + Jetpack Compose)
  src/main/java/com/codevnexus/pistola/
    MainActivity.kt           Navegación, vinculación por QR, avisos de actualización
    core/                     Lógica: Engine (motor), Db (SQLite), Uploader (NexCrono),
                              Exportador, Importador, Licencia, ServidorWeb, Actualizador
    driver/                   Pistolas: TSL ASCII 2.5 y genérica Bluetooth
    service/                  Servicio en segundo plano
    ui/                       Pantallas
docs/
  MANUAL.md                   Manual de uso (también dentro de la app)
  INSTRUCCIONES_NEXCRONO.md   Lo que NexCrono debe implementar (API, QR, seguridad)
  GUIA_FIRMA_Y_ACTUALIZACIONES.md   Firmar el APK y publicar actualizaciones
herramientas/
  GeneradorLicencias.java     Crear las llaves y emitir licencias .lic
servidor-actualizaciones/     Panel PHP para publicar versiones en tu hosting
.github/workflows/            Compilación automática del APK en GitHub
```

## Compilar

Cada `git push` compila en **GitHub → Actions → Compilar APK**. El artefacto `NexCronoPistola-apk` trae:
- `app-debug.apk`, sin secretos de firma;
- `app-release.apk` y su `apk.sha256`, con secretos de firma.

También se puede abrir la carpeta en **Android Studio** (*File → Open*) y usar *Build → Build APK(s)*.

Secretos opcionales de GitHub (*Settings → Secrets and variables → Actions*):

| Secreto | Para qué | Sin él |
|---|---|---|
| `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Firmar el APK con la llave de CodevNexus | APK de prueba (debug) |
| `NEXCRONO_LICENSE_KEY` | Clave pública de licencias | Sin control de licencia (desarrollo) |
| `NEXCRONO_UPDATE_URL` | Servidor de actualizaciones | Sin actualización automática |

Los pasos están en `docs/GUIA_FIRMA_Y_ACTUALIZACIONES.md` y en *Manual → Licencia*.

## Primer uso

1. Aceptar los permisos y tocar **Permitir** en el aviso de batería.
2. **Pistola:** buscar, emparejar (PIN `0000`) y conectar.
3. **Ajustes → Número de chip:** leer un chip, escribir el número impreso y tocar **Detectar**. Luego **GUARDAR AJUSTES**.
4. **Eventos:** crear el evento o traerlo de NexCrono.
5. **Control:** cargar los participantes (si el evento es local).
6. **Ajustes:** elegir *Salida controlada* o *Punto de control*.
7. Leer con el gatillo o con **INICIAR LECTURA**.

## Pistolas compatibles

| Driver | Modelos | Estado |
|---|---|---|
| TSL · ASCII 2.5 | 1128, 1153, 1166, 2128 | ✅ Todas las funciones |
| Genérica Bluetooth | Las que envían el EPC como texto | ✅ Solo recibe lecturas |
| Zebra / Chainway | RFD40, RFD8500 / R5, R6 | Pendiente: requiere sus SDK y un equipo para probar |

Se recomienda el firmware **5.1.2** del 1128, que se instala desde Windows con *TSL PC Firmware Downloader*. La pistola debe estar en modo **SPP**: en modo HID funciona como teclado y no le envía datos a la app.

## Historial de versiones

- **1.4.0:** eventos en SQLite con historial y CRUD; participantes desde Excel/CSV con indicadores y listas; compartir por WiFi con PIN; estados de sincronización y bitácora; licencia .lic; manual dentro de la app; número de chip convertido en todas las salidas.
- **1.3.x:** número de chip por segmento del EPC (TimingSense) y detección automática; grabado de chips en el mismo formato; AUTO.TXT; lectura de la memoria SD corregida.
- **1.2.0:** configuración de la pistola según *TSL ASCII Protocol 2.5* (gatillo, reloj, sesión S0) y memoria SD.
- **1.1.0:** vinculación con NexCrono por QR, inscritos, pestaña Control, dorsal manual, eventos, actualizaciones y firma.
- **1.0.0:** lectura en segundo plano, pasadas, hora sincronizada, envío a NexCrono y respaldo.
