# NexCrono Pistola

App Android (`com.codevnexus.pistola`) para capturar chips con pistolas RFID UHF en **salidas controladas** y **puntos de control**, y enviar cada pasada a **NexCrono**.

NexCrono · CodevNexus · codevnexus.tech

## Qué hace
- **Número de chip**, no el EPC largo. La conversión es configurable: últimos dígitos, hexadecimal → decimal o tabla EPC → chip importada desde CSV. El EPC completo también se guarda y se envía.
- **Pasadas**: mientras un chip sigue a la vista cuenta como una sola pasada. Cuando deja de leerse durante la *ventana* (5 s por defecto), la pasada se cierra y se envía.
  - Salida: registra la **última** lectura, el momento en que el atleta sale del alcance.
  - Punto de control: registra la **primera** lectura.
  - Ambas reglas se pueden cambiar a primera, última o mejor señal. NexCrono recibe siempre las tres horas.
- **Segundo plano**: un servicio con notificación fija sigue leyendo y enviando con la pantalla bloqueada. Si se corta el Bluetooth, reconecta solo cada 5 s.
- **Sin pérdida de datos**: todo se guarda en SQLite en el teléfono. Si no hay red, se encola y se reenvía.
- **Hora sincronizada con NexCrono**: el teléfono calcula la diferencia de reloj con la computadora de NexCrono.
- **Pistola**: buscar y emparejar desde la app, y ver batería, fabricante, serie y firmware. Incluye potencia ajustable y una consola de comandos.
- **Respaldo** en CSV o Excel, en `Descargas/NexCrono`.

## Pistolas soportadas
| Driver | Modelos | Estado |
|---|---|---|
| TSL · ASCII 2 | 1128, 1153, 1166, 2128 | ✅ lectura, batería, info, potencia |
| Genérica Bluetooth (texto) | Pistolas que envían el EPC como texto por SPP | ✅ solo recibe (lectura con su gatillo) |
| Zebra RFD40 / RFD8500 | — | Pendiente: requiere el SDK de Zebra |
| Chainway R5 / R6 | — | Pendiente: requiere el SDK de Chainway |

Para agregar una marca se implementa la interfaz `RfidDriver` (`driver/Drivers.kt`) y se registra en `Drivers.lista` / `Drivers.crear`.

**Memoria / micro SD (TSL):** el botón está, pero la descarga aún no está activa. Los comandos están en la nota de TSL *Using the Micro SD Log and Autorun Files*, que requiere login en tsl.com. Con ese PDF se completa `TslAsciiDriver.leerMemoria()`.

## Compilar el APK
**GitHub:** sube esta carpeta (incluida `.github/`) a un repositorio. En **Actions**, el flujo *Compilar APK* deja el artefacto `NexCronoPistola-apk`.

**Android Studio:** *File → Open* esta carpeta (si ofrece crear el Gradle Wrapper, acepta) y luego *Build → Build APK(s)*.

Requiere Android 8.0 o superior.

## Primer uso
1. Abrir la app y aceptar los permisos de *Dispositivos cercanos* y *Notificaciones*.
2. En **Lectura**, tocar *Permitir* en el aviso de batería. Sin esto, algunos teléfonos cortan la app con la pantalla bloqueada.
3. En **Pistola**: elegir el tipo, *Buscar pistolas* → *Emparejar* (PIN 0000) y tocar la pistola para conectar.
4. En **Ajustes**: elegir *Salida controlada* o *Punto de control* (con su nombre). Configurar el número de chip leyendo un chip para ver la conversión en vivo. Poner la dirección y el token de NexCrono.
5. Leer con el gatillo o con *INICIAR LECTURA*.

---

## Contrato de API que NexCrono debe implementar

Base configurable en la app, por ejemplo `http://192.168.1.10:8080` (red local, sin internet) o el subdominio del túnel. Todas las peticiones llevan `Authorization: Bearer <token>` si se configuró un token.

### 1. Hora del servidor
`GET /api/pistola/hora`

Respuesta `200`:
```json
{ "epochMs": 1763380000000 }
```
(también se acepta el número solo en texto plano). La app lo usa para corregir su reloj. Sin esto, las lecturas llevan `horaSincronizada: false`.

### 2. Recibir lecturas
`POST /api/pistola/lecturas` · `Content-Type: application/json`

```json
{
  "dispositivo": "Galaxy A15 meta",
  "operador": "Juan",
  "punto": "PC1",
  "modo": "CONTROL",
  "app": "NexCrono Pistola 1.0.0",
  "enviado": "2026-11-17T08:15:02.120-05:00",
  "lecturas": [
    {
      "id": "6f1c2a0e-3b7d-4a51-9a51-1d2b8f0c9e11",
      "chip": "1234",
      "epc": "300833B2DDD9014000001234",
      "punto": "PC1",
      "modo": "CONTROL",
      "horaMs": 1763385301123,
      "hora": "2026-11-17T08:15:01.123-05:00",
      "primeraMs": 1763385301123,
      "ultimaMs": 1763385303870,
      "rssi": -56,
      "lecturas": 14,
      "origen": "vivo",
      "horaSincronizada": true
    }
  ]
}
```
- `modo`: `SALIDA` o `CONTROL`. `punto` es `SALIDA` o el nombre del punto de control.
- `horaMs` es la hora según la regla elegida en la app. `primeraMs` y `ultimaMs` permiten a NexCrono aplicar su propia regla.
- Se envían hasta 200 lecturas por petición.
- **Responder 2xx solo cuando se guardaron.** Con cualquier otro código, la app reintenta cada 3 s.
- **`id` es único por pasada.** Si llega uno repetido (reintento), NexCrono debe ignorarlo y aun así responder 2xx.
