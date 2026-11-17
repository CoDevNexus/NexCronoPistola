<?php
// Panel de administración: publicar versiones de las apps de NexCrono.
define('NXC', 1);
require __DIR__ . '/lib.php';
exigir_https();
iniciar_sesion();
header('Cache-Control: no-store');

$aviso = '';
$error = '';
$admin = admin();
$accion = $_POST['accion'] ?? '';

// ───────── Primera instalación: crear administrador ─────────
if (empty($admin)) {
    if ($accion === 'instalar') {
        verificar_csrf();
        if (CLAVE_INSTALACION === 'CAMBIA-ESTA-CLAVE-POR-UNA-LARGA-Y-UNICA' || strlen(CLAVE_INSTALACION) < 16) {
            $error = 'Primero cambia CLAVE_INSTALACION en config.php por una frase de al menos 16 caracteres.';
        } elseif (intentos_bloqueado()) {
            $error = 'Demasiados intentos. Espera 15 minutos.';
        } elseif (!hash_equals(CLAVE_INSTALACION, (string)($_POST['clave_instalacion'] ?? ''))) {
            intento_fallido();
            registrar('instalación: clave incorrecta');
            $error = 'Clave de instalación incorrecta.';
        } else {
            $u = trim((string)($_POST['usuario'] ?? ''));
            $p = (string)($_POST['clave'] ?? '');
            if (!preg_match('/^[A-Za-z0-9_.-]{3,30}$/', $u)) $error = 'Usuario: 3 a 30 letras, números, punto, guion o guion bajo.';
            elseif (strlen($p) < 10) $error = 'La contraseña debe tener al menos 10 caracteres.';
            elseif ($p !== ($_POST['clave2'] ?? '')) $error = 'Las contraseñas no coinciden.';
            else {
                escribir_json('admin.json', ['usuario' => $u, 'hash' => password_hash($p, PASSWORD_DEFAULT), 'creado' => date('c')]);
                limpiar_intentos();
                registrar("administrador creado: $u");
                $admin = admin();
                $aviso = 'Administrador creado. Ya puedes iniciar sesión.';
            }
        }
    }
}

// ───────── Login / logout ─────────
if (!empty($admin) && $accion === 'login') {
    verificar_csrf();
    if (intentos_bloqueado()) {
        $error = 'Demasiados intentos fallidos. Espera 15 minutos.';
    } else {
        $u = (string)($_POST['usuario'] ?? '');
        $p = (string)($_POST['clave'] ?? '');
        if (hash_equals($admin['usuario'], $u) && password_verify($p, $admin['hash'])) {
            session_regenerate_id(true);
            $_SESSION['usuario'] = $u;
            $_SESSION['ultimo'] = time();
            $_SESSION['csrf'] = bin2hex(random_bytes(32));
            limpiar_intentos();
            if (password_needs_rehash($admin['hash'], PASSWORD_DEFAULT)) {
                $admin['hash'] = password_hash($p, PASSWORD_DEFAULT);
                escribir_json('admin.json', $admin);
            }
            registrar("login $u");
        } else {
            intento_fallido();
            registrar('login fallido');
            sleep(1);
            $error = 'Usuario o contraseña incorrectos.';
        }
    }
}
if ($accion === 'salir' && logueado()) {
    verificar_csrf();
    registrar('logout ' . $_SESSION['usuario']);
    $_SESSION = [];
    session_destroy();
    header('Location: ' . url_base() . '/');
    exit;
}

// ───────── Acciones con sesión ─────────
if (logueado() && in_array($accion, ['subir', 'activar', 'eliminar', 'clave'], true)) {
    verificar_csrf();
    $vers = versiones();

    if ($accion === 'subir') {
        $app = (string)($_POST['app'] ?? '');
        $code = filter_var($_POST['versionCode'] ?? '', FILTER_VALIDATE_INT, ['options' => ['min_range' => 1, 'max_range' => 2100000000]]);
        $nombre = trim((string)($_POST['versionName'] ?? ''));
        $notas = trim((string)($_POST['notas'] ?? ''));
        $f = $_FILES['apk'] ?? null;
        if (!array_key_exists($app, APPS)) $error = 'App no válida.';
        elseif ($code === false) $error = 'versionCode debe ser un número entero positivo (el mismo de app/build.gradle).';
        elseif (!preg_match('/^[0-9A-Za-z.\-]{1,20}$/', $nombre)) $error = 'versionName no válido (ej. 1.1.0).';
        elseif (mb_strlen($notas) > 2000) $error = 'Notas demasiado largas.';
        elseif (!$f || ($f['error'] ?? 1) !== UPLOAD_ERR_OK || !is_uploaded_file($f['tmp_name'])) $error = 'No llegó el archivo (revisa el tamaño máximo del hosting).';
        elseif ($f['size'] > MAX_MB * 1024 * 1024) $error = 'El archivo supera ' . MAX_MB . ' MB.';
        elseif (strtolower(pathinfo($f['name'], PATHINFO_EXTENSION)) !== 'apk') $error = 'Debe ser un archivo .apk';
        else {
            $cabecera = file_get_contents($f['tmp_name'], false, null, 0, 4);
            $zip = class_exists('ZipArchive') ? new ZipArchive() : null;
            if ($cabecera !== "PK\x03\x04") $error = 'El archivo no es un APK válido.';
            elseif ($zip && ($zip->open($f['tmp_name']) !== true || $zip->locateName('AndroidManifest.xml') === false)) $error = 'El APK no contiene AndroidManifest.xml.';
            else {
                if ($zip) $zip->close();
                foreach ($vers as $v) {
                    if ($v['app'] === $app && (int)$v['versionCode'] === $code) { $error = "Ya existe la versión $code de esta app."; break; }
                }
                if ($error === '') {
                    $archivo = $app . '-' . $code . '-' . bin2hex(random_bytes(6)) . '.apk';
                    $destino = ruta_datos('apks/' . $archivo);
                    if (!move_uploaded_file($f['tmp_name'], $destino)) {
                        $error = 'No se pudo guardar el archivo.';
                    } else {
                        chmod($destino, 0640);
                        $vers[] = [
                            'app' => $app, 'versionCode' => $code, 'versionName' => $nombre, 'notas' => $notas,
                            'obligatoria' => !empty($_POST['obligatoria']), 'activa' => true,
                            'archivo' => $archivo, 'sha256' => hash_file('sha256', $destino), 'tamano' => filesize($destino),
                            'fecha' => date('c'), 'por' => $_SESSION['usuario'],
                        ];
                        guardar_versiones($vers);
                        registrar("publicada $app v$code ($nombre)");
                        $aviso = "Versión $nombre ($code) publicada.";
                    }
                }
            }
        }
    }

    if ($accion === 'activar' || $accion === 'eliminar') {
        $app = (string)($_POST['app'] ?? '');
        $code = (int)($_POST['versionCode'] ?? 0);
        foreach ($vers as $i => $v) {
            if ($v['app'] === $app && (int)$v['versionCode'] === $code) {
                if ($accion === 'activar') {
                    $vers[$i]['activa'] = empty($v['activa']);
                    registrar(($vers[$i]['activa'] ? 'activada' : 'retirada') . " $app v$code");
                    $aviso = $vers[$i]['activa'] ? 'Versión activada.' : 'Versión retirada (ya no se ofrece).';
                } else {
                    @unlink(ruta_datos('apks/' . basename($v['archivo'])));
                    unset($vers[$i]);
                    registrar("eliminada $app v$code");
                    $aviso = 'Versión eliminada.';
                }
            }
        }
        guardar_versiones(array_values($vers));
    }

    if ($accion === 'clave') {
        $actual = (string)($_POST['actual'] ?? '');
        $nueva = (string)($_POST['nueva'] ?? '');
        if (!password_verify($actual, $admin['hash'])) $error = 'La contraseña actual no es correcta.';
        elseif (strlen($nueva) < 10) $error = 'La nueva contraseña debe tener al menos 10 caracteres.';
        elseif ($nueva !== ($_POST['nueva2'] ?? '')) $error = 'Las contraseñas nuevas no coinciden.';
        else {
            $admin['hash'] = password_hash($nueva, PASSWORD_DEFAULT);
            escribir_json('admin.json', $admin);
            session_regenerate_id(true);
            registrar('contraseña cambiada');
            $aviso = 'Contraseña actualizada.';
        }
    }
}

$maxSubida = ini_get('upload_max_filesize');
?><!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>NexCrono · Actualizaciones</title>
<style>
:root { --bg:#0b1114; --card:#1a252a; --txt:#e6eef0; --muted:#9fb2b8; --tq:#00c2a8; --nar:#ff8a3d; --rojo:#ff5370; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--txt); font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
main { max-width: 900px; margin: 0 auto; padding: 24px 16px 60px; }
h1 { font-size: 22px; margin: 0 0 4px; } h1 span { color: var(--tq); }
h2 { font-size: 14px; letter-spacing: .06em; text-transform: uppercase; color: var(--tq); margin: 0 0 12px; }
.sub { color: var(--muted); margin: 0 0 24px; font-size: 13px; }
.card { background: var(--card); border-radius: 16px; padding: 18px; margin-bottom: 16px; }
label { display:block; font-size: 13px; color: var(--muted); margin: 10px 0 4px; }
input[type=text], input[type=password], input[type=number], select, textarea {
  width:100%; padding:10px 12px; border-radius:10px; border:1px solid #2b3a40; background:#0f171a; color:var(--txt); font:inherit; }
textarea { min-height: 80px; }
button { background: var(--tq); color:#00201b; border:0; border-radius: 10px; padding: 10px 16px; font-weight:700; cursor:pointer; font:inherit; font-weight:700; }
button.sec { background: transparent; color: var(--txt); border:1px solid #3a4a50; font-weight:500; }
button.peligro { background: transparent; color: var(--rojo); border:1px solid var(--rojo); font-weight:500; }
.fila { display:flex; gap:12px; flex-wrap: wrap; } .fila > div { flex:1; min-width: 160px; }
.ok { background: rgba(0,194,168,.15); border-left: 4px solid var(--tq); padding: 10px 14px; border-radius: 8px; margin-bottom: 16px; }
.err { background: rgba(255,83,112,.15); border-left: 4px solid var(--rojo); padding: 10px 14px; border-radius: 8px; margin-bottom: 16px; }
table { width:100%; border-collapse: collapse; font-size: 14px; }
th, td { text-align:left; padding: 8px 6px; border-bottom: 1px solid #263338; vertical-align: top; }
th { color: var(--muted); font-weight: 500; font-size: 12px; }
.mono { font-family: ui-monospace, Consolas, monospace; font-size: 11px; color: var(--muted); word-break: break-all; }
.tag { font-size: 11px; font-weight: 700; padding: 2px 6px; border-radius: 6px; }
.on { background: rgba(0,194,168,.15); color: var(--tq); } .off { background: rgba(159,178,184,.15); color: var(--muted); }
.ob { background: rgba(255,138,61,.15); color: var(--nar); }
form.inline { display:inline; }
footer { color: var(--muted); font-size: 11px; margin-top: 30px; }
</style>
</head>
<body>
<main>
<h1>NexCrono <span>Actualizaciones</span></h1>
<p class="sub">Publica nuevas versiones de las apps. Los teléfonos las detectan y se actualizan solos.</p>

<?php if ($aviso): ?><div class="ok"><?= h($aviso) ?></div><?php endif; ?>
<?php if ($error): ?><div class="err"><?= h($error) ?></div><?php endif; ?>

<?php if (empty($admin)): ?>
  <div class="card">
    <h2>Instalación</h2>
    <p class="sub">Crea el usuario administrador. Necesitas la <b>clave de instalación</b> que pusiste en <code>config.php</code>.</p>
    <form method="post" autocomplete="off">
      <?= csrf_campo() ?><input type="hidden" name="accion" value="instalar">
      <label>Clave de instalación</label><input type="password" name="clave_instalacion" required>
      <label>Usuario</label><input type="text" name="usuario" required pattern="[A-Za-z0-9_.\-]{3,30}">
      <div class="fila">
        <div><label>Contraseña (mín. 10)</label><input type="password" name="clave" required minlength="10"></div>
        <div><label>Repetir contraseña</label><input type="password" name="clave2" required minlength="10"></div>
      </div>
      <p><button>Crear administrador</button></p>
    </form>
  </div>

<?php elseif (!logueado()): ?>
  <div class="card" style="max-width:420px">
    <h2>Iniciar sesión</h2>
    <form method="post">
      <?= csrf_campo() ?><input type="hidden" name="accion" value="login">
      <label>Usuario</label><input type="text" name="usuario" required autocomplete="username">
      <label>Contraseña</label><input type="password" name="clave" required autocomplete="current-password">
      <p><button>Entrar</button></p>
    </form>
  </div>

<?php else: ?>
  <div class="card">
    <h2>Publicar versión</h2>
    <form method="post" enctype="multipart/form-data">
      <?= csrf_campo() ?><input type="hidden" name="accion" value="subir">
      <div class="fila">
        <div><label>App</label>
          <select name="app"><?php foreach (APPS as $k => $n): ?><option value="<?= h($k) ?>"><?= h($n) ?></option><?php endforeach; ?></select></div>
        <div><label>versionCode (número de app/build.gradle)</label><input type="number" name="versionCode" min="1" required></div>
        <div><label>versionName</label><input type="text" name="versionName" placeholder="1.1.0" required></div>
      </div>
      <label>Notas (qué cambió)</label><textarea name="notas" maxlength="2000"></textarea>
      <label>Archivo APK (firmado con la llave de CodevNexus · máx. <?= h(min(MAX_MB . 'M', $maxSubida)) ?>)</label>
      <input type="file" name="apk" accept=".apk" required>
      <label><input type="checkbox" name="obligatoria" value="1"> Actualización obligatoria (el teléfono no puede posponerla)</label>
      <p><button>Publicar</button></p>
    </form>
  </div>

  <div class="card">
    <h2>Versiones publicadas</h2>
    <?php $lista = versiones(); usort($lista, fn($a, $b) => [$a['app'], $b['versionCode']] <=> [$b['app'], $a['versionCode']]); ?>
    <?php if (!$lista): ?><p class="sub">Todavía no hay versiones.</p><?php else: ?>
    <table>
      <tr><th>App</th><th>Versión</th><th>Estado</th><th>Publicada</th><th></th></tr>
      <?php foreach ($lista as $v): ?>
      <tr>
        <td><?= h(APPS[$v['app']] ?? $v['app']) ?></td>
        <td><b><?= h($v['versionName']) ?></b> (<?= (int)$v['versionCode'] ?>)<br>
          <span class="mono">SHA-256 <?= h($v['sha256']) ?> · <?= round($v['tamano'] / 1048576, 1) ?> MB</span>
          <?php if ($v['notas']): ?><br><span style="font-size:12px"><?= nl2br(h($v['notas'])) ?></span><?php endif; ?></td>
        <td><span class="tag <?= !empty($v['activa']) ? 'on' : 'off' ?>"><?= !empty($v['activa']) ? 'ACTIVA' : 'RETIRADA' ?></span>
          <?php if (!empty($v['obligatoria'])): ?><span class="tag ob">OBLIGATORIA</span><?php endif; ?></td>
        <td style="font-size:12px"><?= h(substr($v['fecha'], 0, 16)) ?></td>
        <td style="white-space:nowrap">
          <form method="post" class="inline"><?= csrf_campo() ?><input type="hidden" name="accion" value="activar">
            <input type="hidden" name="app" value="<?= h($v['app']) ?>"><input type="hidden" name="versionCode" value="<?= (int)$v['versionCode'] ?>">
            <button class="sec"><?= !empty($v['activa']) ? 'Retirar' : 'Activar' ?></button></form>
          <form method="post" class="inline" onsubmit="return confirm('¿Eliminar esta versión y su archivo?')"><?= csrf_campo() ?>
            <input type="hidden" name="accion" value="eliminar">
            <input type="hidden" name="app" value="<?= h($v['app']) ?>"><input type="hidden" name="versionCode" value="<?= (int)$v['versionCode'] ?>">
            <button class="peligro">Eliminar</button></form>
        </td>
      </tr>
      <?php endforeach; ?>
    </table>
    <?php endif; ?>
    <p class="sub" style="margin-top:12px">Los teléfonos consultan: <span class="mono"><?= h(url_base()) ?>/api/version.php?app=pistola</span></p>
  </div>

  <div class="card">
    <h2>Cambiar contraseña</h2>
    <form method="post" autocomplete="off">
      <?= csrf_campo() ?><input type="hidden" name="accion" value="clave">
      <div class="fila">
        <div><label>Actual</label><input type="password" name="actual" required></div>
        <div><label>Nueva (mín. 10)</label><input type="password" name="nueva" required minlength="10"></div>
        <div><label>Repetir nueva</label><input type="password" name="nueva2" required minlength="10"></div>
      </div>
      <p><button class="sec">Cambiar</button></p>
    </form>
  </div>

  <form method="post"><?= csrf_campo() ?><input type="hidden" name="accion" value="salir">
    <button class="sec">Cerrar sesión (<?= h($_SESSION['usuario']) ?>)</button></form>
<?php endif; ?>

<footer>NexCrono · CodevNexus · codevnexus.tech</footer>
</main>
</body>
</html>
