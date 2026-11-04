import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { SERIES, SERIE_OTROS, entero, porcentaje } from './formato';

export interface SegmentoAnillo {
  etiqueta: string;
  valor: number;
  detalle?: string;
}

/**
 * Anillo interactivo: al pasar por un segmento o por su fila de la leyenda se
 * resalta y el centro muestra su valor y participación. Anillo y leyenda van
 * lado a lado o apilados según el ancho de la tarjeta (container queries).
 */
@Component({
  selector: 'ui-anillo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="an" (mouseleave)="foco.set(null)">
      <div class="an__aro">
        <svg viewBox="0 0 42 42" role="img" [attr.aria-label]="titulo()">
          <circle class="an__pista" cx="21" cy="21" r="15.9155" />
          @for (s of segmentos(); track s.etiqueta; let i = $index) {
            <circle
              class="an__seg"
              [class.an__seg--off]="foco() !== null && foco() !== i"
              cx="21"
              cy="21"
              r="15.9155"
              [attr.stroke-dasharray]="s.dash"
              [attr.stroke-dashoffset]="s.offset"
              [style.stroke]="s.color"
              (mouseenter)="foco.set(i)"
            />
          }
        </svg>
        <div class="an__centro">
          @if (activo(); as a) {
            <span class="an__valor">{{ a.texto }}</span>
            <span class="an__sub">{{ a.pct }}</span>
            <span class="an__nombre">{{ a.etiqueta }}</span>
          } @else {
            <span class="an__valor">{{ totalTexto() }}</span>
            <span class="an__nombre">{{ centro() }}</span>
          }
        </div>
      </div>
      <ul class="an__leyenda">
        @for (s of segmentos(); track s.etiqueta; let i = $index) {
          <li
            class="an__fila"
            [class.an__fila--on]="foco() === i"
            [class.an__fila--off]="foco() !== null && foco() !== i"
            (mouseenter)="foco.set(i)"
            (click)="foco.set(foco() === i ? null : i)"
          >
            <span class="an__sw" [style.background]="s.color"></span>
            <span class="an__lbl" [title]="s.etiqueta">{{ s.etiqueta }}</span>
            <span class="an__num">{{ s.texto }}</span>
            <span class="an__pct">{{ s.pct }}</span>
            <span class="an__mini"><span [style.width.%]="s.relativo" [style.background]="s.color"></span></span>
            @if (s.detalle) {
              <span class="an__det">{{ s.detalle }}</span>
            }
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; container-type: inline-size; }
    .an { display: grid; grid-template-columns: minmax(150px, 220px) minmax(0, 1fr); align-items: center; gap: var(--space-5); }
    @container (max-width: 460px) { .an { grid-template-columns: 1fr; justify-items: center; } .an__leyenda { width: 100%; } }
    .an__aro { position: relative; width: 100%; max-width: 220px; aspect-ratio: 1; }
    svg { width: 100%; height: 100%; transform: rotate(-90deg); overflow: visible; }
    .an__pista { fill: none; stroke: var(--surface-3); stroke-width: 5.5; }
    .an__seg { fill: none; stroke-width: 5.5; cursor: pointer; transition: opacity var(--transition-fast), stroke-width var(--transition-fast); animation: an-entra 900ms cubic-bezier(0.16, 1, 0.3, 1) both; }
    .an__seg:hover { stroke-width: 7; }
    .an__seg--off { opacity: 0.25; }
    .an__centro { position: absolute; inset: 22%; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; pointer-events: none; }
    .an__valor { font-family: var(--font-display); font-size: clamp(1.3rem, 1rem + 1.5cqi, 1.9rem); font-weight: 800; letter-spacing: -0.03em; line-height: 1; color: var(--text); }
    .an__sub { font-size: var(--fs-sm); font-weight: 800; color: var(--brand); }
    .an__nombre { max-width: 100%; margin-top: 2px; font-size: 0.66rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .an__leyenda { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
    .an__fila {
      display: grid; grid-template-columns: 12px minmax(0, 1fr) auto 48px; grid-template-areas: 'sw lbl num pct' '. mini mini mini' '. det det det';
      align-items: center; column-gap: var(--space-2); row-gap: 3px;
      padding: 6px 8px; border-radius: var(--radius-sm); cursor: pointer;
      transition: background var(--transition-fast), opacity var(--transition-fast);
    }
    .an__fila:hover, .an__fila--on { background: var(--surface-2); }
    .an__fila--off { opacity: 0.5; }
    .an__sw { grid-area: sw; width: 12px; height: 12px; border-radius: 4px; }
    .an__lbl { grid-area: lbl; font-size: var(--fs-sm); font-weight: 700; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .an__num { grid-area: num; font-size: var(--fs-sm); font-weight: 800; color: var(--text); font-variant-numeric: tabular-nums; }
    .an__pct { grid-area: pct; text-align: right; font-size: var(--fs-xs); font-weight: 700; color: var(--text-3); font-variant-numeric: tabular-nums; }
    .an__mini { grid-area: mini; height: 4px; border-radius: 4px; background: var(--surface-3); overflow: hidden; }
    .an__mini span { display: block; height: 100%; border-radius: inherit; }
    .an__det { grid-area: det; font-size: 0.68rem; color: var(--text-3); }
    @keyframes an-entra { from { stroke-dasharray: 0 100; } }
  `,
})
export class RingChart {
  readonly items = input<SegmentoAnillo[]>([]);
  readonly maximo = input<number>(7);
  readonly centro = input<string>('Total');
  readonly titulo = input<string>('Distribución');

  readonly foco = signal<number | null>(null);

  private readonly total = computed(() => this.items().reduce((t, i) => t + i.valor, 0));
  readonly totalTexto = computed(() => entero(this.total()));

  readonly segmentos = computed(() => {
    const total = this.total() || 1;
    const orden = [...this.items()].filter((i) => i.valor > 0).sort((a, b) => b.valor - a.valor);
    const visibles = orden.slice(0, this.maximo());
    const resto = orden.slice(this.maximo());
    const lista = resto.length
      ? [...visibles, { etiqueta: `Otros (${resto.length})`, valor: resto.reduce((t, i) => t + i.valor, 0), detalle: undefined }]
      : visibles;
    const max = lista[0]?.valor || 1;
    let acumulado = 0;
    return lista.map((s, i) => {
      const pct = (s.valor / total) * 100;
      const hueco = lista.length > 1 ? 0.6 : 0;
      const seg = {
        ...s,
        color: i < visibles.length ? SERIES[i % SERIES.length] : SERIE_OTROS,
        dash: `${Math.max(pct - hueco, 0.1)} ${100 - Math.max(pct - hueco, 0.1)}`,
        offset: `${-acumulado}`,
        texto: entero(s.valor),
        pct: porcentaje(s.valor / total),
        relativo: (s.valor / max) * 100,
      };
      acumulado += pct;
      return seg;
    });
  });

  readonly activo = computed(() => {
    const i = this.foco();
    return i === null ? null : (this.segmentos()[i] ?? null);
  });
}
