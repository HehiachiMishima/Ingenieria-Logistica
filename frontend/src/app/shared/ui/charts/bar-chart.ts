import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface BarraDato {
  etiqueta: string;
  valor: number;
  color: string;
  detalle?: string;
}

@Component({
  selector: 'ui-bars',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (horizontal()) {
      <div class="hbars">
        @for (barra of barras(); track barra.etiqueta; let i = $index) {
          <div class="hrow" [style.--bar-color]="barra.color" [style.animation-delay]="i * 70 + 'ms'">
            <div class="hrow__top">
              <span class="hrow__label" [title]="barra.etiqueta">{{ barra.etiqueta }}</span>
              <span class="hrow__meta">
                <span class="hrow__value">{{ barra.valor }}</span>
                @if (barra.detalle) {
                  <span class="hrow__sub">{{ barra.detalle }}</span>
                }
              </span>
            </div>
            <div class="hrow__track">
              <div class="hrow__fill" [style.width.%]="barra.pct"></div>
            </div>
          </div>
        }
      </div>
    } @else {
      <div class="bars" [style.height.px]="altura()">
        @for (barra of barras(); track barra.etiqueta; let i = $index) {
          <div class="bars__col">
            <span class="bars__value">{{ barra.valor }}</span>
            @if (barra.detalle) {
              <span class="bars__sub">{{ barra.detalle }}</span>
            }
            <div class="bars__track">
              <div
                class="bars__fill"
                [style.height.%]="barra.pct"
                [style.background]="barra.color"
                [style.animation-delay]="i * 90 + 'ms'"
              ></div>
            </div>
            <span class="bars__label">{{ barra.etiqueta }}</span>
          </div>
        }
      </div>
    }
  `,
  styles: `
    :host { display: block; }

    .bars {
      display: flex;
      align-items: stretch;
      gap: clamp(var(--space-3), 3vw, var(--space-6));
      padding-top: var(--space-5);
    }

    .bars__col {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--space-3);
    }

    .bars__value {
      font-size: var(--fs-lg);
      font-weight: 600;
      color: var(--text);
      font-variant-numeric: tabular-nums;
    }

    .bars__sub {
      margin-top: calc(var(--space-1) * -1);
      font-size: var(--fs-xs);
      font-weight: 600;
      color: var(--text-3);
      font-variant-numeric: tabular-nums;
    }

    .bars__track {
      flex: 1;
      width: 100%;
      max-width: 68px;
      display: flex;
      align-items: flex-end;
      border-radius: var(--radius);
      background: var(--surface-2);
      overflow: hidden;
    }

    .bars__fill {
      width: 100%;
      border-radius: var(--radius);
      transform-origin: bottom;
      animation: bar-grow 900ms var(--transition-slow) both;
    }

    .bars__label {
      font-size: var(--fs-sm);
      font-weight: 600;
      color: var(--text-2);
      text-align: center;
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .hbars {
      display: flex;
      flex-direction: column;
      gap: var(--space-5);
    }

    .hrow {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      padding: var(--space-2) var(--space-3);
      border-radius: var(--radius-lg);
      animation: bar-row var(--transition-slow) both;
      transition: background var(--transition);
    }

    .hrow:hover {
      background: var(--surface-2);
    }

    .hrow__top {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--space-3);
    }

    .hrow__label {
      font-size: var(--fs-md);
      font-weight: 600;
      color: var(--text);
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .hrow__meta {
      display: flex;
      align-items: baseline;
      gap: var(--space-3);
      flex: none;
    }

    .hrow__value {
      font-size: var(--fs-xl);
      font-weight: 700;
      letter-spacing: -0.02em;
      color: var(--bar-color, var(--brand));
      font-variant-numeric: tabular-nums;
    }

    .hrow__sub {
      font-size: var(--fs-sm);
      font-weight: 600;
      color: var(--text-3);
      font-variant-numeric: tabular-nums;
    }

    .hrow__track {
      height: 14px;
      border-radius: var(--radius-pill);
      background: var(--surface-2);
      overflow: hidden;
      transition: background var(--transition);
    }

    .hrow__fill {
      height: 100%;
      border-radius: var(--radius-pill);
      background: linear-gradient(
        90deg,
        color-mix(in srgb, var(--bar-color, var(--brand)) 62%, transparent),
        var(--bar-color, var(--brand))
      );
      box-shadow: 0 2px 12px color-mix(in srgb, var(--bar-color, var(--brand)) 40%, transparent);
      transform-origin: left;
      animation: bar-slide 900ms var(--transition-slow) both;
    }

    @keyframes bar-grow {
      from { transform: scaleY(0); opacity: 0.4; }
      to { transform: scaleY(1); opacity: 1; }
    }

    @keyframes bar-slide {
      from { transform: scaleX(0); opacity: 0.4; }
      to { transform: scaleX(1); opacity: 1; }
    }

    @keyframes bar-row {
      from { opacity: 0; transform: translateY(10px); }
      to { opacity: 1; transform: translateY(0); }
    }

    @media (prefers-reduced-motion: reduce) {
      .hrow,
      .hrow__fill,
      .bars__fill { animation: none; }
    }
  `,
})
export class BarChart {
  readonly datos = input<BarraDato[]>([]);
  readonly altura = input<number>(220);
  readonly horizontal = input<boolean>(false);

  readonly barras = computed(() => {
    const datos = this.datos();
    const maximo = Math.max(1, ...datos.map((dato) => dato.valor));
    return datos.map((dato) => ({
      ...dato,
      pct: dato.valor <= 0 ? 0 : Math.max(6, (dato.valor / maximo) * 100),
    }));
  });
}
