/**
 * Cálculos de envíos y operarios de Santa Anita (funciones puras, sin estado).
 * Los usan Registro (al validar el Excel) y el Dashboard.
 *
 * - Capacidad = operarios × productividad (bultos por hora de cada operario).
 *   Los operarios trabajan en las rampas: todos los bultos de un cluster pasan
 *   por sus rampas.
 * - Tiempo de un envío = bultos ÷ capacidad.
 * - Operarios necesarios para una ventana = bultos ÷ (productividad × horas de la ventana),
 *   redondeado hacia arriba.
 */

/** Una fila de la tabla de envíos. */
export interface FilaEnvio {
  /** 1, 2, 3… */
  envio: number;
  bultos: number;
  /** Parte del total que trae este envío (0..100). */
  porcentaje: number;
  /** Avance acumulado hasta este envío (0..100). */
  acumulado: number;
  /** Minutos para procesar el envío con la capacidad dada (null sin capacidad). */
  minutos: number | null;
  /** Operarios para hacerlo dentro de la ventana (null sin ventana). */
  operarios: number | null;
}

/** Porcentaje con un decimal; 0 si el total es 0. */
export function parte(valor: number, total: number): number { // jus:ok conteos de bultos, no importes
  return total > 0 ? Math.round((valor / total) * 1000) / 10 : 0;
}

/** Minutos para procesar `bultos` con `capacidadHora` bultos por hora. */
export function minutosPara(bultos: number, capacidadHora: number): number | null {
  return capacidadHora > 0 ? (bultos / capacidadHora) * 60 : null;
}

/** Operarios para procesar `bultos` en `ventanaMin` minutos a `bultosHora` por operario. */
export function operariosPara(bultos: number, bultosHora: number, ventanaMin: number): number | null {
  if (bultosHora <= 0 || ventanaMin <= 0) {
    return null;
  }
  return bultos > 0 ? Math.ceil(bultos / (bultosHora * (ventanaMin / 60))) : 0;
}

/**
 * Tabla de envíos: bultos, % del total, avance acumulado, tiempo con
 * `operarios × bultosHora` y operarios necesarios para `ventanaMin` (si se da).
 */
export function tablaEnvios(
  bultosPorEnvio: readonly number[],
  operarios: number,
  bultosHora: number,
  ventanaMin: number | null = null,
): FilaEnvio[] {
  const total = bultosPorEnvio.reduce((s, b) => s + b, 0);
  const capacidad = operarios * bultosHora;
  let acumulado = 0;
  return bultosPorEnvio.map((bultos, i) => {
    acumulado += bultos;
    return {
      envio: i + 1,
      bultos,
      porcentaje: parte(bultos, total),
      acumulado: parte(acumulado, total),
      minutos: minutosPara(bultos, capacidad),
      operarios: ventanaMin === null ? null : operariosPara(bultos, bultosHora, ventanaMin),
    };
  });
}

/** Avance acumulado (0..100) de cada envío para una serie de bultos. */
export function avanceAcumulado(bultosPorEnvio: readonly number[]): number[] {
  const total = bultosPorEnvio.reduce((s, b) => s + b, 0);
  let acumulado = 0;
  return bultosPorEnvio.map((b) => {
    acumulado += b;
    return parte(acumulado, total);
  });
}

/** «5 h 33 min», «48 min», «—» si no hay dato. */
export function duracion(minutos: number | null): string {
  if (minutos === null || !Number.isFinite(minutos)) {
    return '—';
  }
  if (minutos > 0 && minutos < 1) {
    return '< 1 min';
  }
  const total = Math.round(minutos);
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h > 0 ? `${h} h ${String(m).padStart(2, '0')} min` : `${m} min`;
}

/** «45,5 %» con un decimal. */
export function porciento(valor: number): string {
  return `${valor.toLocaleString('es-PE', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} %`;
}
