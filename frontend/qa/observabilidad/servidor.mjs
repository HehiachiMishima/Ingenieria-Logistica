// Sirve únicamente el bundle de pruebas y respuestas ficticias en loopback.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';

const root = resolve(process.env.ATLAS_QA_BUNDLE ?? 'dist/frontend/browser');
const actor = { id: 1234567890123, nombre: 'Prueba', apellido: 'Local', usuario: '@qa', activo: true };
const permisos = ['PANEL_ADMIN', 'DASHBOARD_ADMIN', 'VER_AUDITORIA', 'VER_ACTIVIDAD', 'VER_MONITOREO'];
const analitica = {
  kpis: { usuarios: 320, usuariosActivos: 296, usuariosSinRol: 3, usuariosConExcepcion: 12, usuariosSoloRol: 281,
    overridesGrant: 9, overridesDeny: 4, conectados: 41, altas30: 18, altasPrevias30: 11, roles: 14, rolesActivos: 12,
    rolesSinUsuarios: 1, rolesSinPermisos: 0, permisos: 120, permisosActivos: 118 },
  coberturaPaneles: [{ etiqueta:'PANEL_PICKING', valor:180 }, { etiqueta:'PANEL_BITACORA', valor:150 }],
  usuariosPorRol: [{ etiqueta:'Operador de picking', valor:150 }, { etiqueta:'Analista', valor:60 }],
  crecimiento: Array.from({ length: 12 }, (_, i) => ({ periodo: `2026-${String(i + 1).padStart(2, '0')}`, altas: 10 + i, total: 200 + i * 10 })),
};
const ruta = '/api/simulacion/registros/{id}/balanceo-guardado/guardar';
const tipos = { '.js':'text/javascript', '.css':'text/css', '.html':'text/html', '.svg':'image/svg+xml',
  '.woff2':'font/woff2', '.png':'image/png', '.jpg':'image/jpeg', '.ico':'image/x-icon' };

// Datos extremos: rutas largas, montos de 13 dígitos, usuarios de 60+ caracteres.
const usuarioLargo = 'usuario.' + 'a'.repeat(60) + '@example.test';
const apis = [
  { metodo:'POST', ruta, peticiones:1234567890123, errores:41234567890, porcentaje:3.3, p95:780 },
  { metodo:'GET', ruta:'/api/picking/olas', peticiones:18920, errores:96, porcentaje:0.5, p95:540 },
  { metodo:'POST', ruta:'/api/simulacion/registros/{id}/clusters/{clusterId}/restablecer', peticiones:2310, errores:52, porcentaje:2.3, p95:470 },
  { metodo:'PUT', ruta:'/api/usuarios/{id}', peticiones:412, errores:9, porcentaje:2.2, p95:260 },
  { metodo:'GET', ruta:'/api/incidencias', peticiones:24115, errores:12, porcentaje:0, p95:310 },
  { metodo:'POST', ruta:'/api/auth/login', peticiones:6870, errores:118, porcentaje:1.7, p95:180 },
  { metodo:'GET', ruta:'/api/malla/centros', peticiones:9540, errores:4, porcentaje:0, p95:150 },
  { metodo:'DELETE', ruta:'/api/usuarios/{usuarioId}/roles/{rolId}', peticiones:31, errores:3, porcentaje:9.7, p95:95 },
  ...Array.from({ length: 6 }, (_, i) => ({ metodo:'GET', ruta:`/api/admin/prueba-${i}`, peticiones:100 + i, errores:0, porcentaje:0, p95:40 + i })),
];

function serie(dias, punto) {
  const n = dias === 1 ? 24 : dias;
  return Array.from({ length: n }, (_, i) => ({
    periodo: dias === 1 ? `2026-11-19T${String(i).padStart(2, '0')}:00` : `2026-11-${String(Math.max(1, 19 - n + 1 + i)).padStart(2, '0')}`,
    ...punto(i),
  }));
}

function resumen(pathname, dias, vacia) {
  if (pathname.endsWith('/monitoreo/resumen')) {
    return { peticiones: vacia ? 0 : 1234567890123, peticionesPrevias: 1100000000000, errores: 9876543210,
      erroresPrevios: 8000000000, erroresServidor: 4321098765, erroresServidorPrevios: 4000000000, p95: 420, p95Previo: 478,
      serie: serie(dias, (i) => ({ peticiones: vacia ? 0 : 8000 + i * 300, errores: 40 + i, p95: [210,190,180,175,190,260,380,470,560,610,540,480][i % 12] })),
      calor: vacia ? [] : Array.from({ length: 7 * 6 }, (_, k) => ({ dia: Math.floor(k / 6) + 1, desde: (k % 6) * 4,
        peticiones: 1234567890, p95: Math.floor(k / 6) < 5 && (k % 6) === 2 ? 610 + k : 180 + (k % 6) * 40 })) };
  }
  if (pathname.endsWith('/actividad/resumen')) {
    const calor = vacia ? [] : Array.from({ length: 7 * 24 }, (_, k) => ({ dia: Math.floor(k / 24) + 1, hora: k % 24,
      acciones: (Math.floor(k / 24) < 5 && k % 24 >= 8 && k % 24 <= 18 ? 900 : 40) + (k % 24) * 7 }));
    return { usuarios: vacia ? 0 : 184, usuariosPrevios: 169, acciones: vacia ? 0 : 1234567890123, accionesPrevias: 36900,
      calor, paneles: vacia ? [] : [
        { panel:'PANEL_PICKING', usuarios:96, acciones:21000 }, { panel:'PANEL_BITACORA', usuarios:71, acciones:9800 },
        { panel:'PANEL_SIMULACION', usuarios:44, acciones:5200 }, { panel:'PANEL_SIMULACION_SANTA', usuarios:31, acciones:2100 },
        { panel:'PANEL_MALLA', usuarios:28, acciones:1500 }, { panel:'PANEL_ADMIN', usuarios:6, acciones:820 },
        { panel:'SESION', usuarios:184, acciones:400 }],
      serie: serie(dias, (i) => ({ usuarios: vacia ? 0 : 150 + (i * 13) % 40, acciones: 3000 + i * 100 })),
      semanas: Array.from({ length: 12 }, (_, i) => ({ periodo: `2026-${i < 4 ? '08' : i < 8 ? '09' : '10'}-${String(1 + (i % 4) * 7).padStart(2, '0')}`,
        usuarios: vacia ? 0 : 120 + i * 6, acciones: 20000 + i * 900 })) };
  }
  return { cambios: vacia ? 0 : 312, cambiosPrevios: 264, fallidos: 7, fallidosPrevios: 9, denegados: 9, denegadosPrevios: 2,
    personas: 14, serie: serie(dias, (i) => ({ exito: vacia ? 0 : 20 + (i * 7) % 41, fallo: i % 3, denegado: i % 5 === 3 ? 6 : 0 })),
    entidades: vacia ? [] : [{ nombre:'Archivo Titan', total:148 }, { nombre:'Rol de usuario', total:81 }, { nombre:'Usuario', total:52 },
      { nombre:'Permiso de rol', total:19 }, { nombre:'Permiso de usuario', total:12 }],
    denegadosPorUsuario: vacia ? [] : [{ nombre: usuarioLargo, total: 6 }],
    calor: vacia ? [] : Array.from({ length: 7 * 24 }, (_, k) => ({ dia: Math.floor(k / 24) + 1, hora: k % 24,
      total: Math.floor(k / 24) < 5 && k % 24 >= 9 && k % 24 <= 18 ? 7 : (k === 5 * 24 + 2 || k === 2 * 24 + 22 ? 2 : 0) })),
    matriz: vacia ? [] : [usuarioLargo, 'aquispe', 'lramos', 'mtorres', 'crios'].flatMap((usuario, i) =>
      ['Archivo Titan', 'Rol de usuario', 'Usuario', 'Permiso de rol', 'Permiso de usuario de prueba con nombre largo']
        .map((entidad, j) => ({ usuario, entidad, total: i === j ? 30 + i : (i + j) % 3 }))) };
}

function responderApi(url, res) {
  let datos;
  const vacia = url.searchParams.get('usuario') === 'sin-datos';
  if (url.pathname.startsWith('/api/auth/')) {
    datos = { usuario: actor, permisos, token:'fixture', expiraEn:'2099-01-01T00:00:00Z', renovableHasta:'2099-01-02T00:00:00Z' };
  } else if (url.pathname === '/api/admin/monitoreo') {
    datos = apis;
  } else if (url.pathname === '/api/admin/analitica') {
    datos = analitica;
  } else if (url.searchParams.get('usuario') === 'fallo') {
    res.writeHead(503, {'Content-Type':'application/json'});
    return res.end(JSON.stringify({exito:false,mensaje:'Fallo de prueba.'}));
  } else if (url.pathname.endsWith('/resumen')) {
    datos = resumen(url.pathname, Number(url.searchParams.get('dias') || 7), vacia);
  } else {
    const vacia = url.searchParams.get('usuario') === 'sin-datos';
    const antes = Number(url.searchParams.get('antes') || 120);
    const filas = vacia ? [] : Array.from({length:Math.min(50,antes-1)}, (_,i) => ({
      id:antes-i-1,fecha:'2026-11-19T10:15:00Z', usuario:'usuario.'+'a'.repeat(60)+'@example.test',
      accion:url.pathname.endsWith('peticiones') ? null : 'Guardar predeterminado',
      entidad:'Archivo Titan',registro:'1234567890123',metodo:'POST',ruta,estado:409,duracion_ms:134,
      resultado:'Fallo',correlacion:'a'.repeat(64),
    }));
    datos = {filas,siguiente: filas.length===50 ? filas.at(-1).id : null};
  }
  res.writeHead(200,{'Content-Type':'application/json'});
  res.end(JSON.stringify({exito:true,datos}));
}
http.createServer(async (req,res) => {
  try {
    const url = new URL(req.url,'http://127.0.0.1');
    if (url.pathname.startsWith('/api/')) return responderApi(url,res);
    const name = resolve(root,'.'+url.pathname);
    if (name!==root && !name.startsWith(root+sep)) {res.writeHead(403);return res.end();}
    const file = extname(name) ? name : resolve(root,'index.html');
    const data = await readFile(file);
    res.writeHead(200,{'Content-Type':tipos[extname(file)] ?? 'application/octet-stream'});
    res.end(data);
  } catch {res.writeHead(404);res.end();}
}).listen(4287,'127.0.0.1');
