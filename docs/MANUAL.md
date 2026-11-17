# NexCrono Pistola · Manual

App Android de **CodevNexus** para capturar los chips de los competidores con pistolas RFID UHF en **salidas controladas** y **puntos de control**. Guarda cada pasada con su hora exacta, la muestra con el dorsal y el nombre del atleta, y la envía a **NexCrono** o la comparte en Excel o CSV.

> Toca el botón **i** de cualquier pantalla para abrir este manual en la parte que corresponde.

## Qué necesitas

- Un teléfono Android 8.0 o superior, con Bluetooth.
- Una pistola RFID UHF. La recomendada es la **TSL 1128**; también sirven la 1153, 1166 y 2128, y las pistolas genéricas que envían el EPC como texto.
- Para usar la pistola sin el teléfono y descargar después su memoria, una **tarjeta micro SD** en la pistola.
- Opcional: NexCrono en una computadora de la misma red (WiFi o hotspot) o por el túnel de internet.
- Una **licencia** de CodevNexus. Sin licencia la app funciona en modo demostración.

## Primeros pasos

1. Instala el APK y abre la app.
2. Acepta los permisos de **Dispositivos cercanos** (Bluetooth) y **Notificaciones**.
3. En **Lectura** toca **Permitir** en el aviso de batería. Sin esto, algunos teléfonos cortan la app cuando la pantalla se bloquea.
4. En **Pistola**, busca la pistola, toca **Emparejar** (PIN `0000` si lo pide) y luego tócala para conectar.
5. En **Ajustes → Número de chip**, lee un chip, escribe el número impreso y toca **Detectar**. Luego toca **GUARDAR AJUSTES**.
6. En **Eventos**, crea el evento o tráelo de NexCrono.
7. En **Control**, carga los participantes (Excel o CSV) si el evento es local.
8. Elige **Salida controlada** o **Punto de control** en Ajustes.
9. Lee con el gatillo o con **INICIAR LECTURA**.

## 1. Lectura

La pantalla principal mientras se captura.

- **Encabezado:** empresa y evento, el punto (SALIDA en naranja o el nombre del punto de control en turquesa), el estado de la pistola, su batería y el estado de NexCrono.
- **Último chip:** el dorsal del último atleta capturado, en grande, con su nombre, categoría y hora (con milisegundos). Si no se conoce el dorsal, se muestra el número de chip.
- **Colores al capturar:** destello **turquesa** si el chip está inscrito, **rojo** si no está en la lista de participantes y **naranja** si el registro es manual o por código de barras. Cada caso tiene un sonido y una vibración distintos.
- **Contadores:** detectados sobre el total, cuántos faltan y cuántas pasadas faltan por enviar a NexCrono. Si el evento no tiene participantes, muestra chips y pasadas.
- **INICIAR LECTURA:** la pistola lee sin parar hasta que tocas **DETENER**. También puedes leer con el gatillo: una pulsación hace una lectura y si lo mantienes presionado lee sin parar.
- **Dorsal manual:** para un chip que no lee. Escribe el dorsal y queda registrado con la hora actual, marcado como `manual`.
- **Cerrar salida** (solo en modo Salida): cierra todas las pasadas abiertas y fija la última lectura de cada atleta. Úsalo al dar la salida.
- **Pasadas recientes:** cada pasada con su hora, lecturas, señal y estado:
  - `EN CURSO`: el chip todavía se está leyendo.
  - `PENDIENTE`: está cerrada pero aún no se envió.
  - `ENVIADO`: NexCrono ya la recibió.

### Qué es una pasada

Mientras un chip se sigue leyendo, todas las lecturas cuentan como **una sola pasada**. Cuando el chip deja de leerse durante la **ventana** (5 segundos por defecto, se cambia en Ajustes), la pasada se cierra y queda lista para enviar.

### Qué hora se registra

- **Salida controlada:** la **última** lectura, es decir, el momento en que el atleta sale del alcance de la pistola.
- **Punto de control:** la **primera** lectura.
- Se puede cambiar en **Ajustes → Pasadas y hora registrada**: primera lectura, última lectura o mejor señal (cuando el chip estuvo más cerca).
- NexCrono recibe siempre también la primera y la última hora.

### Segundo plano

Mientras la pistola está conectada, la app sigue leyendo y enviando **aunque la pantalla esté bloqueada** o uses otra app, y muestra una notificación fija. Si se corta el Bluetooth, reconecta sola cada 5 segundos. Si Android cierra la app, la vuelve a abrir y reconecta con la última pistola.

## 2. Control (detectados y no detectados)

Muestra cómo va el punto respecto a la lista de participantes. Funciona igual en eventos **locales** y en eventos de **NexCrono**.

- **Indicadores:** total de participantes, detectados, no detectados y porcentaje. Si se leyeron chips que no están en la lista, aparece cuántos.
- **No detectados:** quiénes **todavía no pasaron**. En la salida controlada son los que **no se leyeron en el corral**, posibles ausentes o chips dañados. Toca un participante para registrarlo a mano.
- **Detectados:** quiénes ya pasaron, con la hora de su primera pasada y el origen si no fue lectura en vivo.
- **No inscritos:** chips leídos que no están en la lista, con su número de chip, EPC y hora.
- **Buscar:** filtra por dorsal, nombre o categoría.
- **De NexCrono** (solo en eventos de NexCrono): vuelve a descargar los inscritos. También se actualizan solos cada 5 minutos.

### Cargar participantes (Excel o CSV)

Toca **Cargar Excel / CSV** y elige el archivo. La app reconoce las columnas por su **encabezado**, en cualquier orden:

- **Dorsal** (obligatoria): también acepta *Bib*, *Número*, *Nro*.
- **Nombre** / **Nombres** / **Nombre completo**, y **Apellidos** (se juntan).
- **Categoría**.
- **Chip**: el número de chip, tal como lo muestra la app.
- **EPC**: el código completo del chip. Es la forma más segura de identificar al atleta.
- **Género** / **Sexo**, y **Club** / **Equipo**.

Notas sobre el archivo:
- El encabezado puede estar en cualquiera de las 10 primeras filas, así que se pueden dejar títulos arriba.
- En Excel se lee la **primera hoja**. El CSV puede usar coma, punto y coma o tabulador.
- Si el evento ya tiene participantes, puedes **Reemplazar** la lista o **Agregar** (suma y actualiza por dorsal).

Ejemplo de CSV:

```
Dorsal;Nombres;Apellidos;Categoría;Chip;Sexo;Club
446;Ana;Pérez;Elite F;446;F;Runners UIO
730;Luis;Mora;Master M;730;M;
```

> Para reconocer al atleta, la app busca primero por **EPC** y después por **número de chip**. Si el archivo no trae ninguno de los dos, el atleta solo se puede registrar con **Dorsal manual** o por código de barras.

## 3. Eventos

Todos los eventos quedan guardados en el teléfono (base de datos SQLite), con sus pasadas y participantes. Así puedes ver el **historial** aunque no haya internet.

- **+ Nuevo evento:** nombre, fecha, lugar y notas. **Crear y abrir** lo deja como evento actual; **Solo crear** lo guarda para después.
- **De NexCrono** (con el teléfono vinculado): lista los eventos de NexCrono. Al elegir uno, se abre y se descargan sus inscritos.
- Cada evento muestra pasadas, chips, participantes, pasadas por enviar y la hora de la última lectura.
- **Abrir:** lo deja como evento actual. Lo que se lea desde ese momento queda en ese evento.
- **Editar:** cambia el nombre, la fecha, el lugar o las notas.
- **Compartir:** crea el archivo y lo envía por WhatsApp, correo, Drive, etc.:
  - **Pasadas** (Excel o CSV): cada pasada con el número, dorsal, nombre, **número de chip ya convertido**, EPC, horas, señal, punto, origen y si se envió.
  - **Participantes y estado** (Excel o CSV): cada participante como `DETECTADO` (con su hora) o `NO DETECTADO`, más los chips `NO INSCRITO`.
- **Eliminar:** borra el evento con sus pasadas y participantes. Exporta antes si lo necesitas: lo que no se envió se pierde.

Los archivos también quedan en **Descargas/NexCrono**.

### Compartir por WiFi

Para pasar los archivos a una computadora o a otro teléfono **sin cables y sin internet**:

1. Conecta el teléfono y la computadora al **mismo WiFi**, o conecta la computadora al **hotspot del teléfono**.
2. En **Eventos**, activa **Compartir por WiFi**.
3. En el navegador de la computadora abre la **dirección** que aparece, por ejemplo `http://192.168.1.25:8080`.
4. Escribe el **PIN** de 6 dígitos que muestra la app.
5. Descarga las pasadas o los participantes de cualquier evento, en Excel o CSV.

Seguridad del servidor:
- El PIN cambia cada vez que se enciende.
- Después de 5 PIN incorrectos, ese equipo queda bloqueado 5 minutos.
- Desde el navegador no se puede cambiar ni borrar nada.
- **Apágalo al terminar.**

## 4. Pistola

- **Tipo de pistola:**
  - **TSL (ASCII 2):** TSL 1128, 1153, 1166 y 2128, con todas las funciones.
  - **Genérica:** pistolas que envían el EPC como texto. Solo reciben datos.
  - Zebra y Chainway aparecen en la lista pero todavía no se pueden usar.
- **Emparejar:** **Buscar pistolas** → **Emparejar**. También se puede hacer desde los ajustes de Bluetooth del teléfono.
- **Conectar:** toca la pistola en la lista. La última que usaste aparece marcada.
- **Información:** batería y estado de carga, fabricante, número de serie, firmware y el reloj de la pistola (cuánto estaba desfasado y si se ajustó).
- **Potencia:** de 10 a 29 dBm. Con menos potencia la pistola lee a menos distancia, lo que sirve para no leer chips de corrales vecinos.

### Configuración de la pistola

Al conectar, la app deja la pistola configurada:
- RSSI (señal) y fecha/hora en cada lectura.
- Sesión S0, para que la «última lectura» de la salida sea precisa.
- Potencia y pitido.
- **Gatillo:** mantener presionado = leer sin parar.
- Ajusta el **reloj de la pistola** a la hora de NexCrono.

**Grabar configuración en la pistola (AUTO.TXT):** la pistola **olvida su configuración cada vez que se duerme**, después de un minuto sin conexión. Este botón la guarda en la micro SD para que se aplique siempre, aunque el teléfono no esté. Es **obligatorio** si vas a leer con la pistola sola. Repítelo si cambias la potencia o el gatillo.

### Memoria / micro SD

Lo que se lee con el gatillo **sin el teléfono** queda guardado en la tarjeta micro SD de la pistola.

1. Vuelve a conectar la pistola.
2. Abre en la app el evento al que pertenecen esas lecturas.
3. Toca **Leer memoria / SD**.

La app importa solo lo leído **sin conexión** (lo leído con el teléfono conectado ya está registrado) y lo agrupa en pasadas con la misma ventana. Las pasadas importadas quedan con origen `memoria` y tienen precisión de **1 segundo**. Si se importa dos veces, no se duplica nada. Si aparecen «lecturas sin hora», la pistola no tenía el AUTO.TXT grabado.

**Borrar memoria de la pistola** limpia el registro de la SD. Hazlo solo después de importar y enviar.

### Grabar número en chips

Sirve para programar chips nuevos o cambiar el número de uno.

1. Deja **solo el chip a grabar** junto a la antena. Los demás, lejos o dentro de una caja metálica.
2. Escribe el **número**. Con TimingSense, pon el prefijo **00D6** en los chips nuevos.
3. Toca **1. Leer chip**.
4. Revisa **Se grabará** y toca **2. Grabar**.

La app escribe el número **en el mismo formato con que lo lee** (Ajustes → Número de chip). Antes de grabar comprueba cómo se leerá el chip, y después de grabar lo verifica. Con **Pasar al número siguiente** queda lista para el próximo chip. Los chips bloqueados o con contraseña no se pueden grabar.

### Consola de comandos y comandos al conectar

Son para usuarios avanzados: permiten enviar comandos del protocolo TSL ASCII 2.5. Los **comandos al conectar** se envían cada vez que la pistola se conecta. Las líneas que empiezan con `#` se ignoran.

## 5. Ajustes

### Conexión con NexCrono

- **Vincular:** en NexCrono, genera el **QR de vinculación** del punto y en la app toca **ESCANEAR QR DE NEXCRONO**. También funciona escaneándolo con la cámara normal del teléfono o con el lector 2D de la pistola, o escribiendo a mano la dirección y el código.
- **La dirección se puede dejar para después.** Mientras no haya vinculación, todo se guarda en el teléfono y se envía cuando vincules.
- Cada teléfono recibe su propio **token**, que NexCrono puede revocar.
- **Enviar automáticamente:** envía cada pasada al cerrarse. Si no hay red, las guarda y reintenta cada 3 segundos.
- **Sincronizar hora:** calcula la diferencia de reloj con la computadora de NexCrono y corrige todas las horas. Esa diferencia queda guardada 12 horas, así que sirve aunque se reinicie la app sin red.
- **Enviar ahora** fuerza un envío. **Desvincular** deja de enviar sin borrar nada.

### Número de chip

Define cómo se convierte el EPC del chip en su número:

- **Detectar con un chip (recomendado):** lees un chip, escribes el número impreso y la app encuentra sola el formato.
- **Segmento del EPC:** un tramo del EPC (desde una posición, con una cantidad de caracteres), en hexadecimal o en decimal. Con **Usar formato TimingSense**: `00D6 01BE 0000…` → **446**.
- **Últimos dígitos del EPC:** `…000001234` → 1234.
- **Hexadecimal → decimal:** `…04D2` → 1234.
- **Tabla EPC → chip:** importada desde un CSV con dos columnas: EPC y chip.
- **EPC completo.**

Al guardar un cambio, las pasadas del evento actual se recalculan. Los archivos y lo que se envía a NexCrono llevan **siempre el número ya convertido**.

### Punto de captura

**Salida controlada** o **Punto de control** (con su nombre, por ejemplo PC1 o KM10), el nombre del equipo y el operador.

### Pasadas y hora registrada

La ventana en segundos, la regla de hora, el pitido y la vibración.

### Datos y respaldo

Exportar el evento actual en CSV o Excel, marcarlo para reenviar a NexCrono o borrar sus pasadas.

### Historial (bitácora)

Registro guardado en el teléfono de:
- envíos a NexCrono y sus errores;
- descargas de inscritos y sincronizaciones de hora;
- vinculaciones;
- cambios de licencia;
- eventos creados, abiertos o borrados;
- accesos y descargas por WiFi.

Cada pasada guarda además su propio estado de envío: cuándo se envió, cuántos intentos fallaron y el último error.

### Versión y actualizaciones

Si la app se compiló con el servidor de actualizaciones, al abrirla avisa cuando hay una **versión nueva**. La descarga, verifica su firma y la instala sin perder datos.

## 6. Licencia

La licencia es un archivo **.lic** firmado por CodevNexus, **vinculado a un teléfono** y con **fecha de vencimiento** (o perpetua).

Para el usuario:
1. En **Ajustes → Licencia**, copia o envía el **código de este teléfono** (por ejemplo `3EA0-89A2-E98E-AC50`) a CodevNexus.
2. Recibirás un archivo `.lic`. Guárdalo en el teléfono.
3. Toca **Cargar licencia (.lic)** y elígelo.

Estados posibles:
- **Licencia válida:** muestra el cliente y los días que quedan.
- **Vencida**, **de otro teléfono** o **no válida** (archivo modificado): se trabaja en modo demostración.
- **Modo demostración:** hasta **25 pasadas por evento**. Todo lo demás funciona.
- Si se **atrasa la fecha del teléfono** para estirar la licencia, la app lo detecta y pide corregirla.

> La licencia solo sirve en el teléfono para el que se emitió. Si cambias de teléfono, o lo restableces de fábrica, el código cambia y hace falta una licencia nueva.

### Emitir licencias (CodevNexus)

Se usa la herramienta `herramientas/GeneradorLicencias.java`, que funciona con el Java de Android Studio.

Una sola vez, crear las llaves:

```
java GeneradorLicencias.java llaves
```

- Crea `codevnexus-licencias.key`, la **clave privada**. **Guárdala en dos lugares seguros y nunca la subas al repositorio.** Si se pierde, no se pueden emitir más licencias para esta app.
- Muestra la **clave pública**. Ponla en GitHub como secreto `NEXCRONO_LICENSE_KEY` y vuelve a compilar.

Emitir una licencia:

```
java GeneradorLicencias.java emitir --telefono 3EA0-89A2-E98E-AC50 --cliente "FastTime Ecuador" --vence 2027-12-31 --plan Pro
```

- `--vence` acepta una fecha, `perpetua` o `+365` (días desde hoy).
- Crea el archivo `.lic` para enviar al cliente.
- `java GeneradorLicencias.java ver archivo.lic` muestra el contenido de una licencia.

> Una compilación **sin** clave pública no controla la licencia. Es solo para desarrollo.

## 7. Integración con NexCrono

NexCrono tiene que implementar estas rutas de la API (detalle completo en `docs/INSTRUCCIONES_NEXCRONO.md`):
- `POST /api/pistola/vincular`
- `GET /api/pistola/hora`
- `GET /api/pistola/eventos`
- `GET /api/pistola/inscritos?evento=ID`
- `POST /api/pistola/lecturas`

Cada lectura que envía la app lleva:
- `id` único (NexCrono ignora los repetidos);
- evento, dorsal y `numero`;
- `chip` **ya convertido** y `epc`;
- punto y modo;
- `horaMs`, `primeraMs` y `ultimaMs`;
- RSSI, cantidad de lecturas y origen (`vivo`, `manual`, `barras` o `memoria`);
- si la hora estaba sincronizada.

## Solución de problemas

- **No conecta la pistola:** que esté encendida y no conectada a otro equipo. Revisa que esté emparejada. Apaga y prende el Bluetooth.
- **Se desconecta con la pantalla bloqueada:** en Lectura toca **Permitir**, para quitar la restricción de batería.
- **El número no coincide con el impreso:** Ajustes → Número de chip → **Detectar con un chip**.
- **Lee chips de otros corrales:** baja la **potencia**.
- **La memoria no trae hora:** graba el **AUTO.TXT** (Pistola → Configuración de la pistola).
- **NexCrono sin enlace:** revisa que estén en la misma red y la dirección, y mira el **Historial** en Ajustes. Las pasadas no se pierden: se envían cuando vuelve la conexión.
- **El navegador no abre la dirección del WiFi:** que estén en la misma red y que el servidor siga encendido. Algunas redes de hoteles o eventos aíslan a los equipos entre sí; en ese caso usa el hotspot del teléfono.
- **Modo demostración:** carga la licencia en Ajustes → Licencia.
- **Firmware de la pistola:** se recomienda el 5.1.2, que se instala desde Windows con *TSL PC Firmware Downloader*.

## Glosario

- **EPC:** código único grabado en el chip, en hexadecimal.
- **Número de chip:** el número que se obtiene del EPC según la configuración (en TimingSense, el impreso).
- **Dorsal:** número del atleta en la lista de participantes.
- **Pasada:** el paso de un chip por el punto, desde la primera hasta la última lectura.
- **Ventana:** segundos sin leer un chip para dar su pasada por cerrada.
- **RSSI:** intensidad de la señal en dBm. Cuanto más cerca de 0, más cerca está el chip.
- **AUTO.TXT / LOG.TXT:** archivos de configuración y de registro en la micro SD de la pistola.
