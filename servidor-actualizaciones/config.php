<?php
// ─────────────────────────────────────────────────────────────
//  CONFIGURACIÓN — edita estos valores ANTES de subir al hosting
// ─────────────────────────────────────────────────────────────
if (!defined('NXC')) { http_response_code(403); exit; }

// Clave de instalación: solo quien la conozca puede crear la contraseña de administrador
// la primera vez. Cámbiala por una frase larga y única (mínimo 16 caracteres).
const CLAVE_INSTALACION = 'CAMBIA-ESTA-CLAVE-POR-UNA-LARGA-Y-UNICA';

// URL pública de esta carpeta, sin barra final. Ej.: https://updates.codevnexus.tech
// Vacía = se detecta sola.
const URL_BASE = '';

// Exigir HTTPS (recomendado). Ponlo en false solo para pruebas locales.
const FORZAR_HTTPS = true;

// Apps que se pueden publicar (nombre corto => nombre visible)
const APPS = ['pistola' => 'NexCrono Pistola'];

// Tamaño máximo de un APK (MB). Revisa también upload_max_filesize y post_max_size del hosting.
const MAX_MB = 150;

// Carpeta de datos (APKs, versiones, contraseña). Si tu hosting lo permite, muévela FUERA
// de public_html y pon aquí la ruta absoluta, p. ej. '/home/usuario/nexcrono-datos'.
const DIR_DATOS = __DIR__ . '/datos';

// Sesión del panel: minutos de inactividad antes de pedir contraseña otra vez
const MINUTOS_SESION = 30;

// Intentos de login fallidos permitidos por IP en 15 minutos
const MAX_INTENTOS = 5;
