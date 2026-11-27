// TEMPORAL: verificación de DATA en Configuración con una API falsa en memoria (sin backend). Se borra al terminar.
import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { Routes } from '@angular/router';
import { Observable, delay, of, throwError } from 'rxjs';
import { PERMISOS } from '../core/config/permisos';
import { DirectorioUsuarios } from '../core/services/directorio-usuarios';
import { SesionStore } from '../core/services/sesion';
import { SimulacionApi } from '../features/panels/simulacion/data/simulacion.api';
import {
  Balanceo,
  ConfiguracionSimulacion,
  DATA_ID,
  LocalRuta,
  RutaDataRequest,
  RutaSimulacion,
} from '../features/panels/simulacion/data/simulacion.model';

const q = new URLSearchParams(location.search);
const CASO = q.get('caso') ?? 'normal';
const SOLO_VER = q.get('permisos') === 'ver';
document.documentElement.dataset['theme'] = q.get('tema') ?? 'light';
const ESPERA = Number(q.get('espera') ?? '60');

type Estado = 'PENDIENTE' | 'RUTAS' | 'LISTO' | 'CERRADO';
interface Cl {
  id: number;
  nombre: string;
  estado: Record<Balanceo, Estado>;
}
interface R {
  id: number;
  codigo: string;
  tipo: string | null;
  frecuencia: string | null;
  grupo: string | null;
  clusterId: number | null;
  clusterOriginalId: number | null;
  chuteId: number;
  chuteOriginalId: number;
  locales: LocalRuta[];
}

const CHUTES = Array.from({ length: 10 }, (_, i) => ({ id: 201 + i, nombre: String(i + 1) }));
let ids = 0;
let clusters: Cl[] = [];
let rutas: R[] = [];
const L = (cod: string, b: number, u = 100, l = 10, filas = 1): LocalRuta => ({ orden: 0, cod, filas, bultos: b, unidades: u, lineas: l });
const est = (dia: Estado, lxv: Estado, mjs: Estado): Record<Balanceo, Estado> => ({ DIA: dia, LXV: lxv, MJS: mjs });
const PEND = est('PENDIENTE', 'PENDIENTE', 'PENDIENTE');

function agregar(codigo: string, tipo: string | null, frecuencia: string | null, grupo: string | null, clusterId: number | null, chute: number, locales: LocalRuta[], origen = clusterId) {
  const id = ++ids;
  locales.forEach((l, i) => (l.orden = i + 1));
  rutas.push({ id, codigo, tipo, frecuencia, grupo, clusterId, clusterOriginalId: origen, chuteId: 200 + chute, chuteOriginalId: 200 + chute, locales });
}

function sembrar() {
  if (CASO === 'vacio') {
    return;
  }
  clusters = [
    { id: 101, nombre: 'CL1', estado: est('RUTAS', 'RUTAS', 'PENDIENTE') },
    { id: 102, nombre: 'CL2', estado: PEND },
    { id: 103, nombre: 'CL3', estado: PEND },
  ];
  agregar('2002', null, null, null, null, 1, [L('7875', 122.46)]);
  agregar('2015', 'B2C', 'LXV', '1', null, 2, [L('F045', 100), L('7880', 22.46), L('F112', 268, 2301, 151.5, 2)]);
  agregar('2040', 'B2B', null, null, null, 3, [L('F087', 301.2)]);
  agregar('3102', 'B2B', 'MJS', '2', null, 2, [L('F203', 215)]);
  if (CASO.startsWith('huecos')) {
    clusters = [
      { id: 101, nombre: 'CL1', estado: CASO === 'huecos-empezado' ? est('CERRADO', 'CERRADO', 'CERRADO') : est('RUTAS', 'RUTAS', 'PENDIENTE') },
      { id: 104, nombre: 'CL4', estado: CASO === 'huecos-empezado' ? est('RUTAS', 'PENDIENTE', 'PENDIENTE') : PEND },
    ];
  }
  if (CASO === 'grande' || CASO === 'listo' || CASO === 'real' || CASO === 'sinope') {
    // CL1 con operarios ya repartidos (paso de operarios hecho).
    clusters[0].estado = est('LISTO', 'LISTO', 'LISTO');
  }
  if (CASO === 'grande') {
    // Cifras de un cluster real grande: 128 rutas, ~98 mil bultos, ~1,2 millones de unidades.
    for (let i = 0; i < 125; i++) {
      agregar(String(1300 + i), 'B2C', 'LXV', '1', 101, 1 + (i % 10), Array.from({ length: 10 }, (_, j) => L('G' + i + '-' + j, 78.5, 985, 36)));
    }
  }
  if (CASO === 'real' || CASO === 'sinope') {
    // Cluster de tamaño real (en «sinope», el chute 1 queda sin operarios: su cierre no se puede calcular): 29 rutas más en sus 10 chutes, con L-X-V, M-J-S y diarias.
    const frec = ['LXV', 'MJS', 'DIARIA'];
    for (let i = 0; i < 29; i++) {
      const b = 60 + ((i * 137) % 340);
      agregar(String(1200 + i), i % 4 ? 'B2C' : 'B2B', frec[i % 3], '1', 101, 1 + ((i * 3) % 10), [
        L('R' + i + 'A', Math.round(b * 0.6), Math.round(b * 6.5), Math.round(b * 0.3)),
        L('R' + i + 'B', Math.round(b * 0.4), Math.round(b * 4.2), Math.round(b * 0.2)),
      ]);
    }
  }
  agregar('1001', 'B2C', 'LXV', '1', 101, 1, [L('F019', 412)]);
  agregar('1003', 'B2C', 'DIARIA', '1', 101, 2, [L('F156', 280)]);
  agregar('1007', 'B2B', 'MJS', '2', 101, 3, [L('F231', 133)]);
  if (CASO === 'balanceado' || CASO === 'huecos-empezado') {
    // Una ruta de CL1 (ya balanceado o cerrado) con dos locales: se pueden sacar.
    agregar('1012', 'B2C', 'LXV', '1', 101, 4, [L('F300', 40), L('F301', 25)]);
  }
  agregar('1105', 'B2C', 'MJS', 'A', CASO.startsWith('huecos') ? 104 : 102, 1, [L('F190', 356)]);
  agregar('1108', 'B2B', 'LXV', 'A', CASO.startsWith('huecos') ? 104 : 102, 4, [L('F066', 77)]);
  if (CASO === 'extremo') {
    agregar('R-30135-XYZ-SAN-JUAN-DE-LURIGANCHO-PROCERES-12345678', 'B2C', 'DIARIA', 'GRUPO-LARGO-DE-CINCUENTA-CARACTERES-1234567890', null, 5, [
      L('LOCAL-SAN-JUAN-DE-LURIGANCHO-AV-PROCERES-' + 'X'.repeat(40), 9999999999.99, 9999999999.99, 9999999999.99, 12),
    ]);
    for (let i = 0; i < 60; i++) {
      agregar(String(5000 + i), i % 2 ? 'B2C' : null, null, null, null, 1 + (i % 3), [L('C' + i, 10 + i)]);
    }
  }
}
sembrar();

const esperar = <T>(valor: T): Observable<T> => of(valor).pipe(delay(ESPERA));
const fallar = (status: number, mensaje: string) =>
  throwError(() => new HttpErrorResponse({ status, error: { mensaje } })).pipe(delay(ESPERA));
const nombreCluster = (id: number | null) => (id === null ? 'DATA' : (clusters.find((c) => c.id === id)?.nombre ?? '?'));
const nombreChute = (id: number) => CHUTES.find((c) => c.id === id)?.nombre ?? '?';
const suma = (r: R, k: 'bultos' | 'unidades' | 'lineas') => r.locales.reduce((t, l) => t + l[k], 0);
const enBalanceo = (f: string | null, b: Balanceo) => f === 'DIARIA' || (b === 'LXV' ? f === 'LXV' : b === 'MJS' ? f === 'MJS' : false);

function archivo() {
  return {
    id: 1, nombreRegistro: 'Simulación Titan', nombreArchivo: 'Escenario', etiqueta: null, colorHex: null,
    estado: 'PROYECCION' as const, rutas: rutas.filter((r) => r.clusterId !== null).length, chutes: CHUTES.length, bultos: 0, unidades: 0, lineas: 0, cod: 0,
    creadoEn: new Date().toISOString(), actualizadoEn: null, creadoPor: null, actualizadoPor: null,
  };
}

function config(balanceo: Balanceo): ConfiguracionSimulacion {
  const carga = new Map<string, { clusterId: number; chuteId: number; rutas: number; bultos: number; unidades: number; lineas: number; cod: number; operarios: number | null }>();
  for (const r of rutas) {
    if (r.clusterId === null || !enBalanceo(r.frecuencia, balanceo)) {
      continue;
    }
    const k = r.clusterId + ':' + r.chuteId;
    const estadoCl = clusters.find((x) => x.id === r.clusterId)?.estado[balanceo];
    const c = carga.get(k) ?? { clusterId: r.clusterId, chuteId: r.chuteId, rutas: 0, bultos: 0, unidades: 0, lineas: 0, cod: 0, operarios: estadoCl === 'LISTO' || estadoCl === 'CERRADO' ? (CASO === 'sinope' && r.chuteId === 201 ? 0 : 3) : null };
    c.rutas++;
    c.bultos += suma(r, 'bultos');
    c.unidades += suma(r, 'unidades');
    c.lineas += suma(r, 'lineas');
    c.cod += r.locales.length;
    carga.set(k, c);
  }
  const cuenta = (id: number, f: string) => rutas.filter((r) => r.clusterId === id && r.frecuencia === f).length;
  return {
    archivo: archivo(), velocidadBase: 100, operariosMaximos: 10, operariosPorChute: null, topePorChute: 0,
    clusters: clusters.map((c) => ({
      id: c.id, nombre: c.nombre, limiteHoras: 0, estado: c.estado[balanceo], diferenciaOriginal: null, chuteDesdeId: null,
      chuteHastaId: null, operariosOla: null, grupos: [], balanceo, diariasFijas: false,
      avance: { diarias: c.estado.DIA, lxv: c.estado.LXV, mjs: c.estado.MJS, rutasDiarias: cuenta(c.id, 'DIARIA'), rutasLxv: cuenta(c.id, 'LXV'), rutasMjs: cuenta(c.id, 'MJS') },
    })),
    chutes: CHUTES.map((ch) => ({
      id: ch.id, nombre: ch.nombre, operarios: 1, bloqueado: false, buff: false,
      clusterIds: [...new Set(rutas.filter((r) => r.chuteId === ch.id && r.clusterId !== null).map((r) => r.clusterId as number))],
    })),
    carga: [...carga.values()],
    chutesBloqueados: [], balanceo, buffBloqueado: false, balanceoGuardado: false,
  } as unknown as ConfiguracionSimulacion;
}

function aRuta(r: R): RutaSimulacion {
  return {
    id: r.id, chuteId: r.chuteId, chute: nombreChute(r.chuteId), chuteOriginalId: r.chuteOriginalId, chuteOriginal: nombreChute(r.chuteOriginalId),
    clusterId: r.clusterId ?? DATA_ID, cluster: nombreCluster(r.clusterId), clusterOriginalId: r.clusterOriginalId ?? DATA_ID,
    clusterOriginal: nombreCluster(r.clusterOriginalId), clusterEditable: r.clusterId !== null, codigoRuta: r.codigo, tipo: r.tipo,
    totalBultos: suma(r, 'bultos'), totalUnidades: suma(r, 'unidades'), totalLineas: suma(r, 'lineas'), conteoCod: r.locales.length,
    grupo: r.grupo, frecuencia: r.frecuencia as RutaSimulacion['frecuencia'], diariaFija: false, dias: null,
  };
}

const localesDe = (req: RutaDataRequest): LocalRuta[] =>
  req.locales.map((l) => ({ orden: 0, cod: l.cod.toUpperCase(), filas: l.filas ?? 1, bultos: l.bultos, unidades: l.unidades, lineas: l.lineas }));

class ApiFalsa {
  opciones() { return esperar([archivo()]); }
  predeterminados() { return esperar({ velocidad: 100, chutesBuff: [], bloqueado: false }); }
  configuracion(_id: number, balanceo: Balanceo = 'LXV') { console.log('[fake] configuracion ' + balanceo); return esperar(config(balanceo)); }
  guardarConfiguracion(_id: number, _p: unknown, balanceo: Balanceo = 'LXV') {
    console.log('[fake] guardarConfiguracion ' + JSON.stringify(_p));
    if (q.get('choque') === 'guardar') {
      return fallar(409, 'Otra persona cambió la velocidad (ahora 120) mientras editabas: pulsa «Actualizar» y vuelve a hacer tu cambio.');
    }
    if (q.get('falla') === 'guardar') {
      return fallar(400, 'Velocidad inválida (prueba).');
    }
    return esperar(config(balanceo));
  }
  rutas(_id: number, filtros: { texto: string; chuteIds?: number[] }) {
    const texto = (filtros.texto ?? '').toUpperCase();
    const chutes = filtros.chuteIds ?? [];
    const lista = rutas
      .filter((r) => !texto || r.codigo.startsWith(texto))
      .filter((r) => chutes.length === 0 || chutes.includes(r.chuteId))
      .map(aRuta);
    return esperar({ contenido: lista, total: lista.length, siguiente: null });
  }
  moverLocales(_id: number, origenId: number, destinoId: number, ordenes: number[], cods?: (string | null)[]) {
    console.log('[fake] moverLocales', JSON.stringify({ origenId, destinoId, ordenes, cods }));
    if (q.get('choque') === 'locales') {
      return fallar(409, 'Los locales de la ruta ' + rutas.find((x) => x.id === origenId)!.codigo + ' cambiaron mientras tenías abierta su ficha: vuelve a abrirla y elígelos otra vez.');
    }
    const o = rutas.find((x) => x.id === origenId)!;
    const d = rutas.find((x) => x.id === destinoId)!;
    if (ordenes.length >= o.locales.length) {
      return fallar(409, `La ruta ${o.codigo} se quedaría sin locales: deja al menos uno o elimínala.`);
    }
    let sumados = 0;
    let siguiente = Math.max(...d.locales.map((l) => l.orden)) + 1;
    for (const orden of ordenes) {
      const l = o.locales.find((x) => x.orden === orden)!;
      o.locales = o.locales.filter((x) => x !== l);
      const igual = d.locales.find((x) => x.cod === l.cod);
      if (igual) {
        igual.filas += l.filas; igual.bultos += l.bultos; igual.unidades += l.unidades; igual.lineas += l.lineas;
        sumados++;
      } else {
        d.locales.push({ ...l, orden: siguiente++ });
      }
    }
    return esperar({ mensaje: `${ordenes.length === 1 ? '1 local' : ordenes.length + ' locales'} de ${o.codigo} → ${d.codigo}.${sumados ? ' ' + sumados + ' se sumó a su mismo código.' : ''}` });
  }
  localesRuta(_id: number, rutaId: number) {
    console.log('[fake] localesRuta ' + rutaId);
    const r = rutas.find((x) => x.id === rutaId);
    return esperar({ rutaId, conDetalle: true, truncado: false, locales: (r?.locales ?? []).map((l) => ({ ...l })) });
  }
  crearRutaData(_id: number, req: RutaDataRequest) {
    console.log('[fake] crearRutaData', req.codigoRuta);
    const codigo = req.codigoRuta.trim().toUpperCase();
    if (rutas.some((r) => r.codigo === codigo && r.tipo === null)) {
      return fallar(409, `Ya hay una ruta ${codigo} sin tipo en DATA.`);
    }
    agregar(codigo, null, null, null, null, 1 + Math.floor(Math.random() * 10), localesDe(req));
    return esperar({ mensaje: `Ruta ${codigo} creada en DATA.` });
  }
  actualizarRutaData(_id: number, rutaId: number, req: RutaDataRequest) {
    console.log('[fake] actualizarRutaData ' + rutaId + ' ' + req.codigoRuta);
    const r = rutas.find((x) => x.id === rutaId)!;
    r.codigo = req.codigoRuta.trim().toUpperCase();
    r.locales = localesDe(req);
    return esperar({ mensaje: `Ruta ${r.codigo} guardada.` });
  }
  eliminarRutaData(_id: number, rutaId: number) {
    rutas = rutas.filter((r) => r.id !== rutaId);
    return esperar({ mensaje: 'Ruta eliminada.' });
  }
  eliminarRutasData(_id: number, rutaIds: number[]) {
    console.log('[fake] eliminarRutasData', JSON.stringify(rutaIds));
    const enCluster = rutas.filter((r) => rutaIds.includes(r.id) && r.clusterId !== null);
    if (enCluster.length > 0) {
      return fallar(409, 'Solo se eliminan rutas de DATA: ' + enCluster.map((r) => r.codigo).join(', ') + ' están en un cluster.');
    }
    const antes = rutas.length;
    rutas = rutas.filter((r) => !rutaIds.includes(r.id));
    return esperar({ mensaje: (antes - rutas.length) + ' rutas eliminadas de DATA.' });
  }
  asignarData(_id: number, que: 'tipo' | 'frecuencia' | 'grupo', rutaIds: number[], valor: string) {
    console.log('[fake] asignarData', que, valor);
    const elegidas = rutas.filter((r) => rutaIds.includes(r.id));
    elegidas.forEach((r) => (r[que] = que === 'grupo' ? valor.toUpperCase() : valor));
    return esperar({ mensaje: `${que} ${valor} asignado a ${elegidas.length} rutas.` });
  }
  moverData(_id: number, rutaIds: number[], clusterId: number, chuteId: number) {
    console.log('[fake] moverData', JSON.stringify({ rutaIds, clusterId, chuteId }));
    for (const r of rutas.filter((x) => rutaIds.includes(x.id))) {
      r.clusterId = clusterId === DATA_ID ? null : clusterId;
      r.clusterOriginalId = r.clusterId;
      r.chuteId = chuteId;
      r.chuteOriginalId = r.chuteId;
    }
    return esperar({ mensaje: `${rutaIds.length} rutas → ${nombreCluster(clusterId === DATA_ID ? null : clusterId)}.` });
  }
  agregarCluster(_id: number, numero: number) {
    console.log('[fake] agregarCluster', numero);
    const nombre = 'CL' + numero;
    if (clusters.some((c) => c.nombre === nombre)) {
      return fallar(409, nombre + ' ya existe en este escenario.');
    }
    clusters.push({ id: 100 + numero, nombre, estado: PEND });
    clusters.sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { numeric: true }));
    return esperar({ mensaje: `${nombre} agregado. Ahora hay ${clusters.length} clusters.` });
  }
  quitarCluster(_id: number, clusterId: number) {
    console.log('[fake] quitarCluster', clusterId);
    const c = clusters.find((x) => x.id === clusterId);
    if (!c) {
      return fallar(404, 'Ese cluster ya no está en el escenario. Actualiza la página.');
    }
    const vuelven = rutas.filter((r) => r.clusterId === clusterId);
    vuelven.forEach((r) => ((r.clusterId = null), (r.clusterOriginalId = null)));
    clusters = clusters.filter((x) => x.id !== clusterId);
    return esperar({ mensaje: `${c.nombre} quitado${vuelven.length ? '; ' + vuelven.length + ' rutas volvieron a DATA' : ''}. Ahora hay ${clusters.length} clusters.` });
  }
  cambiarClusters(_id: number, cantidad: number) {
    while (clusters.length < cantidad) {
      const n = clusters.length + 1;
      clusters.push({ id: 100 + n, nombre: 'CL' + n, estado: PEND });
    }
    while (clusters.length > cantidad) {
      const ultimo = clusters.pop()!;
      rutas.filter((r) => r.clusterId === ultimo.id).forEach((r) => ((r.clusterId = null), (r.clusterOriginalId = null)));
    }
    return esperar({ mensaje: `Ahora hay ${cantidad} clusters.` });
  }
  moverRutas(_id: number, rutaIds: number[], clusterId: number, chuteId: number, balanceo: Balanceo = 'LXV') {
    rutas.filter((r) => rutaIds.includes(r.id)).forEach((r) => ((r.clusterId = clusterId), (r.chuteId = chuteId)));
    return esperar(config(balanceo));
  }
  moverRuta(_id: number, rutaId: number, chuteId: number, balanceo: Balanceo = 'LXV') {
    rutas.filter((r) => r.id === rutaId).forEach((r) => (r.chuteId = chuteId));
    return esperar(config(balanceo));
  }
  cambiarCluster(_id: number, rutaId: number, clusterId: number, chuteId: number | null, balanceo: Balanceo = 'LXV') {
    rutas.filter((r) => r.id === rutaId).forEach((r) => ((r.clusterId = clusterId), (r.chuteId = chuteId ?? r.chuteId)));
    return esperar(config(balanceo));
  }
  historial() { return esperar({ contenido: [], total: 0 }); }
}

const sesion = {
  permisos: signal<readonly string[]>(
    SOLO_VER
      ? [PERMISOS.VER_CONFIGURACION_SIMULACION]
      : [PERMISOS.VER_CONFIGURACION_SIMULACION, PERMISOS.MODIFICAR_CONFIGURACION_SIMULACION, PERMISOS.VER_DASHBOARD_SIMULACION],
  ),
  tienePermiso(p: string) {
    return this.permisos().includes(p);
  },
};

export const RUTAS_VERIFICACION: Routes = [
  {
    path: '__verificacion/configuracion',
    providers: [
      { provide: SimulacionApi, useValue: new ApiFalsa() },
      { provide: SesionStore, useValue: sesion },
      { provide: DirectorioUsuarios, useValue: { cargar: () => undefined, nombre: () => null } },
    ],
    loadComponent: () =>
      import('../features/panels/simulacion/pages/configuracion/configuracion').then((m) => m.SimulacionConfiguracion),
  },
];
