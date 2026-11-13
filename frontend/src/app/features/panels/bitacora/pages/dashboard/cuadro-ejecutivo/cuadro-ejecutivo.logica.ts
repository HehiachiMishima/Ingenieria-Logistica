import { duracion, entero, porcentaje } from '../../../../../../shared/ui/charts/formato';
import { FilaEjecutiva } from '../../../data/analitica.model';

/**
 * Lógica pura del cuadro ejecutivo: celdas con intensidad por columna (cada
 * columna tiene su unidad), pico de cada columna y totales. Sin estado.
 */

export type ClaveCelda = 'casos' | 'horas' | 'mttr' | 'maximo';

export interface CeldaCuadro {
  clave: ClaveCelda;
  nombre: string;
  texto: string;
  /** 0..1 respecto al máximo de su columna. */
  intensidad: number;
  pico: boolean;
}

export interface FilaCuadro {
  id: number;
  etiqueta: string;
  segundos: number;
  celdas: CeldaCuadro[];
  pctHoras: string;
}

const COLUMNAS: { clave: ClaveCelda; nombre: string; valor: (f: FilaEjecutiva) => number; texto: (v: number) => string }[] = [
  { clave: 'casos', nombre: 'casos', valor: (f) => f.total, texto: entero },
  { clave: 'horas', nombre: 'horas', valor: (f) => f.segundos, texto: horas },
  { clave: 'mttr', nombre: 'MTTR', valor: (f) => f.mttrSegundos, texto: duracion },
  { clave: 'maximo', nombre: 'caso más largo', valor: (f) => f.maxSegundos, texto: duracion },
];

/** Segundos → «6,2 h» (menos de 10 h) o «63 h». */
export function horas(segundos: number): string {
  if (!segundos || segundos <= 0) {
    return '0 h';
  }
  const h = segundos / 3600;
  const texto = h < 10 ? h.toLocaleString('es-PE', { maximumFractionDigits: 1 }) : entero(Math.round(h));
  return `${texto} h`;
}

/** Filas visibles (las `maximo` primeras); porcentajes y picos se calculan sobre todas. */
export function filasCuadro(datos: readonly FilaEjecutiva[], maximo = Infinity): FilaCuadro[] {
  const totalSegundos = datos.reduce((t, f) => t + f.segundos, 0);
  const maximos = COLUMNAS.map((c) => Math.max(0, ...datos.map(c.valor)));
  return datos.slice(0, maximo).map((f) => ({
    id: f.id,
    etiqueta: f.etiqueta,
    segundos: f.segundos,
    celdas: COLUMNAS.map((c, j) => {
      const v = c.valor(f);
      return {
        clave: c.clave,
        nombre: c.nombre,
        texto: c.texto(v),
        intensidad: maximos[j] > 0 ? v / maximos[j] : 0,
        pico: v > 0 && v === maximos[j],
      };
    }),
    pctHoras: porcentaje(totalSegundos > 0 ? f.segundos / totalSegundos : 0),
  }));
}

export function totalesCuadro(datos: readonly FilaEjecutiva[], mttrGeneral: number) {
  const segundos = datos.reduce((t, f) => t + f.segundos, 0);
  return {
    casos: entero(datos.reduce((t, f) => t + f.total, 0)),
    horas: horas(segundos),
    mttr: duracion(mttrGeneral),
    maximo: duracion(Math.max(0, ...datos.map((f) => f.maxSegundos))),
  };
}
