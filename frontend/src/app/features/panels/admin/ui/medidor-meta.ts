import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Nivel } from './observabilidad.util';

/**
 * Medidor horizontal contra una meta: la barra es el valor, la línea vertical la meta y
 * el color el semáforo (el estado también va en texto en la tarjeta: nunca solo color).
 */
@Component({
  selector: 'app-medidor-meta',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="me">
      <div class="me__top">
        <span class="me__etiqueta">{{ etiqueta() }}</span>
        <span class="me__valor" [attr.data-nivel]="nivel()">{{ valor() }}</span>
      </div>
      <div
        class="me__pista"
        role="meter"
        [attr.aria-label]="etiqueta() + ': ' + valor() + (nota() ? '. ' + nota() : '')"
        aria-valuemin="0"
        aria-valuemax="100"
        [attr.aria-valuenow]="porcentaje()"
      >
        <span class="me__barra" [attr.data-nivel]="nivel()" [style.width.%]="porcentaje()"></span>
        @if (meta() !== null) {
          <span class="me__meta" [style.left.%]="metaPorcentaje()"></span>
        }
      </div>
      @if (nota()) {
        <span class="me__nota">{{ nota() }}</span>
      }
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    /* estructura */
    .me { display: flex; flex-direction: column; gap: var(--space-2); }
    .me__top { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); min-width: 0; }
    .me__pista { position: relative; height: 10px; }
    .me__barra { position: absolute; inset: 0 auto 0 0; max-width: 100%; }
    .me__meta { position: absolute; top: -4px; bottom: -4px; width: 3px; margin-left: -1.5px; }
    /* apariencia */
    .me__etiqueta { font-size: var(--fs-sm); font-weight: 600; color: var(--text-2); overflow-wrap: anywhere; }
    .me__valor { font-family: var(--font-display); font-size: var(--fs-lg); font-weight: 800; color: var(--text); font-variant-numeric: tabular-nums; white-space: nowrap; }
    .me__valor[data-nivel='riesgo'] { color: var(--danger); }
    .me__pista { border-radius: var(--radius-pill); background: var(--surface-3); }
    .me__barra { border-radius: var(--radius-pill); background: var(--success); transition: width var(--transition-slow); }
    .me__barra[data-nivel='aviso'] { background: var(--warning); }
    .me__barra[data-nivel='riesgo'] { background: var(--danger); }
    .me__meta { border-radius: 2px; background: var(--text); box-shadow: 0 0 0 2px var(--surface); }
    .me__nota { font-size: var(--fs-xs); color: var(--text-3); }
  `,
})
export class MedidorMeta {
  readonly etiqueta = input.required<string>();
  /** Valor ya formateado («99,6 %», «420 ms»). */
  readonly valor = input.required<string>();
  /** Largo de la barra, 0–1 sobre la escala del medidor. */
  readonly fraccion = input(0);
  /** Posición de la meta, 0–1 sobre la misma escala (null = sin meta). */
  readonly meta = input<number | null>(null);
  readonly nivel = input<Nivel>('ok');
  readonly nota = input('');

  readonly porcentaje = computed(() => Math.round(Math.min(1, Math.max(0, this.fraccion())) * 100));
  readonly metaPorcentaje = computed(() => Math.min(100, Math.max(0, (this.meta() ?? 0) * 100)));
}
