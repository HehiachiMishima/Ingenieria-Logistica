import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { porcentaje } from './formato';

export interface ItemBullet {
  etiqueta: string;
  /** Valor medido (p. ej. segundos de MTTR). */
  valor: number;
  /** Valor formateado para mostrar (p. ej. «2 h 06 m»). */
  texto: string;
  detalle?: string;
}

/**
 * Bullet chart contra una referencia (p. ej. MTTR promedio general):
 * barra = valor del ítem; línea vertical = referencia; zonas de fondo =
 * ≤ referencia (bien), ≤ 1,5× (atención) y por encima (crítico). El color
 * de la barra y el semáforo siguen la misma regla.
 */
@Component({
  selector: 'ui-bullet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bu">
      <ul class="bu__leyenda" aria-hidden="true">
        <li><i class="bu__z bu__z--ok"></i>≤ promedio</li>
        <li><i class="bu__z bu__z--warn"></i>hasta 1,5×</li>
        <li><i class="bu__z bu__z--mal"></i>crítico</li>
        <li><i class="bu__ref"></i>promedio: <strong>{{ textoReferencia() }}</strong></li>
      </ul>
      <ol class="bu__filas">
        @for (f of filas(); track f.etiqueta; let i = $index) {
          <li class="bu__fila" [attr.data-estado]="f.estado" [style.animation-delay]="i * 50 + 'ms'">
            <div class="bu__top">
              <span class="bu__sem" [attr.title]="f.estadoTexto"></span>
              <span class="bu__label" [title]="f.etiqueta">{{ f.etiqueta }}</span>
              <span class="bu__valor">{{ f.texto }}</span>
              <span class="bu__delta">{{ f.delta }}</span>
            </div>
            <div class="bu__pista" [style.--ref.%]="refPct()" [style.--warn.%]="warnPct()">
              <span class="bu__barra" [style.width.%]="f.pct"></span>
              <span class="bu__linea"></span>
            </div>
            @if (f.detalle) {
              <span class="bu__detalle">{{ f.detalle }}</span>
            }
          </li>
        }
      </ol>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .bu__leyenda { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-bottom: var(--space-4); font-size: var(--fs-xs); font-weight: 600; color: var(--text-2); }
    .bu__leyenda li { display: inline-flex; align-items: center; gap: 6px; }
    .bu__leyenda strong { color: var(--text); }
    .bu__z { width: 12px; height: 10px; border-radius: 3px; }
    .bu__z--ok { background: var(--success-soft); border: 1px solid var(--success); }
    .bu__z--warn { background: var(--warning-soft); border: 1px solid var(--warning); }
    .bu__z--mal { background: var(--danger-soft); border: 1px solid var(--danger); }
    .bu__ref { width: 3px; height: 14px; border-radius: 2px; background: var(--text); }
    .bu__filas { display: flex; flex-direction: column; gap: var(--space-3); }
    .bu__fila { --c: var(--success); display: flex; flex-direction: column; gap: 6px; animation: ui-rise var(--transition-slow) both; }
    .bu__fila[data-estado='warn'] { --c: var(--warning); }
    .bu__fila[data-estado='mal'] { --c: var(--danger); }
    .bu__top { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: baseline; gap: var(--space-2); }
    .bu__sem { width: 10px; height: 10px; border-radius: 50%; background: var(--c); box-shadow: 0 0 0 3px color-mix(in srgb, var(--c) 22%, transparent); align-self: center; }
    .bu__label { font-size: var(--fs-sm); font-weight: 800; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .bu__valor { font-size: var(--fs-sm); font-weight: 800; color: var(--c); font-variant-numeric: tabular-nums; }
    .bu__delta { min-width: 52px; text-align: right; font-size: 0.68rem; font-weight: 700; color: var(--text-3); font-variant-numeric: tabular-nums; }
    .bu__pista {
      position: relative;
      height: 18px;
      border-radius: 6px;
      overflow: hidden;
      background: linear-gradient(90deg,
        var(--success-soft) 0 var(--ref),
        var(--warning-soft) var(--ref) var(--warn),
        var(--danger-soft) var(--warn) 100%);
    }
    .bu__barra {
      position: absolute; left: 0; top: 5px; bottom: 5px;
      border-radius: 4px;
      background: linear-gradient(90deg, color-mix(in srgb, var(--c) 70%, transparent), var(--c));
      transform-origin: left; animation: bu-crece 800ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }
    .bu__linea { position: absolute; top: 2px; bottom: 2px; left: var(--ref); width: 3px; margin-left: -1.5px; border-radius: 2px; background: var(--text); }
    .bu__detalle { font-size: 0.7rem; color: var(--text-3); }
    @keyframes bu-crece { from { transform: scaleX(0); } to { transform: scaleX(1); } }
  `,
})
export class BulletChart {
  readonly items = input<ItemBullet[]>([]);
  /** Referencia (promedio general) en la misma unidad que `valor`. */
  readonly referencia = input<number>(0);
  readonly textoReferencia = input<string>('—');
  readonly maximo = input<number>(8);

  private readonly escala = computed(() => {
    const max = Math.max(this.referencia() * 1.6, ...this.items().map((i) => i.valor));
    return max > 0 ? max * 1.05 : 1;
  });

  readonly refPct = computed(() => (this.referencia() / this.escala()) * 100);
  readonly warnPct = computed(() => Math.min(100, ((this.referencia() * 1.5) / this.escala()) * 100));

  readonly filas = computed(() => {
    const ref = this.referencia();
    return [...this.items()]
      .sort((a, b) => b.valor - a.valor)
      .slice(0, this.maximo())
      .map((i) => {
        const estado = !ref || i.valor <= ref ? 'ok' : i.valor <= ref * 1.5 ? 'warn' : 'mal';
        const rel = ref ? (i.valor - ref) / ref : 0;
        return {
          ...i,
          estado,
          estadoTexto: estado === 'ok' ? 'Dentro del promedio' : estado === 'warn' ? 'Por encima del promedio' : 'Crítico',
          pct: (i.valor / this.escala()) * 100,
          delta: ref ? `${rel > 0 ? '+' : ''}${porcentaje(rel)}` : '',
        };
      });
  });
}
