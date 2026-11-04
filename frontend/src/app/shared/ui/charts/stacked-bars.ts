import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { SERIES, SERIE_OTROS, entero, porcentaje } from './formato';

export interface GrupoApilado {
  etiqueta: string;
  segmentos: { nombre: string; valor: number }[];
}

/** Agrupa filas planas (grupo, segmento, valor) en barras apiladas, en el orden en que aparece cada grupo. */
export function agruparApiladas<T>(
  filas: readonly T[],
  grupo: (fila: T) => string,
  segmento: (fila: T) => { nombre: string; valor: number },
): GrupoApilado[] {
  const grupos = new Map<string, { nombre: string; valor: number }[]>();
  for (const fila of filas) {
    const clave = grupo(fila);
    const lista = grupos.get(clave) ?? [];
    lista.push(segmento(fila));
    grupos.set(clave, lista);
  }
  return [...grupos.entries()].map(([etiqueta, segmentos]) => ({ etiqueta, segmentos }));
}

/**
 * Barras horizontales apiladas: cada fila es un grupo (p. ej. un sistema) y
 * sus segmentos la composición (p. ej. tipos de incidencia). Los segmentos
 * más frecuentes en todo el gráfico reciben color propio; el resto, «Otros».
 * El ancho de cada barra es proporcional al total del grupo.
 */
@Component({
  selector: 'ui-apiladas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ap">
      <ul class="ap__leyenda">
        @for (l of leyenda(); track l.nombre) {
          <li
            [class.ap__leyenda--off]="foco() !== null && foco() !== l.nombre"
            (mouseenter)="foco.set(l.nombre)"
            (mouseleave)="foco.set(null)"
          >
            <span [style.background]="l.color"></span>{{ l.nombre }}
          </li>
        }
      </ul>
      <div class="ap__filas">
        @for (g of filas(); track g.etiqueta; let i = $index) {
          <div class="ap__fila" [style.animation-delay]="i * 50 + 'ms'">
            <div class="ap__top">
              <span class="ap__label" [title]="g.etiqueta">{{ g.etiqueta }}</span>
              <span class="ap__total">{{ g.totalTexto }}</span>
            </div>
            <div class="ap__pista">
              <div class="ap__barra" [style.width.%]="g.pct">
                @for (s of g.segmentos; track s.nombre) {
                  <span
                    class="ap__seg"
                    [class.ap__seg--off]="foco() !== null && foco() !== s.nombre"
                    [style.flex-grow]="s.valor"
                    [style.background]="s.color"
                    [attr.title]="g.etiqueta + ' · ' + s.nombre + ': ' + s.valor + ' (' + s.pct + ')'"
                  ></span>
                }
              </div>
            </div>
            @if (g.dominante) {
              <span class="ap__dom">Principal: <strong>{{ g.dominante }}</strong></span>
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .ap__leyenda { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-bottom: var(--space-4); font-size: var(--fs-xs); font-weight: 600; color: var(--text-2); }
    .ap__leyenda li { display: inline-flex; align-items: center; gap: 6px; cursor: default; transition: opacity var(--transition-fast); }
    .ap__leyenda li span { width: 10px; height: 10px; border-radius: 3px; }
    .ap__leyenda--off { opacity: 0.35; }
    .ap__filas { display: flex; flex-direction: column; gap: var(--space-3); }
    .ap__fila { display: flex; flex-direction: column; gap: 5px; animation: ui-rise var(--transition-slow) both; }
    .ap__top { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-3); }
    .ap__label { font-size: var(--fs-sm); font-weight: 700; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .ap__total { font-size: var(--fs-sm); font-weight: 800; color: var(--text); font-variant-numeric: tabular-nums; }
    .ap__pista { height: 16px; border-radius: var(--radius-pill); background: var(--surface-3); overflow: hidden; }
    .ap__barra { display: flex; height: 100%; gap: 2px; border-radius: inherit; overflow: hidden; transform-origin: left; animation: ap-crece 800ms cubic-bezier(0.16, 1, 0.3, 1) both; }
    .ap__seg { flex-basis: 0; min-width: 3px; transition: opacity var(--transition-fast); }
    .ap__seg--off { opacity: 0.2; }
    .ap__dom { font-size: 0.7rem; color: var(--text-3); }
    .ap__dom strong { color: var(--text-2); }
    @keyframes ap-crece { from { transform: scaleX(0); } to { transform: scaleX(1); } }
  `,
})
export class StackedBars {
  readonly grupos = input<GrupoApilado[]>([]);
  /** Segmentos con color propio; el resto se agrupa en «Otros». */
  readonly maxSegmentos = input<number>(5);
  readonly maxGrupos = input<number>(8);

  readonly foco = signal<string | null>(null);

  private readonly principales = computed(() => {
    const totales = new Map<string, number>();
    for (const g of this.grupos()) {
      for (const s of g.segmentos) {
        totales.set(s.nombre, (totales.get(s.nombre) ?? 0) + s.valor);
      }
    }
    return [...totales.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, this.maxSegmentos())
      .map(([nombre]) => nombre);
  });

  private color(nombre: string): string {
    const i = this.principales().indexOf(nombre);
    return i >= 0 ? SERIES[i % SERIES.length] : SERIE_OTROS;
  }

  readonly leyenda = computed(() => {
    const items = this.principales().map((nombre) => ({ nombre, color: this.color(nombre) }));
    const hayOtros = this.grupos().some((g) => g.segmentos.some((s) => !this.principales().includes(s.nombre)));
    return hayOtros ? [...items, { nombre: 'Otros', color: SERIE_OTROS }] : items;
  });

  readonly filas = computed(() => {
    const principales = this.principales();
    const grupos = this.grupos()
      .map((g) => ({ ...g, total: g.segmentos.reduce((t, s) => t + s.valor, 0) }))
      .sort((a, b) => b.total - a.total)
      .slice(0, this.maxGrupos());
    const tope = grupos[0]?.total || 1;
    return grupos.map((g) => {
      const propios = principales
        .map((nombre) => ({ nombre, valor: g.segmentos.filter((s) => s.nombre === nombre).reduce((t, s) => t + s.valor, 0) }))
        .filter((s) => s.valor > 0);
      const otros = g.segmentos.filter((s) => !principales.includes(s.nombre)).reduce((t, s) => t + s.valor, 0);
      const segmentos = [...propios, ...(otros > 0 ? [{ nombre: 'Otros', valor: otros }] : [])].map((s) => ({
        ...s,
        color: s.nombre === 'Otros' ? SERIE_OTROS : this.color(s.nombre),
        pct: porcentaje(g.total ? s.valor / g.total : 0),
      }));
      const dominante = [...g.segmentos].sort((a, b) => b.valor - a.valor)[0];
      return {
        etiqueta: g.etiqueta,
        totalTexto: entero(g.total),
        pct: (g.total / tope) * 100,
        segmentos,
        dominante: dominante
          ? `${dominante.nombre} (${porcentaje(g.total ? dominante.valor / g.total : 0)})`
          : '',
      };
    });
  });
}
