<?php
// Pública: la app consulta si hay una versión nueva.  GET api/version.php?app=pistola&v=CODIGO_ACTUAL
define('NXC', 1);
require __DIR__ . '/../lib.php';
exigir_https();
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$app = (string)($_GET['app'] ?? '');
if (!array_key_exists($app, APPS)) {
    http_response_code(404);
    echo json_encode(['error' => 'app desconocida']);
    exit;
}
$v = ultima_version($app);
if ($v === null) {
    http_response_code(404);
    echo json_encode(['error' => 'sin versiones publicadas']);
    exit;
}
echo json_encode([
    'app' => $app,
    'versionCode' => (int)$v['versionCode'],
    'versionName' => $v['versionName'],
    'url' => url_base() . '/descargar.php?app=' . rawurlencode($app) . '&v=' . (int)$v['versionCode'],
    'sha256' => $v['sha256'],
    'tamano' => (int)$v['tamano'],
    'notas' => $v['notas'],
    'obligatoria' => !empty($v['obligatoria']),
    'publicada' => $v['fecha'],
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
