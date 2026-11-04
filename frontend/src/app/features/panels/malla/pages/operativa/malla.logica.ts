import { CentroMalla, FlujoMalla, OlaMalla, Proceso, TurnoMalla } from '../../data/malla.model';
import {
  MINUTOS_DIA,
  enVentana,
  faltan,
  hora,
  minutos,
  proceso as datosProceso,
} from '../../malla-formato';

/**
 * Lógica pura del tablero de la malla: qué está en curso, qué viene, qué
 * hace cada proceso y cómo se dibuja la línea del día. Las horas son minutos
 * del día y las ventanas pueden cruzar la medianoche.
 */

/** Lo que se está mirando: un turno o una hora del día; null = ahora. */
export interface Ventana {
  etiqueta: string;
  sub: string;
  desde: number;
  hasta: number;
  turno: TurnoMalla | null;
}

export interface Hito {
  minuto: number;
  proceso: Proceso;
  texto: string;
}

export function ventanaDeTurno(turno: TurnoMalla): Ventana {
  return {
    etiqueta: turno.codigo,
    sub: `${turno.inicio}–${turno.fin}`,
    desde: minutos(turno.inicio) ?? 0,
    hasta: minutos(turno.fin) ?? 0,
    turno,
  };
}

export function ventanaDeHora(h: number): Ventana {
  const desde = h * 60;
  return {
    etiqueta: hora(desde),
    sub: `${hora(desde)}–${hora(desde + 60)}`,
    desde,
    hasta: (desde + 60) % MINUTOS_DIA,
    turno: null,
  };
}

/** Tramos [a, b) de una ventana, partida en dos si cruza la medianoche. */
function tramos(a: number | null, b: number | null): [number, number][] {
  if (a === null || b === null) {
    return [];
  }
  if (a === b) {
    return [[0, MINUTOS_DIA]];
  }
  return b > a
    ? [[a, b]]
    : [
        [a, MINUTOS_DIA],
        [0, b],
      ];
}

/** Algo de inicio a fin (o un hito puntual si no tiene fin) cae dentro de la ventana. */
function tocaVentana(v: Ventana, inicio: string | null, fin: string | null): boolean {
  const x = minutos(inicio);
  if (x === null) {
    return false;
  }
  const y = minutos(fin);
  const propios: [number, number][] = y === null ? [[x, x + 1]] : tramos(x, y);
  return tramos(v.desde, v.hasta).some(([a, b]) => propios.some(([c, d]) => a < d && c < b));
}

export function turnosEn(c: CentroMalla, t: number): TurnoMalla[] {
  return c.turnos.filter((turno) => enVentana(t, turno.inicio, turno.fin));
}

export function turnosDeVentana(c: CentroMalla, v: Ventana): TurnoMalla[] {
  return v.turno
    ? [v.turno]
    : c.turnos.filter((turno) => enVentana(v.desde, turno.inicio, turno.fin));
}

export function personasDeTurno(c: CentroMalla, codigo: string): number {
  return c.dotacion
    .filter((d) => d.turno.toUpperCase() === codigo.toUpperCase())
    .reduce((total, d) => total + (d.personas ?? 0), 0);
}

export function personasDeProceso(c: CentroMalla, p: Proceso): number {
  return c.dotacion
    .filter((d) => d.proceso === p)
    .reduce((total, d) => total + (d.personas ?? 0), 0);
}

export function pasos(c: CentroMalla, p: Proceso): FlujoMalla[] {
  return c.flujos.filter((f) => f.proceso === p);
}

export interface FilaDotacion {
  etiqueta: string;
  procesos: string;
  planificada: number | null;
  real: number | null;
  diferencia: number | null;
}

/**
 * La dotación real se declara por flujo y la planificada por proceso:
 * Inbound = Recepción + Almacenamiento, Outbound = Reabastecimiento +
 * Picking y Despacho = Despacho. Sin personas planificadas queda en null.
 */
export function dotacionComparada(c: CentroMalla): {
  filas: FilaDotacion[];
  semana: string | null;
} {
  const fila = (
    etiqueta: string,
    procesos: Proceso[],
    real: number | null,
    detalle = procesos.map((p) => datosProceso(p).nombre).join(' + '),
  ): FilaDotacion => {
    const planificada = procesos.reduce((total, p) => total + personasDeProceso(c, p), 0) || null;
    return {
      etiqueta,
      procesos: detalle,
      planificada,
      real,
      diferencia: planificada === null || real === null ? null : real - planificada,
    };
  };
  const real = c.dotacionReal;
  return {
    filas: [
      fila('Inbound', ['REC', 'ALM'], real.inbound),
      fila('Outbound', ['REA', 'OLA'], real.outbound),
      fila('Despacho', ['DES'], real.despacho),
      fila('Total', ['REC', 'ALM', 'REA', 'OLA', 'DES'], real.total, 'Todos los procesos'),
    ],
    semana: real.semana,
  };
}

/**
 * Ventana de un proceso: el arco del día más corto que cubre todos sus
 * pasos (en picking, de cada lanzamiento a su cierre). No depende del orden
 * de los pasos, y un paso que cruza la medianoche o un hito previo al primer
 * turno (06:30) quedan donde corresponde.
 */
export function ventanaDeProceso(
  c: CentroMalla,
  p: Proceso,
): { inicio: string; fin: string } | null {
  const tramos: [number, number][] =
    p === 'OLA'
      ? c.olas.flatMap((o) => {
          const a = minutos(o.lanzamiento);
          return a === null ? [] : [[a, faltan(a, minutos(o.cierrePicking) ?? a)]];
        })
      : pasos(c, p).flatMap((f) => {
          const a = minutos(f.inicio);
          return a === null ? [] : [[a, faltan(a, minutos(f.fin) ?? a)]];
        });
  if (tramos.length === 0) {
    return null;
  }
  // Se prueba arrancar en cada inicio y se queda el que cubre todo en menos tiempo.
  const mejor = tramos
    .map(([origen]) => ({
      origen,
      largo: Math.max(...tramos.map(([a, largo]) => faltan(origen, a) + largo)),
    }))
    .reduce((x, y) => (y.largo < x.largo ? y : x));
  return { inicio: hora(mejor.origen), fin: hora(mejor.origen + mejor.largo) };
}

/** Pasos con inicio y fin (u olas, en picking) que están ocurriendo a la hora t. */
export function enCurso(c: CentroMalla, p: Proceso, t: number): (FlujoMalla | OlaMalla)[] {
  if (p === 'OLA') {
    return c.olas.filter((o) => enVentana(t, o.lanzamiento, o.cierrePicking));
  }
  return pasos(c, p).filter((f) => f.fin && enVentana(t, f.inicio, f.fin));
}

export function activosEnVentana(
  c: CentroMalla,
  p: Proceso,
  v: Ventana,
): (FlujoMalla | OlaMalla)[] {
  if (p === 'OLA') {
    return c.olas.filter((o) => tocaVentana(v, o.lanzamiento, o.cierrePicking));
  }
  return pasos(c, p).filter((f) => tocaVentana(v, f.inicio, f.fin));
}

/** Texto corto de lo activo en un proceso: clusters en picking o las actividades. */
export function textoActivos(p: Proceso, items: (FlujoMalla | OlaMalla)[]): string {
  if (p === 'OLA') {
    return `Picking ${[...new Set((items as OlaMalla[]).map((o) => `C${o.cluster}`))].join(' ')}`;
  }
  return (items as FlujoMalla[]).map((f) => f.actividad).join(' · ');
}

const HITOS_OLA: [keyof OlaMalla, string][] = [
  ['caidaPedidos', 'Caída de pedidos'],
  ['lanzamiento', 'Lanzamiento de olas'],
  ['cierrePicking', 'Cierre de picking'],
  ['despacho', 'Despacho'],
];

/**
 * Hitos del día ordenados desde t: momentos de las olas (agrupando clusters
 * que coinciden) y el inicio de cada paso del resto de procesos.
 */
export function hitos(c: CentroMalla, t: number, v: Ventana | null = null): Hito[] {
  const todos: {
    minuto: number;
    proceso: Proceso;
    grupo: string;
    cluster?: string;
    ola?: string;
  }[] = [];
  c.olas.forEach((o) =>
    HITOS_OLA.forEach(([campo, nombre]) => {
      const valor = o[campo] as string | null;
      const m = minutos(valor);
      if (m !== null && (!v || tocaVentana(v, valor, null))) {
        todos.push({ minuto: m, proceso: 'OLA', grupo: nombre, cluster: o.cluster, ola: o.nombre });
      }
    }),
  );
  (['REC', 'ALM', 'REA', 'DES'] as Proceso[]).forEach((p) =>
    pasos(c, p).forEach((f) => {
      const m = minutos(f.inicio);
      if (m !== null && (!v || tocaVentana(v, f.inicio, null))) {
        todos.push({ minuto: m, proceso: p, grupo: f.actividad });
      }
    }),
  );
  const desde = v ? v.desde : t;
  const grupos: {
    minuto: number;
    proceso: Proceso;
    grupo: string;
    clusters: string[];
    ola?: string;
  }[] = [];
  todos
    .filter((h) => v || faltan(desde, h.minuto) > 0)
    .sort((a, b) => faltan(desde, a.minuto) - faltan(desde, b.minuto))
    .forEach((h) => {
      const igual = grupos.find(
        (g) => g.minuto === h.minuto && g.grupo === h.grupo && g.ola === h.ola,
      );
      if (igual) {
        if (h.cluster) {
          igual.clusters.push(h.cluster);
        }
      } else {
        grupos.push({ ...h, clusters: h.cluster ? [h.cluster] : [] });
      }
    });
  return grupos.map((g) => ({
    minuto: g.minuto,
    proceso: g.proceso,
    texto:
      g.grupo +
      (g.clusters.length
        ? ` · ${
            g.clusters.length > 2
              ? `C${g.clusters[0]}–C${g.clusters[g.clusters.length - 1]}`
              : g.clusters.map((cl) => `C${cl}`).join(', ')
          }`
        : '') +
      (g.ola ? ` · ${g.ola}` : ''),
  }));
}

/** El siguiente momento del día y todo lo que pasa en él. */
export function proximo(
  c: CentroMalla,
  t: number,
): { minuto: number; faltan: number; textos: string[] } | null {
  const lista = hitos(c, t);
  if (lista.length === 0) {
    return null;
  }
  const minuto = lista[0].minuto;
  return {
    minuto,
    faltan: faltan(t, minuto),
    textos: lista.filter((h) => h.minuto === minuto).map((h) => h.texto),
  };
}

/** Cuántos turnos, olas y pasos del proceso siguen por confirmar. */
export function pendientes(c: CentroMalla, p: Proceso): number {
  const pasosPendientes = pasos(c, p).filter((f) => !f.confirmado).length;
  return p === 'OLA'
    ? pasosPendientes + c.olas.filter((o) => !o.confirmado).length
    : pasosPendientes;
}

// ---------------- Línea del día ----------------

export interface Barra {
  izquierda: number;
  ancho: number;
  color: string;
  titulo: string;
  texto: string | null;
  pendiente: boolean;
  il: boolean;
}

export interface Marca {
  posicion: number;
  color: string;
  titulo: string;
  tipo: 'pedido' | 'despacho' | 'refrigerio' | 'il' | 'ops';
}

export interface FilaLinea {
  etiqueta: string;
  color: string | null;
  proceso: Proceso | null;
  barras: Barra[];
  marcas: Marca[];
}

export interface LineaDia {
  horas: { posicion: number; texto: string }[];
  filas: FilaLinea[];
  /** Posición de la hora actual (vista «ahora») o tramos de la ventana elegida. */
  ahora: { posicion: number; texto: string } | null;
  ventana: { izquierda: number; ancho: number }[];
}

/**
 * Las 24 h desde el inicio del primer turno: una fila por turno, por proceso
 * (con sus pasos) y por cluster (con sus olas). Posiciones en % del día.
 */
export function lineaDelDia(
  c: CentroMalla,
  t: number,
  v: Ventana | null,
  compacta: boolean,
): LineaDia {
  const t0 = minutos(c.turnos[0]?.inicio) ?? 7 * 60;
  const relativo = (m: number) => faltan(t0, m);
  const pct = (m: number) => (m / MINUTOS_DIA) * 100;
  const barras = (
    inicio: string | null,
    fin: string | null,
    color: string,
    titulo: string,
    texto: string | null,
    pendiente: boolean,
    il = false,
  ): Barra[] => {
    const x = minutos(inicio);
    const y = minutos(fin);
    if (x === null || y === null) {
      return [];
    }
    const s = relativo(x);
    let e = relativo(y);
    if (e <= s) {
      e += MINUTOS_DIA;
    }
    const partes: [number, number][] =
      e > MINUTOS_DIA
        ? [
            [s, MINUTOS_DIA],
            [0, e - MINUTOS_DIA],
          ]
        : [[s, e]];
    return partes.map(([a, b]) => ({
      izquierda: pct(a),
      ancho: pct(b - a),
      color,
      titulo,
      texto: !compacta && b - a >= 110 ? texto : null,
      pendiente,
      il,
    }));
  };
  const marca = (
    valor: string | null,
    color: string,
    titulo: string,
    tipo: Marca['tipo'],
  ): Marca[] => {
    const m = minutos(valor);
    return m === null ? [] : [{ posicion: pct(relativo(m)), color, titulo, tipo }];
  };
  const porConfirmar = (ok: boolean) => (ok ? '' : ' · por confirmar');

  const filas: FilaLinea[] = c.turnos.map((turno) => ({
    etiqueta: turno.codigo,
    color: null,
    proceso: null,
    barras: barras(
      turno.inicio,
      turno.fin,
      'var(--border-strong, var(--text-3))',
      `${turno.nombre} · ${turno.inicio}–${turno.fin}${turno.refrigerio ? ` · ${turno.tipoRefrigerio ?? 'Refrigerio'} ${turno.refrigerio}` : ''}${turno.nota ? ` · ${turno.nota}` : ''}`,
      turno.nombre,
      !turno.confirmado,
    ),
    marcas: marca(
      turno.refrigerio,
      'var(--text-2)',
      `${turno.nombre} · ${turno.tipoRefrigerio ?? 'Refrigerio'} ${turno.refrigerio}`,
      'refrigerio',
    ),
  }));
  const filaProceso = (p: Proceso) => {
    const info = datosProceso(p);
    const fila: FilaLinea = {
      etiqueta: info.nombre,
      color: info.color,
      proceso: p,
      barras: [],
      marcas: [],
    };
    pasos(c, p).forEach((f) => {
      const titulo = `${f.responsable === 'IL' ? 'Ingeniería Logística' : 'Operaciones'} · ${f.actividad} · ${f.inicio}${f.fin ? `–${f.fin}` : ''}${porConfirmar(f.confirmado)}`;
      if (f.fin) {
        fila.barras.push(
          ...barras(
            f.inicio,
            f.fin,
            info.color,
            titulo,
            f.actividad,
            !f.confirmado,
            f.responsable === 'IL',
          ),
        );
      } else {
        fila.marcas.push(
          ...marca(f.inicio, info.color, titulo, f.responsable === 'IL' ? 'il' : 'ops'),
        );
      }
    });
    return fila;
  };
  (['REC', 'ALM', 'REA'] as Proceso[]).forEach((p) => filas.push(filaProceso(p)));
  const picking = datosProceso('OLA');
  const despacho = datosProceso('DES');
  [...new Set(c.olas.map((o) => o.cluster))]
    .sort((a, b) => (Number(a) || 0) - (Number(b) || 0) || a.localeCompare(b))
    .forEach((cluster) => {
      const fila: FilaLinea = {
        etiqueta: `Cluster ${cluster}`,
        color: picking.color,
        proceso: 'OLA',
        barras: [],
        marcas: [],
      };
      c.olas
        .filter((o) => o.cluster === cluster)
        .forEach((o) => {
          const base = `Cluster ${o.cluster} · ${o.nombre}`;
          fila.barras.push(
            ...barras(
              o.lanzamiento,
              o.cierrePicking,
              picking.color,
              `${base} · picking ${o.lanzamiento}–${o.cierrePicking}${porConfirmar(o.confirmado)}`,
              o.nombre,
              !o.confirmado,
            ),
          );
          fila.marcas.push(
            ...marca(
              o.caidaPedidos,
              'var(--text-2)',
              `${base} · caída de pedidos ${o.caidaPedidos}`,
              'pedido',
            ),
            ...marca(o.despacho, despacho.color, `${base} · despacho ${o.despacho}`, 'despacho'),
          );
        });
      filas.push(fila);
    });
  filas.push(filaProceso('DES'));

  const horas: { posicion: number; texto: string }[] = [];
  for (let i = 0; i <= 24; i += compacta ? 4 : 2) {
    horas.push({ posicion: pct(i * 60), texto: hora(t0 + i * 60) });
  }
  const ventana = v
    ? tramos(v.desde, v.hasta).flatMap(([a, b]) => {
        const x = relativo(a);
        const y = x + (b - a);
        return y > MINUTOS_DIA
          ? [
              { izquierda: pct(x), ancho: pct(MINUTOS_DIA - x) },
              { izquierda: 0, ancho: pct(y - MINUTOS_DIA) },
            ]
          : [{ izquierda: pct(x), ancho: pct(y - x) }];
      })
    : [];
  return {
    horas,
    filas,
    ahora: v ? null : { posicion: pct(relativo(t)), texto: hora(t) },
    ventana,
  };
}
