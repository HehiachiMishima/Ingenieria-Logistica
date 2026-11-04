import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { entero, porcentaje } from './formato';

export interface ItemRanking {
  etiqueta: string;
  valor: number;
  /** Etiqueta corta de contexto (sistema, nº de proveedores…). */
  chip?: string;
  /** Línea secundaria (MTTR, unidades…). */
  detalle?: string;
}

/**
 * Ranking en tarjetas: cada ítem muestra su posición, volumen, participación
 * (anillo) y contexto. El líder se destaca con el color de marca. La rejilla
 * se adapta al ancho de la tarjeta contenedora (container queries).
 */
@Component({
  selector: 'ui-ranking',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="rk">
      @for (t of tarjetas(); track t.etiqueta; let i = $index) {
        <li class="rk__item" [class.rk__item--lider]="i === 0" [style.animation-delay]="i * 50 + 'ms'">
          <div class="rk__top">
            <span class="rk__pos">{{ i + 1 }}</span>
            @if (t.chip) {
              <span class="rk__chip" [title]="t.chip">{{ t.chip }}</span>
            }
          </div>
          <span class="rk__nombre" [title]="t.etiqueta">{{ t.etiqueta }}</span>
          <div class="rk__datos">
            <span class="rk__valor">{{ t.texto }}</span>
            <svg class="rk__anillo" viewBox="0 0 36 36" aria-hidden="true">
              <circle class="rk__anillo-pista" cx="18" cy="18" r="15.9155" />
              <circle
                class="rk__anillo-arco"
                cx="18"
                cy="18"
                r="15.9155"
                [attr.stroke-dasharray]="t.fraccion * 100 + ' 100'"
              />
              <text x="18" y="21.5" text-anchor="middle">{{ t.pctCorto }}</text>
            </svg>
          </div>
          <div class="rk__barra"><span [style.width.%]="t.relativo"></span></div>
          @if (t.detalle) {
            <span class="rk__detalle">{{ t.detalle }}</span>
          }
        </li>
      }
    </ol>
    @if (resto(); as r) {
      <p class="rk__resto">+ {{ r.cantidad }} más con {{ r.texto }} casos ({{ r.pct }}).</p>
    }
  `,
  styles: `
    :host { display: block; min-width: 0; container-type: inline-size; }
    .rk { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-3); }
    @container (min-width: 620px) { .rk { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
    @container (min-width: 900px) { .rk { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
    @container (max-width: 250px) { .rk { grid-template-columns: 1fr; } }
    @container (max-width: 400px) {
      .rk { gap: var(--space-2); }
      .rk__item { padding: var(--space-2) var(--space-3); }
      .rk__valor { font-size: 1.3rem; }
      .rk__anillo { width: 38px; height: 38px; }
      .rk__chip { display: none; }
    }

    .rk__item {
      --c: var(--accent);
      position: relative;
      display: flex;
      flex-direction: column;
      gap: 6px;
      min-width: 0;
      padding: var(--space-3);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      overflow: hidden;
      animation: ui-rise var(--transition-slow) both;
      transition: border-color var(--transition-fast), transform var(--transition-fast), box-shadow var(--transition-fast);
    }
    .rk__item:hover { border-color: color-mix(in srgb, var(--c) 55%, var(--border)); transform: translateY(-2px); box-shadow: var(--shadow-sm); }
    .rk__item--lider {
      --c: #fff;
      color: #fff;
      border-color: transparent;
      background: radial-gradient(120% 120% at 100% 0%, rgba(255, 255, 255, 0.18), transparent 55%), var(--gradient-brand);
      box-shadow: 0 14px 30px -14px var(--brand);
    }
    .rk__top { display: flex; align-items: center; justify-content: space-between; gap: 6px; min-width: 0; }
    .rk__pos {
      display: inline-flex; align-items: center; justify-content: center;
      width: 24px; height: 24px; flex: none; border-radius: 7px;
      font-size: var(--fs-xs); font-weight: 800;
      color: var(--accent); background: var(--accent-soft);
    }
    .rk__item--lider .rk__pos { color: var(--brand); background: #fff; }
    .rk__chip {
      min-width: 0; padding: 2px 8px; border-radius: var(--radius-pill);
      font-size: 0.64rem; font-weight: 800; letter-spacing: 0.04em; text-transform: uppercase;
      color: var(--text-2); background: var(--surface-3);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .rk__item--lider .rk__chip { color: #fff; background: rgba(255, 255, 255, 0.2); }
    .rk__nombre {
      min-height: 2.4em;
      font-size: var(--fs-sm); font-weight: 800; line-height: 1.2; color: var(--text);
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
    }
    .rk__item--lider .rk__nombre { color: #fff; }
    .rk__datos { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
    .rk__valor {
      font-family: var(--font-display); font-size: 1.6rem; font-weight: 800;
      letter-spacing: -0.035em; line-height: 1; font-variant-numeric: tabular-nums;
    }
    .rk__anillo { width: 46px; height: 46px; flex: none; transform: rotate(-90deg); }
    .rk__anillo-pista { fill: none; stroke: var(--surface-3); stroke-width: 4; }
    .rk__anillo-arco { fill: none; stroke: var(--c); stroke-width: 4; stroke-linecap: round; transition: stroke-dasharray 700ms ease; }
    .rk__item--lider .rk__anillo-pista { stroke: rgba(255, 255, 255, 0.25); }
    .rk__anillo text { transform: rotate(90deg); transform-origin: 18px 18px; font-size: 9px; font-weight: 800; fill: currentColor; }
    .rk__barra { height: 4px; border-radius: 4px; background: var(--surface-3); overflow: hidden; }
    .rk__barra span { display: block; height: 100%; background: var(--c); border-radius: inherit; }
    .rk__item--lider .rk__barra { background: rgba(255, 255, 255, 0.25); }
    .rk__detalle { font-size: 0.7rem; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .rk__item--lider .rk__detalle { color: rgba(255, 255, 255, 0.85); }
    .rk__resto { margin-top: var(--space-3); font-size: var(--fs-sm); color: var(--text-3); }
  `,
})
export class RankingTiles {
  readonly items = input<ItemRanking[]>([]);
  readonly maximo = input<number>(8);

  private readonly ordenados = computed(() => [...this.items()].sort((a, b) => b.valor - a.valor));
  private readonly total = computed(() => this.ordenados().reduce((t, i) => t + i.valor, 0) || 1);

  readonly tarjetas = computed(() => {
    const total = this.total();
    const lista = this.ordenados().slice(0, this.maximo());
    const max = lista[0]?.valor || 1;
    return lista.map((i) => {
      const fraccion = i.valor / total;
      return {
        ...i,
        texto: entero(i.valor),
        fraccion,
        pctCorto: `${Math.round(fraccion * 100)}%`,
        relativo: (i.valor / max) * 100,
      };
    });
  });

  readonly resto = computed(() => {
    const resto = this.ordenados().slice(this.maximo());
    if (!resto.length) {
      return null;
    }
    const suma = resto.reduce((t, i) => t + i.valor, 0);
    return { cantidad: resto.length, texto: entero(suma), pct: porcentaje(suma / this.total()) };
  });
}
