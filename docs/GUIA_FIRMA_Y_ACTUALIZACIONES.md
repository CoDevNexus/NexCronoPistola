# Firma del APK y actualizaciones automáticas

Son tres partes, y se hacen **una sola vez**:
1. Crear la llave de firma de CodevNexus.
2. Cargarla en GitHub para que el APK salga firmado.
3. Subir el servidor de actualizaciones a tu hosting y conectar la URL.

> Mientras no hagas estos pasos, GitHub sigue compilando el APK de prueba (debug) y la app funciona igual, solo que sin actualizaciones automáticas.

---

## 1. Crear la llave de firma (en tu PC con Windows)

La llave identifica a CodevNexus como autor de la app. Android **solo acepta una actualización si está firmada con la misma llave**, así que nadie puede colar un APK falso.

Abre **CMD** en una carpeta segura, **fuera del repositorio**, por ejemplo `D:\Llaves`, y ejecuta (ajusta la ruta si Android Studio está en otro lugar):

```bat
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore codevnexus.jks -keyalg RSA -keysize 4096 -validity 10000 -alias codevnexus
```

- Pide una contraseña: usa una larga y **guárdala en un gestor de contraseñas**. Si te pide una segunda contraseña para la llave, pon **la misma**.
- Completa los datos de la organización: CodevNexus, Ecuador, etc.

⚠️ **Guarda `codevnexus.jks` y su contraseña en 2 lugares seguros** (por ejemplo, un USB y una nube privada). Si los pierdes, **no podrás actualizar** la app en los teléfonos: habría que desinstalarla en todos. Nunca la subas al repositorio; el `.gitignore` ya bloquea los archivos `.jks`.

Convierte la llave a texto para GitHub (en **PowerShell**, en la misma carpeta):
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("codevnexus.jks")) | Set-Content codevnexus.b64
```

---

## 2. Cargar la llave en GitHub

En el repositorio `CoDevNexus/NexCronoPistola` → **Settings → Secrets and variables → Actions → New repository secret**. Crea estos secretos:

| Nombre | Valor |
|---|---|
| `KEYSTORE_BASE64` | Todo el contenido de `codevnexus.b64` |
| `KEYSTORE_PASSWORD` | La contraseña de la llave |
| `KEY_ALIAS` | `codevnexus` |
| `KEY_PASSWORD` | La misma contraseña |
| `NEXCRONO_UPDATE_URL` | *(déjalo para después, paso 3)* |
| `NEXCRONO_LICENSE_KEY` | La clave pública de licencias (ver *Manual → Licencia → Emitir licencias*). Sin ella, la app no controla la licencia |

Desde la siguiente compilación, en **Actions** el artefacto trae `app-release.apk` (firmado) y `apk.sha256`.

> **Solo la primera vez:** el APK de prueba (debug) y el firmado (release) tienen firmas distintas, así que hay que **desinstalar el de prueba** antes de instalar el firmado. Exporta antes tus eventos (*Eventos → Compartir*) si tienes lecturas que quieras guardar. Una licencia ya cargada se vuelve a cargar, porque el código del teléfono no cambia. De ahí en adelante todas las actualizaciones se instalan encima, sin perder datos.

---

## 3. Servidor de actualizaciones en tu hosting

La carpeta `servidor-actualizaciones/` es un panel PHP (PHP 7.4 o superior) para publicar versiones.

### 3.1 Preparar
1. Crea un subdominio con SSL, por ejemplo `updates.codevnexus.tech`.
2. Abre `servidor-actualizaciones/config.php` y cambia:
   - `CLAVE_INSTALACION`: una frase larga y única (mínimo 16 caracteres). Solo sirve para crear el administrador la primera vez.
   - `URL_BASE`: `https://updates.codevnexus.tech`. También puede quedar vacío para que se detecte sola.
   - Si el hosting lo permite, mueve la carpeta `datos/` **fuera de `public_html`** y pon su ruta absoluta en `DIR_DATOS`.

### 3.2 Subir
Sube **el contenido** de `servidor-actualizaciones/` a la carpeta del subdominio (por FTP o el administrador de archivos del cPanel), incluidos los archivos `.htaccess`.

Si tus APK pesan más de 8 MB, sube en el cPanel (*Select PHP Version → Options*) los límites `upload_max_filesize` y `post_max_size`, por ejemplo a `150M`.

### 3.3 Crear el administrador
Entra a `https://updates.codevnexus.tech/`. Pon la **clave de instalación**, un usuario y una contraseña (mínimo 10 caracteres).

### 3.4 Conectar la app con el servidor
1. En GitHub, crea el secreto `NEXCRONO_UPDATE_URL` = `https://updates.codevnexus.tech`.
2. En **Actions → Compilar APK → Run workflow** compila de nuevo.
3. **Instala ese APK a mano** en los teléfonos. Es la primera versión que sabe dónde buscar actualizaciones.

### 3.5 Publicar una actualización (cada vez)
1. En `app/build.gradle` sube `versionCode`, por ejemplo de 6 a 7, y `versionName`, por ejemplo de `"1.4.0"` a `"1.4.1"`. Haz commit y push.
2. Descarga de **Actions** el `app-release.apk`.
3. En el panel → **Publicar versión**: elige la app y pon el mismo `versionCode` y `versionName`, las notas y el archivo.
4. Al abrir la app, los teléfonos ven «Nueva versión» → **Actualizar**:
   - La app descarga el APK.
   - Verifica el SHA-256.
   - Android confirma la firma e instala.

Con **«Obligatoria»** el teléfono no puede posponer la actualización. Si una versión sale con problemas, **Retirar** la deja de ofrecer y vuelve a ofrecerse la anterior activa, pero solo a los teléfonos que tengan un `versionCode` menor.

### Protecciones incluidas
- Contraseña con `password_hash` y bloqueo de **5 intentos fallidos por IP durante 15 minutos**.
- La sesión vence a los 30 minutos; la cookie es `HttpOnly`, `Secure` y `SameSite=Strict`.
- **CSRF** en todos los formularios y cabeceras de seguridad (CSP, X-Frame-Options, nosniff, HSTS).
- HTTPS obligatorio.
- Validación del APK: extensión, firma ZIP, `AndroidManifest.xml` y tamaño máximo.
- Los nombres de archivo los genera el servidor, sin rutas del usuario.
- La carpeta `datos/` está bloqueada desde la web, igual que `config.php` y `lib.php`.
- Registro de logins, publicaciones y descargas en `datos/registro.log`.
- El teléfono verifica el **SHA-256** publicado y Android exige **la misma firma**: aunque alguien entrara al hosting, no podría instalar un APK que no esté firmado con tu llave.
