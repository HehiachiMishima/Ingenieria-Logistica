import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { entero, porcentaje } from '../../../../../../shared/ui/charts/formato';
import { Reincidencia } from '../../../data/analitica.model';

/**
 * ¿Lo resolvimos o volvió? Proporción de casos cuyo mismo tipo ya había
 * ocurrido en el mismo centro dentro de la ventana (7 días), y las
 * combinaciones centro + tipo que más reinciden. Todo dentro del filtro.
 */
@Component({
  selector: 'app-reincidencias',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (vista(); as v) {
      <div class="re">
        <div class="re__aro" role="img" [attr.aria-label]="v.pct + ' de los casos reinciden'">
          <svg viewBox="0 0 42 42" aria-hidden="true">
            <circle class="re__pista" cx="21" cy="21" r="15.9155" />
            <circle
              class="re__seg"
              cx="21"
              cy="21"
              r="15.9155"
              [attr.stroke-dasharray]="v.dash"
            />
          </svg>
          <div class="re__centro">
            <span class="re__valor">{{ v.pct }}</span>
            <span class="re__nombre">reinciden</span>
          </div>
        </div>
        <div class="re__lado">
          <p class="re__resumen">
            <strong>{{ v.reincidentes }}</strong> de {{ v.total }} casos repitieron el mismo tipo en el mismo centro
            en {{ v.ventana }} días o menos.
          </p>
          @if (v.repetidos.length > 0) {
            <h3 class="re__sub">Lo que más vuelve</h3>
            <ol class="re__lista">
              @for (r of v.repetidos; track r.centro + r.tipo) {
                <li class="re__fila">
                  <span class="re__lbl" [title]="r.centro + ' · ' + r.tipo + ' (' + r.sistema + ')'">
                    <strong>{{ r.centro }}</strong> · {{ r.tipo }}
                  </span>
                  <span class="re__num" [class.re__num--alto]="r.alto" [title]="r.titulo">{{ r.texto }}</span>
                  <span class="re__mini"><span [style.width.%]="r.relativo"></span></span>
                  <span class="re__det">{{ r.sistema }} · {{ r.casosTexto }}</span>
                </li>
              }
            </ol>
          } @else {
            <p class="re__ok">Ningún caso volvió a repetirse en el periodo.</p>
          }
        </div>
      </div>
    } @else {
      <div class="dash-card__vacio">Sin incidencias para medir reincidencias.</div>
    }
  `,
  styles: `
    :host { display: block; min-width: 0; container-type: inline-size; }
    .re { display: grid; grid-template-columns: minmax(130px, 180px) minmax(0, 1fr); align-items: center; gap: var(--space-5); }
    @container (max-width: 440px) { .re { grid-template-columns: 1fr; justify-items: center; } .re__lado { width: 100%; } }
    .re__aro { position: relative; width: 100%; max-width: 180px; aspect-ratio: 1; }
    svg { width: 100%; height: 100%; transform: rotate(-90deg); overflow: visible; }
    .re__pista { fill: none; stroke: var(--surface-3); stroke-width: 5.5; }
    .re__seg { fill: none; stroke: var(--danger); stroke-width: 5.5; stroke-linecap: round; animation: re-entra 900ms cubic-bezier(0.16, 1, 0.3, 1) both; }
    .re__centro { position: absolute; inset: 22%; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; pointer-events: none; }
    .re__valor { font-family: var(--font-display); font-size: clamp(1.3rem, 1rem + 1.5cqi, 1.9rem); font-weight: 800; letter-spacing: -0.03em; line-height: 1; color: var(--text); }
    .re__nombre { margin-top: 2px; font-size: 0.66rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-3); }
    .re__lado { min-width: 0; }
    .re__resumen { font-size: var(--fs-sm); color: var(--text-2); }
    .re__resumen strong { color: var(--danger); }
    .re__sub { margin: var(--space-3) 0 var(--space-2); font-size: 0.62rem; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: var(--table-head-fg); }
    .re__lista { display: flex; flex-direction: column; gap: 4px; }
    .re__fila {
      display: grid; grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: 'lbl num' 'mini mini' 'det det';
      align-items: center; column-gap: var(--space-2); row-gap: 3px;
      padding: 6px 8px; border-radius: var(--radius-sm);
      transition: background var(--transition-fast);
    }
    .re__fila:hover { background: var(--surface-2); }
    .re__lbl { grid-area: lbl; min-width: 0; font-size: var(--fs-sm); color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .re__lbl strong { color: var(--text); }
    .re__num { grid-area: num; font-size: var(--fs-sm); font-weight: 800; color: var(--warning); font-variant-numeric: tabular-nums; white-space: nowrap; }
    .re__num--alto { color: var(--danger); }
    .re__mini { grid-area: mini; height: 4px; border-radius: 4px; background: var(--surface-3); overflow: hidden; }
    .re__mini span { display: block; height: 100%; border-radius: inherit; background: var(--danger); }
    .re__det { grid-area: det; font-size: 0.68rem; color: var(--text-3); }
    .re__ok { margin-top: var(--space-2); font-size: var(--fs-sm); font-weight: 700; color: var(--success); }
    @keyframes re-entra { from { stroke-dasharray: 0 100; } }
  `,
})
export class Reincidencias {
  readonly datos = input<Reincidencia | null>(null);

  readonly vista = computed(() => {
    const d = this.datos();
    if (!d || d.total === 0) {
      return null;
    }
    const fraccion = d.reincidentes / d.total;
    const pct = fraccion * 100;
    const max = Math.max(1, ...d.repetidos.map((r) => r.reincidencias));
    return {
      pct: porcentaje(fraccion),
      dash: `${pct > 0 ? Math.max(pct, 0.5) : 0} 100`,
      total: entero(d.total),
      reincidentes: entero(d.reincidentes),
      ventana: d.ventanaDias,
      repetidos: d.repetidos.map((r) => ({
        ...r,
        texto: `${entero(r.reincidencias)}×`,
        titulo: `${entero(r.reincidencias)} veces volvió en ${d.ventanaDias} días o menos`,
        casosTexto: `${entero(r.casos)} casos en el periodo`,
        relativo: (r.reincidencias / max) * 100,
        alto: r.reincidencias >= 3,
      })),
    };
  });
}
