import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { SERIES, SERIE_OTROS, entero, porcentaje } from './formato';

export interface ItemWaffle {
  etiqueta: string;
  valor: number;
  detalle?: string;
}

/**
 * Waffle 10 × 10: cada celda = 1 % del total. Se reparte con «mayor resto»
 * para que siempre sumen 100 celdas. Al pasar por la leyenda se resaltan sus
 * celdas. Celdas y leyenda se acomodan según el ancho de la tarjeta.
 */
@Component({
  selector: 'ui-waffle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wf" (mouseleave)="foco.set(null)">
      <div class="wf__rejilla" role="img" [attr.aria-label]="resumen()">
        @for (c of celdas(); track $index) {
          <span
            class="wf__celda"
            [class.wf__celda--off]="foco() !== null && foco() !== c"
            [style.background]="categorias()[c]?.color"
            [style.animation-delay]="$index * 6 + 'ms'"
            (mouseenter)="foco.set(c)"
          ></span>
        }
      </div>
      <ul class="wf__leyenda">
        @for (cat of categorias(); track cat.etiqueta; let i = $index) {
          <li
            class="wf__fila"
            [class.wf__fila--on]="foco() === i"
            (mouseenter)="foco.set(i)"
            (click)="foco.set(foco() === i ? null : i)"
          >
            <span class="wf__sw" [style.background]="cat.color"></span>
            <span class="wf__lbl" [title]="cat.etiqueta">{{ cat.etiqueta }}</span>
            <span class="wf__pct">{{ cat.pct }}</span>
            <span class="wf__num">{{ cat.texto }} casos{{ cat.detalle ? ' · ' + cat.detalle : '' }}</span>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; container-type: inline-size; }
    .wf { display: grid; grid-template-columns: minmax(160px, 240px) minmax(0, 1fr); align-items: center; gap: var(--space-5); }
    @container (max-width: 460px) { .wf { grid-template-columns: 1fr; } .wf__rejilla { max-width: 260px; margin: 0 auto; } }
    .wf__rejilla { display: grid; grid-template-columns: repeat(10, 1fr); gap: 4px; width: 100%; aspect-ratio: 1; }
    .wf__celda { border-radius: 4px; cursor: pointer; transition: opacity var(--transition-fast), transform var(--transition-fast); animation: wf-entra 400ms cubic-bezier(0.16, 1, 0.3, 1) both; }
    .wf__celda:hover { transform: scale(1.12); }
    .wf__celda--off { opacity: 0.18; }
    .wf__leyenda { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
    .wf__fila {
      display: grid; grid-template-columns: 12px minmax(0, 1fr) auto; grid-template-areas: 'sw lbl pct' '. num num';
      align-items: center; column-gap: var(--space-2); row-gap: 2px;
      padding: 6px 8px; border-radius: var(--radius-sm); cursor: pointer; transition: background var(--transition-fast);
    }
    .wf__fila:hover, .wf__fila--on { background: var(--surface-2); }
    .wf__sw { grid-area: sw; width: 12px; height: 12px; border-radius: 3px; }
    .wf__lbl { grid-area: lbl; font-size: var(--fs-sm); font-weight: 700; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .wf__pct { grid-area: pct; font-family: var(--font-display); font-size: var(--fs-base); font-weight: 800; color: var(--text); font-variant-numeric: tabular-nums; }
    .wf__num { grid-area: num; font-size: 0.7rem; color: var(--text-3); }
    @keyframes wf-entra { from { opacity: 0; transform: scale(0.4); } to { opacity: 1; transform: none; } }
  `,
})
export class WaffleChart {
  readonly items = input<ItemWaffle[]>([]);
  readonly maximo = input<number>(6);

  readonly foco = signal<number | null>(null);

  readonly categorias = computed(() => {
    const orden = [...this.items()].filter((i) => i.valor > 0).sort((a, b) => b.valor - a.valor);
    const total = orden.reduce((t, i) => t + i.valor, 0) || 1;
    const visibles = orden.slice(0, this.maximo());
    const resto = orden.slice(this.maximo());
    const lista = resto.length
      ? [...visibles, { etiqueta: `Otros (${resto.length})`, valor: resto.reduce((t, i) => t + i.valor, 0), detalle: undefined }]
      : visibles;
    // Reparto de 100 celdas por mayor resto.
    const exactas = lista.map((c) => (c.valor / total) * 100);
    const celdas = exactas.map(Math.floor);
    let faltan = 100 - celdas.reduce((a, b) => a + b, 0);
    exactas
      .map((e, i) => ({ i, resto: e - Math.floor(e) }))
      .sort((a, b) => b.resto - a.resto)
      .forEach(({ i }) => {
        if (faltan > 0) {
          celdas[i]++;
          faltan--;
        }
      });
    return lista.map((c, i) => ({
      ...c,
      celdas: celdas[i],
      color: i < visibles.length ? SERIES[i % SERIES.length] : SERIE_OTROS,
      pct: porcentaje(c.valor / total),
      texto: entero(c.valor),
    }));
  });

  /** Índice de categoría de cada una de las 100 celdas. */
  readonly celdas = computed(() => this.categorias().flatMap((c, i) => Array.from({ length: c.celdas }, () => i)));

  readonly resumen = computed(() => this.categorias().map((c) => `${c.etiqueta} ${c.pct}`).join(', '));
}
