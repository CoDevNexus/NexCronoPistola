# Instrucciones para NexCrono: vincular la app «NexCrono Pistola»

> **Cómo usar este documento:** pégalo completo en el proyecto de NexCrono (en la conversación con Claude donde desarrollas NexCrono) junto con este pedido:
> *«Agrega a NexCrono lo que describe este documento, respetando la arquitectura, la base de datos y el panel que ya existen. No cambies el contrato de la API porque la app Android ya está hecha.»*

---

## 1. Contexto

**NexCrono Pistola** (`com.codevnexus.pistola`) es una app Android de CodevNexus. Con una pistola RFID UHF (TSL 1128 u otras) captura los chips de los atletas en:
- **Salida controlada**: registra la **última** lectura de cada chip en el corral, es decir, el momento en que el atleta sale.
- **Puntos de control** (PC1, KM10…): registra la **primera** lectura.

La app agrupa las lecturas repetidas de un mismo chip en una sola **pasada**. Las guarda en el teléfono (SQLite), funciona sin internet y las envía a NexCrono cuando hay conexión, por la red local o por el túnel (`controlX.dominio`). Cada pasada lleva un `id` único (UUID), así que los reintentos no duplican datos.

NexCrono tiene que aportar 4 cosas:
1. **Vinculación por QR** de cada teléfono, con un token propio por dispositivo.
2. Una **API** `/api/pistola/*` con el contrato exacto de la sección 4.
3. La **integración de las pasadas** en el cronometraje: salidas, puntos de control y seguimiento en vivo.
4. Un **panel de dispositivos** para ver, renombrar y revocar.

---

## 2. Panel «Dispositivos móviles» (nuevo)

### 2.1 Generar QR de vinculación
Formulario:
- **Evento**: los eventos existentes.
- **Modo**: `SALIDA` o `CONTROL`.
- **Punto**: nombre del punto de control, por ejemplo `PC1` o `KM10`. Para SALIDA se envía `SALIDA`.
- **URL base** que usará el teléfono, configurable. Puede ser la IP de la red local, por ejemplo `http://192.168.1.10:8080`, o el subdominio del túnel, por ejemplo `https://control1.fasttimeecuador.com`. Debe salir de la configuración de NexCrono, nunca fija en el código, porque otros cronometristas usan otros dominios.

Al generar:
- Crear un **código de un solo uso**: 8 caracteres de un alfabeto sin ambiguos (`ABCDEFGHJKMNPQRSTUVWXYZ23456789`), mostrado como `XXXX-XXXX`.
- **Vence a los 10 minutos.** Se guarda **solo su hash SHA-256**, junto con el evento, el modo, el punto y la fecha de vencimiento.
- Mostrar en pantalla el **QR** y debajo el **código en texto** para ingresarlo a mano.

**Contenido exacto del QR** (los valores van codificados con URL-encoding):
```
nexcrono://vincular?u=<URL_BASE>&c=<CODIGO>&p=<PUNTO>&m=<SALIDA|CONTROL>&e=<ID_EVENTO>&en=<NOMBRE_EVENTO>
```
Ejemplo:
```
nexcrono://vincular?u=https%3A%2F%2Fcontrol1.fasttimeecuador.com&c=K7P2-9QX4&p=PC2&m=CONTROL&e=42&en=Trail%20Cotopaxi%202026
```
La app lee este QR con su escáner, con la cámara normal del teléfono (el esquema `nexcrono://` abre la app) o con el lector 2D de la pistola.

### 2.2 Lista de dispositivos
Tabla con: nombre del equipo, operador, punto, modo, evento, versión de la app, **última conexión**, cantidad de pasadas recibidas e IP de la última petición.

Acciones:
- **Revocar**: invalida el token al instante. A partir de ahí la API responde 401 y la app pide volver a vincular.
- **Cambiar punto o modo**: solo informativo en el panel. La app manda el punto en cada lectura.
- **Eliminar.**

### 2.3 Vista en vivo
Por evento y punto: las últimas pasadas recibidas (dorsal, nombre, hora, punto, dispositivo, origen) y un contador por punto. Resaltar con un color:
- `origen = manual` o `barras`: hora tomada a mano, requiere revisión.
- `origen = memoria`: viene de la SD de la pistola, con precisión de 1 s.
- Chips no inscritos.
- `horaSincronizada = false`.

---

## 3. Modelo de datos (adaptar a la base actual)

- **dispositivos_moviles**: `id` (el `dispositivoId` que envía la app, UUID), `nombre`, `operador`, `token_hash` (SHA-256), `evento_id`, `modo`, `punto`, `app_version`, `creado`, `ultimo_contacto`, `ultima_ip`, `revocado` (bool).
- **codigos_vinculacion**: `codigo_hash`, `evento_id`, `modo`, `punto`, `vence`, `usado` (bool), `creado_por`.
- **lecturas_moviles**: `id` (UUID de la app, **PRIMARY KEY o UNIQUE**), `dispositivo_id`, `evento_id`, `dorsal` (nullable), `chip`, `epc`, `punto`, `modo`, `hora_ms` (entero, epoch en ms), `primera_ms`, `ultima_ms`, `rssi` (nullable), `lecturas`, `origen` (`vivo`|`manual`|`barras`|`memoria`), `hora_sincronizada` (bool), `recibido`. Si NexCrono ya tiene una tabla de lecturas (por ejemplo, la del TimeSense), se puede usar esa con una columna `fuente = 'pistola'` y `punto`.

---

## 4. API — contrato exacto (la app ya lo usa)

Reglas generales:
- Prefijo: `/api/pistola`. Todo en JSON UTF-8.
- Autenticación (todas menos `vincular`): cabecera `Authorization: Bearer <token>`. La app manda además `X-Dispositivo: <dispositivoId>`.
- Respuestas de error: `{"error":"mensaje"}` con estos códigos:
  - `400` datos inválidos.
  - `401` token inválido o revocado: la app pide volver a vincular.
  - `410` código de vinculación vencido.
  - `413` cuerpo demasiado grande.
  - `429` demasiadas peticiones.
  - `500` sin detalles internos.
- Cada petición autenticada actualiza `ultimo_contacto` y `ultima_ip` del dispositivo.

### 4.1 `POST /api/pistola/vincular` (sin token)
Petición:
```json
{ "codigo": "K7P2-9QX4", "dispositivoId": "6f1c…-uuid", "dispositivo": "Galaxy A15 meta", "app": "NexCrono Pistola", "version": "1.4.0" }
```
Validar:
- Que el código exista con su hash, no esté usado y no esté vencido. Si no es válido, responder **401**. Si está vencido, **410**.
- Límite de **5 intentos por minuto por IP**. Si se pasa, **429**.

Si es válido:
- Marcar el código como **usado**.
- Generar un token aleatorio de **32 bytes** (hex o base64url) y guardar solo su **SHA-256** en `dispositivos_moviles`. Si el `dispositivoId` ya existía, se reemplaza su token.

Respuesta `200`:
```json
{
  "token": "<token en claro, solo se entrega esta vez>",
  "empresa": "FastTime Ecuador",
  "punto": "PC2",
  "modo": "CONTROL",
  "evento": { "id": "42", "nombre": "Trail Cotopaxi 2026", "fecha": "2026-11-22" }
}
```
`empresa` es el nombre del cronometrista o cliente configurado en NexCrono (la marca del cliente). La app lo muestra en el encabezado.

### 4.2 `GET /api/pistola/hora`
Respuesta: `{"epochMs": 1763385301123}` con el reloj de la computadora de NexCrono. La app calcula su diferencia de reloj con esta hora y corrige todas sus marcas. **Responder lo más rápido posible**, sin consultas pesadas.

### 4.3 `GET /api/pistola/eventos`
Devuelve los eventos activos o próximos:
```json
{ "eventos": [ { "id": "42", "nombre": "Trail Cotopaxi 2026", "fecha": "2026-11-22" } ] }
```

### 4.4 `GET /api/pistola/inscritos?evento=42`
```json
{
  "evento": "42",
  "inscritos": [
    { "dorsal": "1234", "chip": "1234", "epc": "300833B2DDD9014000001234", "nombre": "Ana Pérez", "categoria": "Elite F", "genero": "F", "club": "Runners UIO" }
  ]
}
```
- `dorsal` es obligatorio. Con `epc` y/o `chip` la app identifica al atleta al leer su chip: primero busca por `epc` y después por `chip`. Si NexCrono guarda la relación chip ↔ dorsal, mándala aquí.
- **Privacidad:** enviar **solo** estos campos. Nunca cédula, teléfono, correo ni fecha de nacimiento.
- La app descarga esta lista al vincular y cada 5 minutos.

### 4.5 `POST /api/pistola/lecturas`
Petición, hasta 200 lecturas por envío:
```json
{
  "dispositivoId": "6f1c…",
  "dispositivo": "Galaxy A15 meta",
  "operador": "Juan",
  "app": "NexCrono Pistola 1.4.0",
  "enviado": "2026-11-22T08:15:02.120-05:00",
  "lecturas": [
    {
      "id": "0d6c2a0e-3b7d-4a51-9a51-1d2b8f0c9e11",
      "evento": "42",
      "dorsal": "1234",
      "numero": "1234",
      "chip": "1234",
      "epc": "300833B2DDD9014000001234",
      "punto": "PC2",
      "modo": "CONTROL",
      "horaMs": 1763385301123,
      "hora": "2026-11-22T08:15:01.123-05:00",
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
Validar:
- `id` es un UUID.
- `evento` existe.
- `modo` ∈ {SALIDA, CONTROL}.
- `origen` ∈ {vivo, manual, barras, memoria}.
- `horaMs` es un entero razonable: dentro de ±2 días del evento.

Sobre los campos `chip` y `numero`:
- `chip` llega **ya convertido** con la configuración «Número de chip» del teléfono. Por ejemplo, en TimingSense `00D601BE00000000` → `446`.
- `numero` es el dorsal si el teléfono lo conoce y, si no, el número de chip.
- Textos de 64 caracteres como máximo.
- Como máximo **500 lecturas** y **2 MB** por petición.

Guardar en **una transacción** con `INSERT … ON CONFLICT(id) DO NOTHING`, o el equivalente. **Si un `id` ya existe, ignorarlo sin error.**

Responder `200` **solo después de guardar**:
```json
{ "recibidas": 1, "nuevas": 1, "duplicadas": 0 }
```
Mientras no reciba un 2xx, la app reintenta cada 3 segundos.

---

## 5. Integración con el cronometraje

- **Identificación del atleta:** usar la relación chip/EPC ↔ dorsal **de NexCrono**, que manda sobre el `dorsal` que envía la app. Si no hay relación, usar el `dorsal` de la app. Si no hay ninguno, guardar la pasada como «chip no inscrito» para revisarla.
- **Salida controlada (`modo = SALIDA`):** la hora de salida del atleta es `horaMs`, porque la app ya aplicó «última lectura». Si llegan varias pasadas de SALIDA del mismo atleta, tomar la **última**, igual que la regla actual de NexCrono para la salida. Esta hora de salida individual debe poder reemplazar la salida por oleada o categoría cuando el evento sea de salida controlada (opción por evento).
- **Puntos de control (`modo = CONTROL`):** registrar el paso por `punto` con `horaMs`. Si llegan varias pasadas del mismo atleta en el mismo punto, usar la **primera**, con la misma tolerancia que NexCrono usa para las vueltas. Estos pasos alimentan el **seguimiento en vivo por puntos de control** (versión 7.5) y los reportes de parciales.
- `primeraMs` y `ultimaMs` están disponibles si alguna regla necesita la otra hora.
- `origen = manual` o `barras`: marcar el tiempo como *tomado a mano* en los reportes.
- `origen = memoria`: lecturas hechas con la pistola **sin conexión** y descargadas después desde su micro SD. La hora viene del reloj de la pistola (la app lo sincroniza en cada conexión) con precisión de **1 segundo**. Pueden llegar horas después de ocurridas: se integran igual, por hora y no por orden de llegada.
- `horaSincronizada = false`: avisar en el panel, porque ese teléfono no logró sincronizar la hora.

---

## 6. Seguridad (obligatorio)

1. **Tokens por dispositivo**, nunca un token global. Guardar solo `SHA-256(token)` y comparar con una comparación de tiempo constante.
2. **Revocación inmediata** desde el panel.
3. **Códigos de vinculación** de un solo uso, que vencen a los 10 minutos, guardados con hash y con límite de intentos por IP.
4. **Límites**: 2 MB y 500 lecturas por petición en `/lecturas`; 120 peticiones por minuto por dispositivo; 5 por minuto por IP en `/vincular`.
5. **SQL siempre parametrizado.** Validar tipos y largos de todos los campos.
6. **Errores sin detalles internos.** Nada de trazas ni rutas en la respuesta.
7. **Túnel**: por el subdominio público exponer **solo** `/api/pistola/*` y lo que ya se publica a propósito. El panel de dispositivos, la generación de QR y el resto de la administración quedan **solo en la red local** o detrás del login actual de NexCrono.
8. **Sin CORS.** La app no es un navegador, así que no hace falta.
9. **HTTPS** cuando se use el túnel, ya cubierto por Cloudflare. En la red local se acepta `http://`.
10. **Registro (log)** de vinculaciones, revocaciones y errores 401/429 con IP y hora.
11. **Privacidad**: `/inscritos` solo entrega dorsal, chip, EPC, nombre, categoría, género y club.

---

## 7. Pruebas sugeridas (curl)

```bash
# 1. Vincular (con el código mostrado en el panel)
curl -X POST http://localhost:8080/api/pistola/vincular -H "Content-Type: application/json" \
  -d '{"codigo":"K7P2-9QX4","dispositivoId":"11111111-1111-1111-1111-111111111111","dispositivo":"Prueba","app":"NexCrono Pistola","version":"1.4.0"}'

# 2. Hora
curl http://localhost:8080/api/pistola/hora -H "Authorization: Bearer TOKEN"

# 3. Inscritos
curl "http://localhost:8080/api/pistola/inscritos?evento=42" -H "Authorization: Bearer TOKEN"

# 4. Enviar la misma lectura dos veces → la segunda debe dar duplicadas: 1
curl -X POST http://localhost:8080/api/pistola/lecturas -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" \
  -d '{"dispositivoId":"11111111-1111-1111-1111-111111111111","lecturas":[{"id":"22222222-2222-2222-2222-222222222222","evento":"42","dorsal":"1234","chip":"1234","epc":"300833B2DDD9014000001234","punto":"PC1","modo":"CONTROL","horaMs":1763385301123,"primeraMs":1763385301123,"ultimaMs":1763385301123,"rssi":-56,"lecturas":3,"origen":"vivo","horaSincronizada":true}]}'

# 5. Token revocado → 401
```

Criterios de aceptación:
- El QR vinculado desde la app muestra la empresa y el evento.
- La pestaña *Control* de la app muestra los inscritos y quién falta.
- Las pasadas aparecen en la vista en vivo en menos de 10 segundos.
- Al revocar un teléfono, este deja de poder enviar lecturas.
- Una lectura repetida no se duplica.
