import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Icon } from '../icon/icon';
import { porcentaje } from './formato';

/** Datos de una tarjeta KPI tal como los arman los dashboards. */
export interface TarjetaKpi {
  etiqueta: string;
  valor: string;
  icono: string;
  color: string;
  detalle: string;
  variacion: number | null;
}

/**
 * Tarjeta KPI: etiqueta, valor principal, detalle y (opcional) variación vs el
 * periodo anterior. `subirEsMalo` invierte el color de la variación: en
 * incidencias, subir es una mala noticia (rojo).
 */
@Component({
  selector: 'ui-kpi',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <article class="kpi" [style.--kpi]="color()">
      <div class="kpi__top">
        <span class="kpi__icon"><ui-icon [name]="icono()" [size]="20" /></span>
        <span class="kpi__label">{{ etiqueta() }}</span>
      </div>
      @if (cargando()) {
        <span class="kpi__skeleton ui-skeleton"></span>
      } @else {
        <span class="kpi__value">{{ valor() }}</span>
      }
      <div class="kpi__foot">
        @if (delta(); as d) {
          <span
            class="kpi__delta"
            [class.kpi__delta--malo]="d.malo"
            [class.kpi__delta--bueno]="!d.malo && d.valor !== 0"
            [attr.title]="d.titulo"
          >
            <span aria-hidden="true">{{ d.valor > 0 ? '▲' : d.valor < 0 ? '▼' : '■' }}</span>
            {{ d.texto }}
          </span>
        }
        <span class="kpi__detail">{{ delta() ? 'vs periodo anterior · ' : '' }}{{ detalle() }}</span>
      </div>
    </article>
  `,
  styles: `
    :host { display: block; min-width: 0; }

    .kpi {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      height: 100%;
      padding: var(--space-4) var(--space-5);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-xl);
      box-shadow: var(--shadow-sm);
      overflow: hidden;
      animation: ui-rise var(--transition-slow) both;
    }

    .kpi::before {
      content: '';
      position: absolute;
      inset: 0 0 auto 0;
      height: 3px;
      background: var(--kpi);
    }

    .kpi::after {
      content: '';
      position: absolute;
      right: -40px;
      bottom: -40px;
      width: 120px;
      height: 120px;
      border-radius: 50%;
      background: radial-gradient(circle, color-mix(in srgb, var(--kpi) 14%, transparent), transparent 70%);
      pointer-events: none;
    }

    .kpi__top { display: flex; align-items: center; gap: var(--space-3); min-width: 0; }

    .kpi__icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 38px;
      height: 38px;
      flex: none;
      border-radius: var(--radius);
      color: var(--kpi);
      background: color-mix(in srgb, var(--kpi) 13%, transparent);
    }

    .kpi__label {
      font-size: var(--fs-xs);
      font-weight: 700;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      line-height: 1.3;
      overflow-wrap: normal;
      word-break: normal;
      color: var(--text-3);
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .kpi__value {
      font-family: var(--font-display);
      font-size: clamp(1.5rem, 1.1rem + 1.2vw, 2.1rem);
      font-weight: 800;
      letter-spacing: -0.035em;
      line-height: 1.05;
      color: var(--text);
      font-variant-numeric: tabular-nums;
      overflow-wrap: anywhere;
    }

    .kpi__skeleton { display: block; height: 34px; width: 60%; }

    .kpi__foot {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px var(--space-2);
      margin-top: auto;
    }

    .kpi__delta {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 8px;
      border-radius: var(--radius-pill);
      font-size: var(--fs-xs);
      font-weight: 700;
      color: var(--text-2);
      background: var(--surface-3);
      font-variant-numeric: tabular-nums;
    }

    .kpi__delta--malo { color: var(--danger); background: var(--danger-soft); }
    .kpi__delta--bueno { color: var(--success); background: var(--success-soft); }

    .kpi__detail { font-size: var(--fs-xs); color: var(--text-3); }

    @media (max-width: 767.98px) {
      .kpi { padding: var(--space-3) var(--space-4); }
      .kpi__icon { width: 32px; height: 32px; }
      .kpi__top { flex-direction: column; align-items: flex-start; gap: var(--space-2); }
      .kpi__label { font-size: 0.64rem; letter-spacing: 0.04em; }
    }
  `,
})
export class Kpi {
  readonly etiqueta = input.required<string>();
  readonly valor = input.required<string>();
  readonly icono = input<string>('dashboard');
  readonly color = input<string>('var(--accent)');
  readonly detalle = input<string>('');
  readonly cargando = input<boolean>(false);
  /** Variación relativa (0.12 = +12 %) vs el periodo anterior. */
  readonly variacion = input<number | null>(null);
  readonly subirEsMalo = input<boolean>(true);

  readonly delta = computed(() => {
    const v = this.variacion();
    if (v === null || !Number.isFinite(v)) {
      return null;
    }
    const signo = v > 0 ? '+' : '';
    return {
      valor: v,
      texto: `${signo}${porcentaje(v)}`,
      titulo: `${signo}${porcentaje(v)} respecto al periodo anterior de igual duración`,
      malo: this.subirEsMalo() ? v > 0 : v < 0,
    };
  });
}
