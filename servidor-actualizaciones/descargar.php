<?php
// Descarga de un APK publicado.  GET descargar.php?app=pistola&v=CODIGO
define('NXC', 1);
require __DIR__ . '/lib.php';
exigir_https();

$app = (string)($_GET['app'] ?? '');
$code = filter_var($_GET['v'] ?? '', FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
if (!array_key_exists($app, APPS) || $code === false) { http_response_code(404); exit('No encontrado'); }

$version = null;
foreach (versiones() as $v) {
    if ($v['app'] === $app && (int)$v['versionCode'] === $code && !empty($v['activa'])) { $version = $v; break; }
}
if ($version === null) { http_response_code(404); exit('No encontrado'); }

// el nombre del archivo viene de nuestro índice, nunca del usuario
$archivo = ruta_datos('apks/' . basename($version['archivo']));
if (!is_file($archivo)) { http_response_code(404); exit('No encontrado'); }

registrar("descarga $app v$code");
header('Content-Type: application/vnd.android.package-archive');
header('Content-Length: ' . filesize($archivo));
header('Content-Disposition: attachment; filename="NexCrono-' . $app . '-' . preg_replace('/[^0-9A-Za-z\.\-]/', '', $version['versionName']) . '.apk"');
header('Cache-Control: no-store');
readfile($archivo);
