import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Mini tendencia sin ejes: el área se estira al ancho (trazo sin deformar) y el último
 * punto es un elemento HTML, así nunca se aplasta.
 */
@Component({
  selector: 'app-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sp" role="img" [attr.aria-label]="etiqueta()">
      @if (trazo(); as t) {
        <svg class="sp__svg" viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
          <path class="sp__area" [attr.d]="t.area" [style.fill]="color()" />
          <path class="sp__linea" [attr.d]="t.linea" [style.stroke]="color()" />
        </svg>
        <span class="sp__punto" [style.top.%]="t.ultimoY" [style.background]="color()"></span>
      }
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .sp { position: relative; height: 44px; }
    .sp__svg { display: block; width: 100%; height: 100%; overflow: visible; }
    .sp__area { opacity: 0.1; }
    .sp__linea { fill: none; stroke-width: 2; vector-effect: non-scaling-stroke; stroke-linejoin: round; stroke-linecap: round; }
    .sp__punto { position: absolute; right: -4px; width: 8px; height: 8px; margin-top: -4px; border-radius: 50%; box-shadow: 0 0 0 2px var(--surface); }
  `,
})
export class Sparkline {
  readonly valores = input<number[]>([]);
  readonly color = input('var(--accent)');
  /** Descripción para lectores de pantalla («Usuarios activos por día: de 120 a 184»). */
  readonly etiqueta = input('Tendencia');

  readonly trazo = computed(() => {
    const v = this.valores();
    if (v.length < 2) {
      return null;
    }
    const max = Math.max(...v);
    const min = Math.min(...v);
    const rango = max - min || 1;
    // 4 % de margen arriba y abajo para que el trazo no toque el borde.
    const y = (n: number) => 38 - ((n - min) / rango) * 36;
    const x = (i: number) => (i / (v.length - 1)) * 100;
    const puntos = v.map((n, i) => `${x(i).toFixed(2)},${y(n).toFixed(2)}`);
    return {
      linea: `M${puntos.join(' L')}`,
      area: `M${puntos.join(' L')} L100,40 L0,40 Z`,
      ultimoY: (y(v[v.length - 1]) / 40) * 100,
    };
  });
}
