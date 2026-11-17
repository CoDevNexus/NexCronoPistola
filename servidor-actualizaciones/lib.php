<?php
if (!defined('NXC')) { http_response_code(403); exit; }
require __DIR__ . '/config.php';

// ───────── Cabeceras de seguridad ─────────
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');
header("Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'");

function es_https(): bool {
    if (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') return true;
    if (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https') return true;   // Cloudflare / proxy
    return ($_SERVER['SERVER_PORT'] ?? '') == 443;
}

function exigir_https(): void {
    if (FORZAR_HTTPS && !es_https()) {
        http_response_code(403);
        exit('Se requiere HTTPS');
    }
}

function url_base(): string {
    if (URL_BASE !== '') return rtrim(URL_BASE, '/');
    $esquema = es_https() ? 'https' : 'http';
    $host = preg_replace('/[^A-Za-z0-9\.\-:\[\]]/', '', $_SERVER['HTTP_HOST'] ?? 'localhost');
    $dir = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
    if (substr($dir, -4) === '/api') $dir = substr($dir, 0, -4);
    return $esquema . '://' . $host . $dir;
}

function h($s): string { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }

// ───────── Datos (JSON con bloqueo) ─────────
function ruta_datos(string $f): string {
    if (!is_dir(DIR_DATOS)) mkdir(DIR_DATOS, 0750, true);
    if (!is_dir(DIR_DATOS . '/apks')) mkdir(DIR_DATOS . '/apks', 0750, true);
    $ht = DIR_DATOS . '/.htaccess';
    if (!file_exists($ht)) file_put_contents($ht, "<IfModule mod_authz_core.c>\n    Require all denied\n</IfModule>\n<IfModule !mod_authz_core.c>\n    Deny from all\n</IfModule>\n");
    return DIR_DATOS . '/' . $f;
}

function leer_json(string $f, $defecto) {
    $p = ruta_datos($f);
    if (!file_exists($p)) return $defecto;
    $fh = fopen($p, 'r');
    flock($fh, LOCK_SH);
    $txt = stream_get_contents($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    $d = json_decode($txt, true);
    return is_array($d) ? $d : $defecto;
}

function escribir_json(string $f, $datos): void {
    $p = ruta_datos($f);
    $tmp = $p . '.' . bin2hex(random_bytes(4)) . '.tmp';
    file_put_contents($tmp, json_encode($datos, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), LOCK_EX);
    rename($tmp, $p);
}

function registrar(string $msg): void {
    $ip = $_SERVER['REMOTE_ADDR'] ?? '-';
    file_put_contents(ruta_datos('registro.log'), date('c') . " [$ip] $msg\n", FILE_APPEND | LOCK_EX);
}

// ───────── Versiones ─────────
function versiones(): array { return leer_json('versiones.json', []); }

function guardar_versiones(array $v): void { escribir_json('versiones.json', $v); }

function ultima_version(string $app): ?array {
    $mejor = null;
    foreach (versiones() as $v) {
        if ($v['app'] === $app && !empty($v['activa']) && ($mejor === null || $v['versionCode'] > $mejor['versionCode'])) $mejor = $v;
    }
    return $mejor;
}

// ───────── Límite de intentos ─────────
function clave_ip(): string { return hash('sha256', ($_SERVER['REMOTE_ADDR'] ?? '-') . '|nxc'); }

function intentos_bloqueado(): bool {
    $t = leer_json('intentos.json', []);
    $k = clave_ip();
    $ahora = time();
    $lista = array_filter($t[$k] ?? [], fn($x) => $x > $ahora - 900);
    return count($lista) >= MAX_INTENTOS;
}

function intento_fallido(): void {
    $t = leer_json('intentos.json', []);
    $k = clave_ip();
    $ahora = time();
    $t[$k] = array_values(array_filter($t[$k] ?? [], fn($x) => $x > $ahora - 900));
    $t[$k][] = $ahora;
    foreach ($t as $kk => $vv) { if (!array_filter($vv, fn($x) => $x > $ahora - 900)) unset($t[$kk]); }
    escribir_json('intentos.json', $t);
}

function limpiar_intentos(): void {
    $t = leer_json('intentos.json', []);
    unset($t[clave_ip()]);
    escribir_json('intentos.json', $t);
}

// ───────── Sesión y CSRF ─────────
function iniciar_sesion(): void {
    session_name('nxc_admin');
    session_set_cookie_params([
        'lifetime' => 0, 'path' => '/', 'secure' => es_https(), 'httponly' => true, 'samesite' => 'Strict',
    ]);
    ini_set('session.use_strict_mode', '1');
    session_start();
    if (!empty($_SESSION['usuario'])) {
        if (time() - ($_SESSION['ultimo'] ?? 0) > MINUTOS_SESION * 60) {
            $_SESSION = [];
            session_regenerate_id(true);
        } else {
            $_SESSION['ultimo'] = time();
        }
    }
    if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(32));
}

function csrf_campo(): string { return '<input type="hidden" name="csrf" value="' . h($_SESSION['csrf']) . '">'; }

function verificar_csrf(): void {
    if (!hash_equals($_SESSION['csrf'] ?? '', (string)($_POST['csrf'] ?? ''))) {
        http_response_code(400);
        exit('Solicitud inválida (CSRF). Recarga la página.');
    }
}

function admin(): array { return leer_json('admin.json', []); }

function logueado(): bool { return !empty($_SESSION['usuario']); }
