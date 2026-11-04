import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { compacto, entero, periodoCorto } from './formato';

export interface SerieLinea {
  nombre: string;
  color: string;
  /** Un valor por cada etiqueta del eje X (mismo orden). */
  valores: number[];
}

/**
 * Líneas/áreas multi-serie responsive.
 *
 * El área de trazado es un SVG con `preserveAspectRatio="none"` y trazos con
 * `vector-effect: non-scaling-stroke`, así se adapta a cualquier ancho sin
 * deformar las líneas; los textos (ejes, tooltip) son HTML y nunca se estiran.
 */
@Component({
  selector: 'ui-linea',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="lc" [style.--alto.px]="altura()">
      @if (leyenda() && series().length > 1) {
        <ul class="lc__leyenda">
          @for (s of series(); track s.nombre) {
            <li><span class="lc__punto" [style.background]="s.color"></span>{{ s.nombre }}</li>
          }
        </ul>
      }
      <div class="lc__cuerpo">
        <div class="lc__eje-y" aria-hidden="true">
          @for (t of ticks(); track $index) {
            <span [style.bottom.%]="t.pct">{{ t.texto }}</span>
          }
        </div>
        <div class="lc__plot" (mouseleave)="activo.set(null)">
          @for (t of ticks(); track $index) {
            <span class="lc__guia" [style.bottom.%]="t.pct"></span>
          }
          <svg viewBox="0 0 1000 100" preserveAspectRatio="none" aria-hidden="true">
            @for (s of trazos(); track s.nombre; let i = $index) {
              @if (i === 0 && area()) {
                <path class="lc__area" [attr.d]="s.area" [style.fill]="s.color" />
              }
              <path class="lc__linea" [attr.d]="s.linea" [style.stroke]="s.color" />
            }
          </svg>
          <div class="lc__hover">
            @for (x of etiquetas(); track $index; let i = $index) {
              <span
                class="lc__col"
                [class.lc__col--on]="activo() === i"
                (mouseenter)="activo.set(i)"
                (click)="activo.set(i)"
              ></span>
            }
          </div>
          @if (detalleActivo(); as d) {
            <div class="lc__tip" [style.left.%]="d.x" [class.lc__tip--izq]="d.x > 60">
              <strong>{{ d.etiqueta }}</strong>
              @for (v of d.valores; track v.nombre) {
                <span
                  ><i [style.background]="v.color"></i>{{ v.nombre }}: <b>{{ v.texto }}</b></span
                >
              }
            </div>
          }
        </div>
      </div>
      <div class="lc__eje-x" aria-hidden="true">
        @for (x of etiquetasVisibles(); track x.i) {
          <span [style.left.%]="x.pct">{{ x.texto }}</span>
        }
      </div>
      <p class="u-sr-only">{{ resumen() }}</p>
    </div>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .lc {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .lc__leyenda {
      display: flex;
      flex-wrap: wrap;
      gap: 6px 14px;
      font-size: var(--fs-xs);
      color: var(--text-2);
    }
    .lc__leyenda li {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-weight: 600;
    }
    .lc__punto {
      width: 10px;
      height: 10px;
      border-radius: 3px;
    }
    .lc__cuerpo {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: var(--space-2);
    }
    .lc__eje-y {
      position: relative;
      width: 40px;
      height: var(--alto);
      font-size: 0.68rem;
      color: var(--text-3);
    }
    .lc__eje-y span {
      position: absolute;
      right: 0;
      transform: translateY(50%);
      font-variant-numeric: tabular-nums;
    }
    .lc__plot {
      position: relative;
      height: var(--alto);
    }
    .lc__guia {
      position: absolute;
      left: 0;
      right: 0;
      height: 1px;
      background: var(--border);
    }
    svg {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      overflow: visible;
    }
    .lc__linea {
      fill: none;
      stroke-width: 2.5;
      vector-effect: non-scaling-stroke;
      stroke-linejoin: round;
      stroke-linecap: round;
    }
    .lc__area {
      opacity: 0.14;
    }
    .lc__hover {
      position: absolute;
      inset: 0;
      display: flex;
    }
    .lc__col {
      flex: 1;
      cursor: crosshair;
    }
    .lc__col--on {
      background: linear-gradient(
        180deg,
        transparent,
        color-mix(in srgb, var(--accent) 12%, transparent)
      );
      box-shadow: inset 1px 0 0 color-mix(in srgb, var(--accent) 50%, transparent);
    }
    .lc__tip {
      position: absolute;
      top: 6px;
      z-index: 2;
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 140px;
      padding: 8px 10px;
      transform: translateX(8px);
      font-size: var(--fs-xs);
      color: var(--text-2);
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      box-shadow: var(--shadow-lg);
      pointer-events: none;
      white-space: nowrap;
    }
    .lc__tip--izq {
      transform: translateX(calc(-100% - 8px));
    }
    .lc__tip strong {
      color: var(--text);
      font-size: var(--fs-sm);
    }
    .lc__tip i {
      display: inline-block;
      width: 8px;
      height: 8px;
      margin-right: 6px;
      border-radius: 2px;
    }
    .lc__tip b {
      color: var(--text);
      font-variant-numeric: tabular-nums;
    }
    .lc__eje-x {
      position: relative;
      height: 18px;
      margin-left: calc(40px + var(--space-2));
      font-size: 0.68rem;
      color: var(--text-3);
    }
    .lc__eje-x span {
      position: absolute;
      transform: translateX(-50%);
      white-space: nowrap;
    }
    .u-sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class LineChart {
  /** Etiquetas del eje X (periodos «yyyy-MM» o «yyyy-MM-dd», o texto libre). */
  readonly etiquetas = input<string[]>([]);
  readonly series = input<SerieLinea[]>([]);
  readonly altura = input<number>(220);
  readonly area = input<boolean>(true);
  readonly leyenda = input<boolean>(true);
  /** Formatea periodos ISO a «oct 26» / «07 oct». */
  readonly periodos = input<boolean>(true);
  /** Máximo de etiquetas visibles en el eje X (se reparten). */
  readonly maxEtiquetas = input<number>(8);

  readonly activo = signal<number | null>(null);

  private readonly maximo = computed(() => {
    const max = Math.max(0, ...this.series().flatMap((s) => s.valores));
    if (max <= 0) {
      return 1;
    }
    const paso = Math.pow(10, Math.floor(Math.log10(max)));
    return Math.ceil((max * 1.1) / paso) * paso;
  });

  readonly ticks = computed(() => {
    const max = this.maximo();
    return [0, 0.25, 0.5, 0.75, 1].map((f) => ({ pct: f * 100, texto: compacto(Math.round(max * f)) }));
  });

  private x(i: number): number {
    const n = this.etiquetas().length;
    return n <= 1 ? 500 : (i / (n - 1)) * 1000;
  }

  readonly trazos = computed(() => {
    const max = this.maximo();
    return this.series().map((s) => {
      const puntos = s.valores.map(
        (v, i) => `${this.x(i).toFixed(1)},${(100 - (v / max) * 100).toFixed(2)}`,
      );
      const linea = puntos.length ? `M${puntos.join(' L')}` : '';
      const area = puntos.length
        ? `${linea} L${this.x(puntos.length - 1)},100 L${this.x(0)},100 Z`
        : '';
      return { nombre: s.nombre, color: s.color, linea, area };
    });
  });

  private texto(etiqueta: string): string {
    return this.periodos() ? periodoCorto(etiqueta) : etiqueta;
  }

  readonly etiquetasVisibles = computed(() => {
    const todas = this.etiquetas();
    const n = todas.length;
    const paso = Math.max(1, Math.ceil(n / this.maxEtiquetas()));
    return todas
      .map((e, i) => ({ i, texto: this.texto(e), pct: n <= 1 ? 50 : (i / (n - 1)) * 100 }))
      .filter((e) => e.i % paso === 0 || e.i === n - 1);
  });

  readonly detalleActivo = computed(() => {
    const i = this.activo();
    const etiquetas = this.etiquetas();
    if (i === null || i >= etiquetas.length) {
      return null;
    }
    const n = etiquetas.length;
    return {
      x: n <= 1 ? 50 : (i / (n - 1)) * 100,
      etiqueta: this.texto(etiquetas[i]),
      valores: this.series().map((s) => ({
        nombre: s.nombre,
        color: s.color,
        texto: entero(s.valores[i] ?? 0),
      })),
    };
  });

  readonly resumen = computed(() => {
    return this.series()
      .map((s) => `${s.nombre}: total ${entero(s.valores.reduce((a, b) => a + b, 0))}`)
      .join('. ');
  });
}
