/** Formato numérico compartido por los gráficos y dashboards (es-PE). */

const ENTERO = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 0 });
const COMPACTO = new Intl.NumberFormat('es-PE', { notation: 'compact', maximumFractionDigits: 1 });
const PORCENTAJE = new Intl.NumberFormat('es-PE', { style: 'percent', maximumFractionDigits: 1 });

export function entero(valor: number): string {
  return ENTERO.format(valor);
}

/** 1 234 → «1,2 mil» cuando el número es grande (ejes y etiquetas compactas). */
export function compacto(valor: number): string {
  return Math.abs(valor) >= 10_000 ? COMPACTO.format(valor) : ENTERO.format(valor);
}

export function porcentaje(fraccion: number): string {
  return PORCENTAJE.format(Number.isFinite(fraccion) ? fraccion : 0);
}

/** Segundos → «2 h 05 m», «35 m» o «—». */
export function duracion(segundos: number): string {
  if (!segundos || segundos <= 0) {
    return '—';
  }
  const total = Math.round(segundos / 60);
  const horas = Math.floor(total / 60);
  const minutos = total % 60;
  return horas > 0 ? `${horas} h ${`${minutos}`.padStart(2, '0')} m` : `${minutos} m`;
}

const MESES = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];

/** «2026-10» → «oct 26»; «2026-10-07» → «07 oct». */
export function periodoCorto(periodo: string): string {
  const [anio, mes, dia] = periodo.split('-');
  const nombre = MESES[Number(mes) - 1] ?? mes;
  return dia ? `${dia} ${nombre}` : `${nombre} ${anio?.slice(2) ?? ''}`;
}

/** Variación relativa actual vs anterior (null si no hay base de comparación). */
export function variacion(actual: number, anterior: number | null | undefined): number | null {
  if (anterior === null || anterior === undefined || anterior === 0) {
    return null;
  }
  return (actual - anterior) / anterior;
}

/** Paleta categórica del tema (tokens CSS) en orden fijo. */
export const SERIES = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
  'var(--chart-6)',
];
export const SERIE_OTROS = 'var(--chart-otros)';
