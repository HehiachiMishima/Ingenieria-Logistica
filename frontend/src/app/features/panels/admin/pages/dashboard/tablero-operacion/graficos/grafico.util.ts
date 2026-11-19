import { DestroyRef, ElementRef, Signal, afterNextRender, inject, signal } from '@angular/core';
import { porcentaje, variacion } from '../../../../../../../shared/ui/charts/formato';
import { META_DISPONIBILIDAD, META_P95_MS, Nivel } from '../../../../ui/observabilidad.util';

/** Tasa de error a partir de la cual el período pide atención (la misma de cada API). */
export const LIMITE_ERROR = 0.02;
/** Tasa de error crítica (la misma de cada API). */
export const CRITICO_ERROR = 0.05;

export const TEXTO_NIVEL: Record<Nivel, string> = { ok: 'En meta', aviso: 'Atención', riesgo: 'Crítico' };
export const ICONO_NIVEL: Record<Nivel, string> = { ok: 'checkCircleFilled', aviso: 'alertTriangle', riesgo: 'xCircle' };

export function nivelDisponibilidad(valor: number): Nivel {
  return valor >= META_DISPONIBILIDAD ? 'ok' : valor >= 0.99 ? 'aviso' : 'riesgo';
}

export function nivelP95(ms: number): Nivel {
  return ms <= META_P95_MS ? 'ok' : ms <= META_P95_MS * 1.5 ? 'aviso' : 'riesgo';
}

export function nivelError(tasa: number): Nivel {
  return tasa <= LIMITE_ERROR ? 'ok' : tasa <= CRITICO_ERROR ? 'aviso' : 'riesgo';
}

/** Cambio frente al período anterior: el texto y si es una buena noticia. */
export interface Delta {
  texto: string;
  bueno: boolean;
}

const PUNTOS = new Intl.NumberFormat('es-PE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** Variación relativa («▲ 12 %»); null sin período anterior. */
export function deltaRelativo(actual: number, anterior: number | null | undefined, subirEsMalo: boolean): Delta | null {
  const v = variacion(actual, anterior);
  if (v === null) {
    return null;
  }
  return { texto: `${v >= 0 ? '▲' : '▼'} ${porcentaje(Math.abs(v))}`, bueno: subirEsMalo ? v <= 0 : v >= 0 };
}

/** Diferencia en puntos porcentuales entre dos fracciones («▲ 0,2 pp»). */
export function deltaPuntos(actual: number, anterior: number | null, subirEsMalo: boolean): Delta | null {
  if (anterior === null || !Number.isFinite(anterior)) {
    return null;
  }
  const pp = (actual - anterior) * 100;
  return { texto: `${pp >= 0 ? '▲' : '▼'} ${PUNTOS.format(Math.abs(pp))} pp`, bueno: subirEsMalo ? pp <= 0 : pp >= 0 };
}

const DIAS = ['dom', 'lun', 'mar', 'mié', 'jue', 'vie', 'sáb'];
const MESES = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];

/** «2026-11-03» (día de negocio, sin zona) → fecha local sin correr el día. */
function aFecha(periodo: string): Date | null {
  const [y, m, d] = periodo.slice(0, 10).split('-').map(Number);
  return y && m && d ? new Date(y, m - 1, d) : null;
}

/** Etiqueta corta del eje: «03 nov» o, por hora, «14 h». */
export function etiquetaEje(periodo: string): string {
  const hora = periodo.split('T')[1];
  if (hora) {
    return `${Number(hora.slice(0, 2))} h`;
  }
  const f = aFecha(periodo);
  return f ? `${`${f.getDate()}`.padStart(2, '0')} ${MESES[f.getMonth()]}` : periodo;
}

/** Etiqueta larga para el detalle: «lun 03 nov» o «lun 03 nov · 14 h». */
export function etiquetaLarga(periodo: string): string {
  const f = aFecha(periodo);
  if (!f) {
    return periodo;
  }
  const hora = periodo.split('T')[1];
  const dia = `${DIAS[f.getDay()]} ${`${f.getDate()}`.padStart(2, '0')} ${MESES[f.getMonth()]}`;
  return hora ? `${dia} · ${Number(hora.slice(0, 2))} h` : dia;
}

/**
 * Máximo «redondo» del eje para un valor, con un 8 % de aire: múltiplo par de un quinto de su
 * orden de magnitud, así la marca del medio también es redonda (37 → 40, 940 → 1 200,
 * 11 800 → 16 000). Por debajo de 10 (conteos chicos) queda en un entero par (3 → 4).
 */
export function maximoRedondo(valor: number): number {
  const v = Math.max(valor, 1) * 1.08;
  if (v < 10) {
    const entero = Math.ceil(v);
    return entero % 2 ? entero + 1 : entero;
  }
  const paso = 10 ** Math.floor(Math.log10(v)) / 5;
  let pasos = Math.ceil(v / paso);
  if (pasos % 2) {
    pasos++;
  }
  return pasos * paso;
}

/** Columna con el extremo de datos redondeado (4 px) y recta en la base. */
export function columnaRedondeada(x: number, arriba: number, ancho: number, base: number): string {
  const r = Math.min(4, ancho / 2, base - arriba);
  return `M${x},${base}V${arriba + r}Q${x},${arriba} ${x + r},${arriba}H${x + ancho - r}Q${x + ancho},${arriba} ${x + ancho},${arriba + r}V${base}Z`;
}

/**
 * Ancho real del componente (px), para dibujar el SVG a su tamaño: los textos no se estiran
 * ni se achican y los círculos siguen siendo círculos. 0 hasta la primera medición.
 */
export function medirAncho(): Signal<number> {
  const host = inject<ElementRef<HTMLElement>>(ElementRef);
  const destroyRef = inject(DestroyRef);
  const ancho = signal(0);
  afterNextRender(() => {
    const observador = new ResizeObserver((entradas) => ancho.set(Math.floor(entradas[0].contentRect.width)));
    observador.observe(host.nativeElement);
    destroyRef.onDestroy(() => observador.disconnect());
  });
  return ancho.asReadonly();
}

const DECIMAL = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 1 });

/**
 * Marca compacta de un eje: «8 k», «16 k», «1.6 M». Propia porque el formato compacto del
 * navegador mezcla «8 K» y «16 k» en un mismo eje.
 */
export function compactoEje(valor: number): string {
  const a = Math.abs(valor);
  if (a >= 1e6) {
    return `${DECIMAL.format(valor / 1e6)} M`;
  }
  return a >= 1e3 ? `${DECIMAL.format(valor / 1e3)} k` : DECIMAL.format(valor);
}
