import { ChangeDetectionStrategy, Component, afterNextRender, computed, input, signal } from '@angular/core';

@Component({
  selector: 'ui-gauge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="gauge" [style.max-width.px]="tamano()">
      <svg viewBox="0 0 42 42" role="img" [attr.aria-label]="etiqueta() + ': ' + valor() + '%'">
        <circle class="gauge__track" cx="21" cy="21" r="15.9155" />
        <g transform="rotate(-90 21 21)">
          <circle
            class="gauge__value"
            cx="21"
            cy="21"
            r="15.9155"
              [attr.stroke-dasharray]="dash()"
              [style.stroke]="color()"
              [style.color]="color()"
            />
        </g>
      </svg>
      @if (conTexto()) {
        <div class="gauge__center">
          <span class="gauge__num">{{ valor() }}<span class="gauge__pct">%</span></span>
          <span class="gauge__caption">{{ etiqueta() }}</span>
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: block; }

    .gauge {
      position: relative;
      width: 100%;
      aspect-ratio: 1;
      margin-inline: auto;
      animation: gauge-in 900ms var(--transition-slow) both;
    }

    svg { width: 100%; height: 100%; }

    .gauge__track {
      fill: none;
      stroke: var(--surface-3);
      stroke-width: 3.6;
    }

    .gauge__value {
      fill: none;
      stroke-width: 4.4;
      stroke-linecap: round;
      transition: stroke-dasharray 1000ms var(--transition-slow);
    }

    .gauge__center {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 2px;
    }

    .gauge__num {
      font-size: var(--fs-3xl);
      font-weight: 600;
      line-height: 1;
      letter-spacing: -0.03em;
      color: var(--text);
      font-variant-numeric: tabular-nums;
    }

    .gauge__num::after {
      content: '%';
      font-size: 0.5em;
      font-weight: 600;
      margin-left: 2px;
      color: var(--text-3);
      vertical-align: super;
    }

    .gauge__caption {
      margin-top: var(--space-2);
      font-size: var(--fs-xs);
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-3);
      text-align: center;
    }

    @keyframes gauge-in {
      from { opacity: 0; transform: scale(0.85); }
      to { opacity: 1; transform: scale(1); }
    }
  `,
})
export class GaugeChart {
  readonly valor = input<number>(0);
  readonly etiqueta = input<string>('Progreso');
  readonly color = input<string>('var(--brand)');
  readonly tamano = input<number>(220);
  /** false = sin número ni rótulo al centro (cuando el valor ya se muestra al lado). */
  readonly conTexto = input<boolean>(true);

  private readonly listo = signal(false);

  constructor() {
    afterNextRender(() => this.listo.set(true));
  }

  readonly dash = computed(() => {
    const valor = this.listo() ? this.valor() : 0;
    return `${Math.min(100, Math.max(0, valor))} ${100 - Math.min(100, Math.max(0, valor))}`;
  });
}
