// TEMPORAL: verificación del Registro v2 con una API falsa en memoria (sin backend). Se borra al terminar.
import { Routes } from '@angular/router';
import { Observable, delay, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { DiccionarioApi } from '../features/panels/simulacion/data/diccionario.api';
import {
  CambioRutas,
  ClusterDiccionario,
  DetalleRutaDiccionario,
  Diccionario,
  FrecuenciaDiccionario,
  LocalDiccionario,
  RutaDiccionario,
  RutaDiccionarioRequest,
  TipoRutaDiccionario,
  UbicacionRuta,
} from '../features/panels/simulacion/data/diccionario.model';

const q = new URLSearchParams(location.search);
const CASO = q.get('caso') ?? 'normal';
const N = Number(q.get('n') ?? '0');
document.documentElement.dataset['theme'] = q.get('tema') ?? 'light';
const ESPERA = Number(q.get('espera') ?? '120');

let ids = 0;
let clusters: ClusterDiccionario[] = [];
const rutas = new Map<number, { r: Omit<RutaDiccionario, 'bultos' | 'unidades' | 'lineas' | 'locales' | 'codigos'>; l: LocalDiccionario[] }>();

function agregar(codigo: string, tipo: TipoRutaDiccionario | null, clusterId: number | null, frecuencia: FrecuenciaDiccionario | null, grupo: string | null, l: LocalDiccionario[]) {
  const id = ++ids;
  rutas.set(id, { r: { id, codigoRuta: codigo, tipo, clusterId, frecuencia, grupo }, l });
}
const L = (cod: string, b: number, u: number, li: number, filas = 1): LocalDiccionario => ({ cod, filas, bultos: b, unidades: u, lineas: li });

function sembrar() {
  if (CASO === 'vacio') {
    return;
  }
  clusters = [1, 2, 3].map((o) => ({ id: 100 + o, orden: o, nombre: 'CL' + o }));
  if (N > 0) {
    for (let i = 0; i < N; i++) {
      const cl = i % 4 === 0 ? null : 101 + (i % 3);
      agregar(String(1000 + i), cl === null ? null : i % 5 === 0 ? 'B2B' : 'B2C', cl, cl && i % 2 ? 'LXV' : null, cl && i % 3 === 0 ? String(1 + (i % 4)) : null, [L('L' + i, 50 + (i % 300), 900 + i, 40 + (i % 50))]);
    }
    return;
  }
  agregar('2002', null, null, null, null, [L('7875', 122.46, 5878.11, 221.91)]);
  agregar('2015', null, null, null, null, [L('F045', 100, 5000, 180), L('7880', 22.46, 878.11, 41.91), L('F112', 268, 2301, 151.5, 2)]);
  agregar('2040', null, null, null, null, [L('F087', 301.2, 2045, 140)]);
  agregar('3102', 'B2B', null, null, null, [L('F203', 215, 1702, 116)]);
  agregar('1001', 'B2C', 101, 'LXV', '1', [L('F019', 412, 1498, 102)]);
  agregar('1003', 'B2C', 101, 'LXV', '2', [L('F156', 280, 1410, 96)]);
  agregar('1007', 'B2B', 101, 'MJS', '1', [L('F231', 133, 1288, 88)]);
  agregar('1012', 'B2C', 101, 'DIARIA', null, [L('F074', 98, 1190, 81)]);
  agregar('1105', 'B2C', 102, 'MJS', 'A', [L('F190', 356, 1036, 70.5)]);
  agregar('1108', 'B2B', 102, null, null, [L('F066', 77, 930, 63)]);
  if (CASO === 'extremo') {
    agregar('R-30135-XYZ-SAN-JUAN-DE-LURIGANCHO-PROCERES-12345678', 'B2C', 103, 'DIARIA', 'GRUPO-LARGO-DE-CINCUENTA', [
      L('LOCAL-SAN-JUAN-DE-LURIGANCHO-AV-PROCERES-DE-LA-INDEPENDENCIA-1234-INTERIOR-B-' + 'X'.repeat(20), 9999999999.99, 9999999999.99, 9999999999.99, 12),
    ]);
    for (let i = 0; i < 300; i++) {
      agregar('9' + String(i).padStart(4, '0'), null, null, null, null, [L('C' + i, 5000 - i, 40000 - i, 3000 - i)]);
    }
  }
}
sembrar();

function vista(id: number): RutaDiccionario {
  const { r, l } = rutas.get(id)!;
  const s = (k: 'bultos' | 'unidades' | 'lineas') => Math.round(l.reduce((t, x) => t + x[k], 0) * 100) / 100;
  return { ...r, bultos: s('bultos'), unidades: s('unidades'), lineas: s('lineas'), locales: l.length, codigos: l.map((x) => x.cod) };
}
const responder = <T>(valor: T): Observable<T> => of(valor).pipe(delay(ESPERA));
const fallar = (estado: number, mensaje: string) =>
  throwError(() => new HttpErrorResponse({ status: estado, error: { mensaje } })).pipe(delay(ESPERA));
const todo = (): Diccionario => ({ clusters: [...clusters], rutas: [...rutas.keys()].map(vista), truncado: false });

function sumarLocales(p: RutaDiccionarioRequest): LocalDiccionario[] {
  const m = new Map<string, LocalDiccionario>();
  for (const x of p.locales) {
    const cod = x.cod.trim().toUpperCase();
    const a = m.get(cod);
    if (a) {
      a.filas++;
      a.bultos += x.bultos;
      a.unidades += x.unidades;
      a.lineas += x.lineas;
    } else {
      m.set(cod, { cod, filas: 1, bultos: x.bultos, unidades: x.unidades, lineas: x.lineas });
    }
  }
  return [...m.values()];
}

class ApiFalsa {
  ver = () => responder(todo());
  detalle = (id: number): Observable<DetalleRutaDiccionario> =>
    rutas.has(id) ? responder({ ruta: vista(id), locales: rutas.get(id)!.l }) : fallar(404, 'No existe');
  crear = (p: RutaDiccionarioRequest) => {
    const codigo = p.codigoRuta.trim().toUpperCase();
    if ([...rutas.values()].some((x) => x.r.codigoRuta === codigo && x.r.tipo === p.tipo)) {
      return fallar(409, `La ruta ${codigo} ${p.tipo} ya existe.`);
    }
    agregar(codigo, p.tipo, null, null, null, sumarLocales(p));
    return responder(vista(ids));
  };
  actualizar = (id: number, p: RutaDiccionarioRequest) => {
    const x = rutas.get(id)!;
    x.r = { ...x.r, codigoRuta: p.codigoRuta.trim().toUpperCase(), tipo: p.tipo };
    x.l = sumarLocales(p);
    return responder(vista(id));
  };
  eliminar = (id: number) => {
    rutas.delete(id);
    return responder(undefined as void);
  };
  mover = (rutaIds: readonly number[], clusterId: number | null): Observable<CambioRutas> => {
    for (const id of rutaIds) {
      const x = rutas.get(id)!;
      x.r = { ...x.r, clusterId, frecuencia: clusterId ? x.r.frecuencia : null, grupo: clusterId ? x.r.grupo : null };
    }
    return responder({ rutas: rutaIds.map(vista), omitidas: 0 });
  };
  private enCluster(rutaIds: readonly number[], cambio: (x: { r: RutaDiccionario }) => void): Observable<CambioRutas> {
    const con = rutaIds.filter((id) => rutas.get(id)!.r.clusterId !== null);
    if (con.length === 0) {
      return fallar(400, 'Esas rutas están en Datos.');
    }
    for (const id of con) {
      cambio(rutas.get(id) as never);
    }
    return responder({ rutas: con.map(vista), omitidas: rutaIds.length - con.length });
  }
  asignarFrecuencia = (rutaIds: readonly number[], frecuencia: FrecuenciaDiccionario) =>
    this.enCluster(rutaIds, (x) => (x.r = { ...x.r, frecuencia }));
  asignarGrupo = (rutaIds: readonly number[], grupo: string) =>
    this.enCluster(rutaIds, (x) => (x.r = { ...x.r, grupo: grupo.trim().toUpperCase() }));
  cambiarTipo = (rutaIds: readonly number[], tipo: TipoRutaDiccionario) => {
    for (const id of rutaIds) {
      const x = rutas.get(id)!;
      x.r = { ...x.r, tipo };
    }
    return responder({ rutas: rutaIds.map(vista), omitidas: 0 });
  };
  restaurar = (lista: readonly UbicacionRuta[]) => {
    for (const u of lista) {
      const x = rutas.get(u.id)!;
      x.r = { ...x.r, clusterId: u.clusterId, frecuencia: u.frecuencia, grupo: u.grupo };
    }
    return responder({ rutas: lista.map((u) => vista(u.id)), omitidas: 0 });
  };
  cambiarClusters = (cantidad: number) => {
    while (clusters.length < cantidad) {
      const o = clusters.length + 1;
      clusters.push({ id: 100 + o, orden: o, nombre: 'CL' + o });
    }
    while (clusters.length > cantidad) {
      const quitado = clusters.pop()!;
      for (const x of rutas.values()) {
        if (x.r.clusterId === quitado.id) {
          x.r = { ...x.r, clusterId: null, frecuencia: null, grupo: null };
        }
      }
    }
    return responder(todo());
  };
}

export const RUTAS_VERIFICACION: Routes = [
  {
    path: '__verificacion/registro-v2',
    providers: [{ provide: DiccionarioApi, useClass: ApiFalsa }],
    loadComponent: () =>
      import('../features/panels/simulacion/pages/registro-v2/registro-v2').then((m) => m.SimulacionRegistroV2),
  },
];
