import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { entero } from '../../../../../../shared/ui/charts/formato';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { AbiertasBitacora } from '../../../data/analitica.model';
import { edad, relojPorMinuto, segundosDesde } from '../../../data/estado-incidencia';

/**
 * Sexto KPI del dashboard: incidencias abiertas ahora (con los filtros del
 * dashboard), su antigüedad en tres tramos y cuánto lleva la más antigua, que
 * avanza sola cada minuto. Mismo lenguaje visual que `ui-kpi`; al hacer clic
 * abre el listado filtrado en abiertas.
 */
@Component({
  selector: 'app-kpi-abiertas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink],
  template: `
    <a
      class="ka"
      [class.ka--hay]="hay()"
      routerLink="/bitacora/incidencias"
      [queryParams]="{ estado: 'ABIERTA' }"
      [attr.aria-label]="titulo()"
      [title]="titulo()"
    >
      <div class="ka__top">
        <span class="ka__icon"><ui-icon name="reloj" [size]="20" /></span>
        <span class="ka__label">
          @if (hay()) {
            <span class="ka__pulso" aria-hidden="true"></span>
          }
          Abiertas ahora
        </span>
      </div>
      @if (cargando()) {
        <span class="ka__skeleton ui-skeleton"></span>
      } @else {
        <span class="ka__value">{{ total() }}</span>
      }
      <div class="ka__foot">
        @if (hay()) {
          <span class="ka__barra" aria-hidden="true">
            <span class="ka__tramo ka__tramo--reciente" [style.flex-grow]="datos()!.menosDeUnaHora"></span>
            <span class="ka__tramo ka__tramo--media" [style.flex-grow]="datos()!.deUnaACuatroHoras"></span>
            <span class="ka__tramo ka__tramo--antigua" [style.flex-grow]="datos()!.masDeCuatroHoras"></span>
          </span>
          <span class="ka__detail">más antigua hace {{ masAntigua() }} · ver →</span>
        } @else {
          <span class="ka__detail">Ninguna abierta con estos filtros</span>
        }
      </div>
    </a>
  `,
  styles: `
    :host { display: block; min-width: 0; }

    .ka {
      --ka: var(--success);
      position: relative;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      height: 100%;
      padding: var(--space-4) var(--space-5);
      color: inherit;
      text-decoration: none;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-xl);
      box-shadow: var(--shadow-sm);
      overflow: hidden;
      animation: ui-rise var(--transition-slow) both;
      transition: border-color var(--transition-fast), transform var(--transition-fast);
    }
    .ka--hay { --ka: var(--danger); border-color: color-mix(in srgb, var(--danger) 30%, var(--border)); }
    .ka:hover { transform: translateY(-1px); border-color: var(--ka); }
    .ka:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

    .ka::before { content: ''; position: absolute; inset: 0 0 auto 0; height: 3px; background: var(--ka); }

    .ka__top { display: flex; align-items: center; gap: var(--space-3); min-width: 0; }
    .ka__icon {
      display: inline-flex; align-items: center; justify-content: center;
      width: 38px; height: 38px; flex: none;
      border-radius: var(--radius);
      color: var(--ka);
      background: color-mix(in srgb, var(--ka) 13%, transparent);
    }
    .ka__label {
      display: inline-flex; align-items: center; gap: 6px;
      font-size: var(--fs-xs); font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase;
      line-height: 1.3; color: var(--text-3);
    }
    .ka--hay .ka__label { color: var(--danger); }

    .ka__pulso {
      width: 7px; height: 7px; flex: none; border-radius: 50%;
      background: var(--danger);
      animation: ka-pulso 1.8s ease-out infinite;
    }
    @keyframes ka-pulso {
      0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--danger) 55%, transparent); }
      70%, 100% { box-shadow: 0 0 0 6px transparent; }
    }
    @media (prefers-reduced-motion: reduce) {
      .ka__pulso { animation: none; }
      .ka:hover { transform: none; }
    }

    .ka__value {
      font-family: var(--font-display);
      font-size: clamp(1.5rem, 1.1rem + 1.2vw, 2.1rem);
      font-weight: 800; letter-spacing: -0.035em; line-height: 1.05;
      color: var(--text);
      font-variant-numeric: tabular-nums;
    }
    .ka--hay .ka__value { color: var(--danger); }
    .ka__skeleton { display: block; height: 34px; width: 60%; }

    .ka__foot { display: flex; flex-direction: column; gap: 6px; margin-top: auto; }
    .ka__barra { display: flex; height: 6px; border-radius: 3px; overflow: hidden; background: var(--surface-3); }
    .ka__tramo { flex: 0 1 0; min-width: 0; }
    .ka__tramo--reciente { background: var(--success); }
    .ka__tramo--media { background: var(--warning); }
    .ka__tramo--antigua { background: var(--danger); }
    .ka__detail { font-size: var(--fs-xs); color: var(--text-3); }

    @media (max-width: 767.98px) {
      .ka { padding: var(--space-3) var(--space-4); }
      .ka__icon { width: 32px; height: 32px; }
      .ka__top { flex-direction: column; align-items: flex-start; gap: var(--space-2); }
      .ka__label { font-size: 0.64rem; letter-spacing: 0.04em; }
    }
  `,
})
export class KpiAbiertas {
  readonly datos = input<AbiertasBitacora | null>(null);
  readonly cargando = input<boolean>(false);

  /** Avanza cada minuto: la antigüedad de la más antigua se actualiza sola. */
  private readonly ahora = relojPorMinuto();

  readonly hay = computed(() => (this.datos()?.total ?? 0) > 0);
  readonly total = computed(() => (this.datos() ? entero(this.datos()!.total) : '—'));

  readonly masAntigua = computed(() => {
    const desde = this.datos()?.desdeMasAntigua;
    return desde ? edad(segundosDesde(desde, this.ahora())) : '—';
  });

  readonly titulo = computed(() => {
    const d = this.datos();
    if (!d || d.total === 0) {
      return 'No hay incidencias abiertas con estos filtros';
    }
    return (
      `${entero(d.total)} abiertas: ${d.menosDeUnaHora} de menos de 1 h, ${d.deUnaACuatroHoras} de 1 a 4 h ` +
      `y ${d.masDeCuatroHoras} de más de 4 h. La más antigua (${d.ticketMasAntiguo ?? '—'}) lleva ${this.masAntigua()}.`
    );
  });
}
