import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { entero, porcentaje } from './formato';

export interface CeldaCalor {
  fila: string;
  columna: string;
  valor: number;
}

/**
 * Mapa de calor fila × columna (p. ej. proveedor × tipo, centro × sistema).
 *
 * - Filas y columnas se ordenan por total y se limitan a las más relevantes
 *   (el resto se suma en «Otros») para que la matriz siga siendo legible.
 * - Intensidad = valor / máximo de la matriz; la celda pico de cada fila se
 *   resalta, y hay totales por fila y columna.
 * - En pantallas estrechas la matriz se desplaza en horizontal con la
 *   primera columna fija; nunca se deforma.
 */
@Component({
  selector: 'ui-heatmap',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="hm">
      <div class="hm__scroll">
        <table class="hm__tabla">
          <thead>
            <tr>
              <th class="hm__esquina">{{ tituloFilas() }} \\ {{ tituloColumnas() }}</th>
              @for (c of columnas(); track c) {
                <th class="hm__col" [title]="c"><span>{{ c }}</span></th>
              }
              <th class="hm__total-h">Total</th>
            </tr>
          </thead>
          <tbody>
            @for (f of matriz(); track f.fila) {
              <tr>
                <th class="hm__fila" [title]="f.fila">{{ f.fila }}</th>
                @for (v of f.valores; track $index; let j = $index) {
                  <td
                    class="hm__celda"
                    [class.hm__celda--pico]="v > 0 && v === f.pico"
                    [class.hm__celda--fuerte]="v / maximo() > 0.5"
                    [style.--i]="v / maximo()"
                    [attr.title]="f.fila + ' · ' + columnas()[j] + ': ' + v"
                    (mouseenter)="foco.set({ fila: f.fila, columna: columnas()[j], valor: v, total: f.total })"
                    (mouseleave)="foco.set(null)"
                  >
                    {{ v > 0 ? v : '' }}
                  </td>
                }
                <td class="hm__total">{{ f.totalTexto }}</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th class="hm__fila">Total</th>
              @for (t of totalesColumna(); track $index) {
                <td class="hm__total">{{ t }}</td>
              }
              <td class="hm__total hm__total--gran">{{ granTotal() }}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      <div class="hm__pie">
        <span class="hm__escala"><i></i> menos <b></b> más</span>
        @if (foco(); as f) {
          <span class="hm__foco">
            <strong>{{ f.fila }}</strong> · {{ f.columna }}: <strong>{{ f.valor }}</strong>
            ({{ pctFila(f.valor, f.total) }} de la fila)
          </span>
        } @else {
          <span class="hm__foco">Pasa el cursor por una celda para ver el detalle. La celda remarcada es el pico de cada fila.</span>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .hm__scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: var(--radius-lg); }
    .hm__tabla { width: 100%; border-collapse: separate; border-spacing: 0; font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
    th, td { padding: 0; }
    .hm__esquina, .hm__fila {
      position: sticky; left: 0; z-index: 1;
      max-width: 180px; min-width: 120px;
      padding: 8px 10px;
      text-align: left;
      font-weight: 700;
      color: var(--text);
      background: var(--surface);
      border-right: 1px solid var(--border);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .hm__esquina { font-size: 0.62rem; letter-spacing: 0.06em; text-transform: uppercase; color: var(--table-head-fg); background: var(--table-head-bg); }
    .hm__col, .hm__total-h {
      min-width: 56px; max-width: 110px;
      padding: 8px 6px;
      font-size: 0.62rem; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase;
      color: var(--table-head-fg); background: var(--table-head-bg);
      border-bottom: 2px solid var(--brand);
    }
    .hm__col span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .hm__celda {
      height: 34px;
      text-align: center;
      font-weight: 700;
      color: var(--text-2);
      background: color-mix(in srgb, var(--brand) calc(var(--i) * 85%), var(--surface));
      border-bottom: 1px solid var(--surface);
      border-right: 1px solid var(--surface);
      transition: outline-color var(--transition-fast);
      outline: 2px solid transparent;
      outline-offset: -2px;
      cursor: default;
    }
    .hm__celda--fuerte { color: #fff; }
    .hm__celda:hover { outline-color: var(--accent); }
    .hm__celda--pico { box-shadow: inset 0 0 0 2px var(--text); }
    .hm__total { text-align: center; font-weight: 800; color: var(--text); background: var(--surface-2); padding: 0 8px; }
    .hm__total--gran { color: #fff; background: var(--chrome-bg-2); }
    tfoot .hm__fila { background: var(--surface-2); }
    .hm__pie { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--space-2); margin-top: var(--space-3); font-size: var(--fs-xs); color: var(--text-3); }
    .hm__escala { display: inline-flex; align-items: center; gap: 6px; }
    .hm__escala i, .hm__escala b { display: inline-block; width: 18px; height: 10px; border-radius: 3px; }
    .hm__escala i { background: color-mix(in srgb, var(--brand) 10%, var(--surface)); border: 1px solid var(--border); }
    .hm__escala b { background: var(--brand); }
    .hm__foco strong { color: var(--text); }
  `,
})
export class Heatmap {
  readonly celdas = input<CeldaCalor[]>([]);
  readonly maxFilas = input<number>(12);
  readonly maxColumnas = input<number>(10);
  readonly tituloFilas = input<string>('Fila');
  readonly tituloColumnas = input<string>('Columna');

  readonly foco = signal<{ fila: string; columna: string; valor: number; total: number } | null>(null);

  private totales(clave: 'fila' | 'columna'): Map<string, number> {
    const mapa = new Map<string, number>();
    for (const c of this.celdas()) {
      mapa.set(c[clave], (mapa.get(c[clave]) ?? 0) + c.valor);
    }
    return mapa;
  }

  private seleccion(clave: 'fila' | 'columna', maximo: number): { visibles: string[]; hayOtros: boolean } {
    const orden = [...this.totales(clave).entries()].sort((a, b) => b[1] - a[1]).map(([k]) => k);
    return { visibles: orden.slice(0, maximo), hayOtros: orden.length > maximo };
  }

  readonly columnas = computed(() => {
    const { visibles, hayOtros } = this.seleccion('columna', this.maxColumnas());
    return hayOtros ? [...visibles, 'Otros'] : visibles;
  });

  readonly matriz = computed(() => {
    const columnas = this.columnas();
    const indiceCol = new Map(columnas.map((c, i) => [c, i]));
    const { visibles, hayOtros } = this.seleccion('fila', this.maxFilas());
    const filas = hayOtros ? [...visibles, 'Otros'] : visibles;
    const indiceFila = new Map(filas.map((f, i) => [f, i]));
    const valores = filas.map(() => columnas.map(() => 0));
    for (const c of this.celdas()) {
      const i = indiceFila.get(c.fila) ?? (hayOtros ? filas.length - 1 : -1);
      const j = indiceCol.get(c.columna) ?? indiceCol.get('Otros') ?? -1;
      if (i >= 0 && j >= 0) {
        valores[i][j] += c.valor;
      }
    }
    return filas.map((fila, i) => {
      const total = valores[i].reduce((a, b) => a + b, 0);
      return { fila, valores: valores[i], pico: Math.max(...valores[i]), total, totalTexto: entero(total) };
    });
  });

  readonly maximo = computed(() => Math.max(1, ...this.matriz().flatMap((f) => f.valores)));

  readonly totalesColumna = computed(() =>
    this.columnas().map((_, j) => entero(this.matriz().reduce((t, f) => t + f.valores[j], 0))),
  );

  readonly granTotal = computed(() => entero(this.matriz().reduce((t, f) => t + f.total, 0)));

  pctFila(valor: number, total: number): string {
    return porcentaje(total ? valor / total : 0);
  }
}
