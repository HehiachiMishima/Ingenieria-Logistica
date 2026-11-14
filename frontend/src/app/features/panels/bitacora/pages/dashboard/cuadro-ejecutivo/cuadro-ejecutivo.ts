import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { mensajeDeError } from '../../../../../../core/errors/error.util';
import { duracion, porcentaje } from '../../../../../../shared/ui/charts/formato';
import { Icon } from '../../../../../../shared/ui/icon/icon';
import { CasoLargo, FilaEjecutiva } from '../../../data/analitica.model';
import { IncidenciaFiltros } from '../../../data/bitacora.model';
import { IncidenciasApi } from '../../../data/incidencias.api';
import { filasCuadro, totalesCuadro } from './cuadro-ejecutivo.logica';

type Detalle =
  | { estado: 'cargando' }
  | { estado: 'ok'; casos: CasoLargo[] }
  | { estado: 'error'; mensaje: string };

/**
 * Cuadro ejecutivo por sistema con el mismo lenguaje visual que la matriz
 * centro × sistema: intensidad por columna, pico de cada columna remarcado y
 * fila de totales. Cada fila se despliega con sus casos más largos, pedidos al
 * servidor con LOS MISMOS filtros del dashboard (`filtros`).
 */
@Component({
  selector: 'app-cuadro-ejecutivo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="ce">
      <div class="ce__scroll">
        <table class="ce__tabla">
          <thead>
            <tr>
              <th class="ce__esquina" scope="col">Sistema</th>
              <th class="ce__col" scope="col">Casos</th>
              <th class="ce__col" scope="col">Horas</th>
              <th class="ce__col" scope="col">% horas</th>
              <th class="ce__col" scope="col">MTTR</th>
              <th class="ce__col" scope="col" title="Caso más largo">Más largo</th>
            </tr>
          </thead>
          <tbody>
            @for (f of filas(); track f.id) {
              <tr class="ce__fila" [class.ce__fila--abierta]="abierto() === f.id">
                <th class="ce__nombre" scope="row">
                  <button
                    type="button"
                    class="ce__toggle"
                    [attr.aria-expanded]="abierto() === f.id"
                    [attr.aria-controls]="'ce-det-' + f.id"
                    [title]="'Ver los casos más largos de ' + f.etiqueta"
                    (click)="alternar(f.id)"
                  >
                    <ui-icon name="chevronRight" [size]="14" class="ce__chev" />
                    <span class="ce__etq">{{ f.etiqueta }}</span>
                  </button>
                </th>
                @for (c of f.celdas; track c.clave) {
                  <td
                    class="ce__celda"
                    [class.ce__celda--pico]="c.pico"
                    [class.ce__celda--fuerte]="c.intensidad > 0.5"
                    [style.--i]="c.intensidad"
                    [attr.title]="f.etiqueta + ' · ' + c.nombre + ': ' + c.texto"
                    (mouseenter)="foco.set(f.etiqueta + ' · ' + c.nombre + ': ' + c.texto)"
                    (mouseleave)="foco.set(null)"
                  >
                    {{ c.texto }}
                  </td>
                  @if (c.clave === 'horas') {
                    <td class="ce__plano">{{ f.pctHoras }}</td>
                  }
                }
              </tr>
              @if (abierto() === f.id) {
                <tr class="ce__det-fila">
                  <td colspan="6" [id]="'ce-det-' + f.id" class="ce__det">
                    @switch (detalleDe(f.id)?.estado) {
                      @case ('ok') {
                        @let casos = casosDe(f.id);
                        @if (casos.length === 0) {
                          <p class="ce__nota">No hay casos resueltos de {{ f.etiqueta }} con estos filtros.</p>
                        } @else {
                          <p class="ce__nota">
                            Los {{ casos.length }} casos más largos de <strong>{{ f.etiqueta }}</strong> con los filtros
                            actuales:
                          </p>
                          <ol class="ce__casos">
                            @for (c of casos; track c.ticket) {
                              <li class="ce__caso">
                                <div class="ce__caso-linea">
                                  <span class="ce__ticket">{{ c.ticket }}</span>
                                  <span class="ce__desc"></span>
                                  <strong class="ce__dur">{{ duracion(c.segundos) }}</strong>
                                </div>
                                <div class="ce__caso-meta">
                                  <span>{{ fecha(c.fecha) }} · {{ hora(c.horaInicio) }} → {{ hora(c.horaFin) }}</span>
                                  <span>{{ c.centro }} · {{ c.responsable }}</span>
                                  <span class="ce__mini" [attr.aria-label]="pctDeHoras(c.segundos, f.segundos) + ' de las horas del sistema'">
                                    <span [style.width.%]="anchoDe(c.segundos, f.segundos)"></span>
                                  </span>
                                  <span class="ce__pct">{{ pctDeHoras(c.segundos, f.segundos) }}</span>
                                </div>
                              </li>
                            }
                          </ol>
                        }
                      }
                      @case ('error') {
                        <p class="ce__nota ce__nota--error">
                          {{ mensajeDe(f.id) }}
                          <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="pedir(f.id)">Reintentar</button>
                        </p>
                      }
                      @default {
                        <p class="ce__nota">Cargando casos…</p>
                      }
                    }
                  </td>
                </tr>
              }
            }
          </tbody>
          <tfoot>
            <tr>
              <th class="ce__nombre ce__nombre--total" scope="row">Total</th>
              <td class="ce__total">{{ totales().casos }}</td>
              <td class="ce__total">{{ totales().horas }}</td>
              <td class="ce__total">100 %</td>
              <td class="ce__total">{{ totales().mttr }}</td>
              <td class="ce__total ce__total--gran">{{ totales().maximo }}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      <div class="ce__pie">
        <span class="ce__escala"><i></i> menos <b></b> más</span>
        <span class="ce__foco">
          {{ foco() ?? 'Haz clic en un sistema para ver sus casos más largos. La celda remarcada es el pico de cada columna.' }}
        </span>
      </div>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .ce__scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: var(--radius-lg); }
    .ce__tabla { width: 100%; border-collapse: separate; border-spacing: 0; font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
    th, td { padding: 0; }
    .ce__esquina, .ce__nombre {
      position: sticky; left: 0; z-index: 1;
      min-width: 120px; max-width: 180px;
      text-align: left; font-weight: 700; color: var(--text);
      background: var(--surface);
      border-right: 1px solid var(--border);
    }
    .ce__esquina { padding: 8px 10px; font-size: 0.62rem; letter-spacing: 0.06em; text-transform: uppercase; color: var(--table-head-fg); background: var(--table-head-bg); border-bottom: 2px solid var(--brand); }
    .ce__col {
      min-width: 64px; padding: 8px 6px;
      font-size: 0.62rem; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase; white-space: nowrap;
      color: var(--table-head-fg); background: var(--table-head-bg);
      border-bottom: 2px solid var(--brand);
    }
    .ce__toggle {
      display: flex; align-items: center; gap: 6px; width: 100%; min-height: 34px;
      padding: 6px 10px; border: 0; background: none; font: inherit; font-weight: 700; color: inherit;
      text-align: left; cursor: pointer;
    }
    .ce__toggle:hover .ce__etq, .ce__toggle:focus-visible .ce__etq { color: var(--accent); }
    .ce__toggle:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
    .ce__chev { flex: none; color: var(--text-3); transition: transform var(--transition-fast); }
    .ce__fila--abierta .ce__chev { transform: rotate(90deg); color: var(--accent); }
    .ce__etq { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .ce__nombre--total { padding: 8px 10px; background: var(--surface-2); }
    .ce__celda {
      height: 34px; padding: 0 8px;
      text-align: center; font-weight: 700; white-space: nowrap;
      color: var(--text-2);
      background: color-mix(in srgb, var(--brand) calc(var(--i) * 85%), var(--surface));
      border-bottom: 1px solid var(--surface); border-right: 1px solid var(--surface);
      outline: 2px solid transparent; outline-offset: -2px;
      transition: outline-color var(--transition-fast);
    }
    .ce__celda--fuerte { color: #fff; }
    .ce__celda:hover { outline-color: var(--accent); }
    .ce__celda--pico { box-shadow: inset 0 0 0 2px var(--text); }
    .ce__plano { padding: 0 8px; text-align: center; font-weight: 700; white-space: nowrap; color: var(--text-2); border-bottom: 1px solid var(--border); }
    .ce__total { padding: 8px; text-align: center; font-weight: 800; white-space: nowrap; color: var(--text); background: var(--surface-2); }
    .ce__total--gran { color: #fff; background: var(--chrome-bg-2); }
    .ce__det { padding: var(--space-3) var(--space-4); background: var(--surface-2); border-bottom: 1px solid var(--border); }
    .ce__nota { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2); font-size: var(--fs-xs); color: var(--text-3); }
    .ce__nota strong { color: var(--text); }
    .ce__nota--error { color: var(--danger); }
    .ce__casos { display: flex; flex-direction: column; gap: 6px; margin-top: var(--space-2); }
    .ce__caso { padding: 6px 8px; border-radius: var(--radius-sm); background: var(--surface); border: 1px solid var(--border); }
    .ce__caso-linea { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: baseline; gap: var(--space-2); }
    .ce__ticket { font-family: var(--font-mono); font-size: 0.68rem; color: var(--text-3); }
    .ce__desc { min-width: 0; font-size: var(--fs-xs); font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .ce__dur { font-size: var(--fs-sm); font-weight: 800; color: var(--text); white-space: nowrap; }
    .ce__caso-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px var(--space-3); margin-top: 4px; font-size: 0.68rem; color: var(--text-3); }
    .ce__mini { flex: 1 1 80px; min-width: 60px; height: 4px; border-radius: 4px; background: var(--surface-3); overflow: hidden; }
    .ce__mini span { display: block; height: 100%; border-radius: inherit; background: var(--brand); }
    .ce__pct { font-weight: 700; font-variant-numeric: tabular-nums; }
    .ce__pie { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--space-2); margin-top: var(--space-3); font-size: var(--fs-xs); color: var(--text-3); }
    .ce__escala { display: inline-flex; align-items: center; gap: 6px; }
    .ce__escala i, .ce__escala b { display: inline-block; width: 18px; height: 10px; border-radius: 3px; }
    .ce__escala i { background: color-mix(in srgb, var(--brand) 10%, var(--surface)); border: 1px solid var(--border); }
    .ce__escala b { background: var(--brand); }
  `,
})
export class CuadroEjecutivo {
  private readonly api = inject(IncidenciasApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly datos = input<FilaEjecutiva[]>([]);
  /** Filtros con los que se calculó `datos`: el detalle se pide con los mismos. */
  readonly filtros = input<IncidenciaFiltros>({});
  /** MTTR general del filtro (KPI), para la fila de totales. */
  readonly mttrGeneral = input<number>(0);
  readonly maxFilas = input<number>(10);

  readonly foco = signal<string | null>(null);

  /** Fila abierta y detalle por sistema: se reinician cuando llegan datos nuevos (otro filtro). */
  readonly abierto = linkedSignal<FilaEjecutiva[], number | null>({
    source: this.datos,
    computation: () => null,
  });
  private readonly detalles = linkedSignal<FilaEjecutiva[], ReadonlyMap<number, Detalle>>({
    source: this.datos,
    computation: () => new Map(),
  });

  readonly filas = computed(() => filasCuadro(this.datos(), this.maxFilas()));
  readonly totales = computed(() => totalesCuadro(this.datos(), this.mttrGeneral()));

  readonly duracion = duracion;

  alternar(id: number): void {
    if (this.abierto() === id) {
      this.abierto.set(null);
      return;
    }
    this.abierto.set(id);
    const actual = this.detalles().get(id);
    if (!actual || actual.estado === 'error') {
      this.pedir(id);
    }
  }

  pedir(id: number): void {
    // Una respuesta de un filtro anterior no debe pintar el detalle del actual.
    const datosAlPedir = this.datos();
    this.fijar(id, { estado: 'cargando' });
    this.api
      .casosLargos(id, this.filtros(), 3)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (casos) => {
          if (this.datos() === datosAlPedir) {
            this.fijar(id, { estado: 'ok', casos });
          }
        },
        error: (e) => {
          if (this.datos() === datosAlPedir) {
            this.fijar(id, { estado: 'error', mensaje: mensajeDeError(e) });
          }
        },
      });
  }

  detalleDe(id: number): Detalle | undefined {
    return this.detalles().get(id);
  }

  casosDe(id: number): CasoLargo[] {
    const d = this.detalles().get(id);
    return d?.estado === 'ok' ? d.casos : [];
  }

  mensajeDe(id: number): string {
    const d = this.detalles().get(id);
    return d?.estado === 'error' ? d.mensaje : '';
  }

  pctDeHoras(segundos: number, totalSistema: number): string {
    return porcentaje(totalSistema > 0 ? segundos / totalSistema : 0);
  }

  anchoDe(segundos: number, totalSistema: number): number {
    return totalSistema > 0 ? Math.min(100, (segundos / totalSistema) * 100) : 0;
  }

  /** «2026-10-07» → «07/10/2026» sin pasar por Date (no corre el día por zona). */
  fecha(dia: string): string {
    const [a, m, d] = dia.split('-');
    return `${d}/${m}/${a}`;
  }

  hora(valor: string): string {
    return valor?.slice(0, 5) ?? '';
  }

  private fijar(id: number, detalle: Detalle): void {
    this.detalles.update((mapa) => new Map(mapa).set(id, detalle));
  }
}
