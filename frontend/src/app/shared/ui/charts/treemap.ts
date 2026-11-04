import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { entero, porcentaje } from './formato';

export interface ItemTreemap {
  etiqueta: string;
  valor: number;
  /** Texto de contexto (MTTR, unidades, tipo dominante…), visible al enfocar. */
  detalle?: string;
}

interface Bloque {
  etiqueta: string;
  valor: number;
  detalle?: string;
  x: number;
  y: number;
  w: number;
  h: number;
  rank: number;
  intensidad: number;
  vital: boolean;
  otros: boolean;
  pct: string;
  texto: string;
}

/** Proporción (ancho/alto) con la que se calcula el layout. */
const PROPORCION = 1.45;

/**
 * Treemap «squarified»: el área de cada bloque es proporcional a su volumen y
 * la intensidad de color crece con él (misma lógica visual que el mapa de
 * calor). Los bloques que juntos alcanzan el `umbral` (80 %) se marcan como
 * vitales. Al pasar el cursor (o tocar) se muestra el detalle abajo.
 */
@Component({
  selector: 'ui-treemap',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tm">
      <div class="tm__mapa" role="list" [attr.aria-label]="titulo()">
        @for (b of bloques(); track b.etiqueta) {
          <button
            type="button"
            role="listitem"
            class="tm__bloque"
            [class.tm__bloque--fuerte]="b.intensidad > 0.42"
            [class.tm__bloque--vital]="b.vital"
            [class.tm__bloque--otros]="b.otros"
            [class.tm__bloque--chico]="b.w * b.h < 260"
            [class.tm__bloque--bajo]="b.h < 16"
            [class.tm__bloque--on]="foco()?.etiqueta === b.etiqueta"
            [style.left.%]="b.x"
            [style.top.%]="b.y"
            [style.width.%]="b.w"
            [style.height.%]="b.h"
            [style.--i]="b.intensidad"
            [style.animation-delay]="b.rank * 45 + 'ms'"
            [attr.aria-label]="b.etiqueta + ': ' + b.texto + ' (' + b.pct + ')'"
            (mouseenter)="foco.set(b)"
            (focus)="foco.set(b)"
            (click)="foco.set(b)"
          >
            <span class="tm__rank">{{ b.otros ? '+' : b.rank }}</span>
            <span class="tm__nombre">{{ b.etiqueta }}</span>
            <span class="tm__valor">{{ b.texto }}</span>
            <span class="tm__pct">{{ b.pct }}</span>
          </button>
        }
      </div>

      <div class="tm__pie">
        @if (foco(); as f) {
          <div class="tm__foco">
            <span class="tm__foco-rank">{{ f.otros ? '+' : '#' + f.rank }}</span>
            <span class="tm__foco-texto">
              <strong>{{ f.etiqueta }}</strong>
              <span>{{ f.texto }} casos · {{ f.pct }} del total{{ f.detalle ? ' · ' + f.detalle : '' }}</span>
            </span>
          </div>
        } @else if (vitales() > 0) {
          <p class="tm__nota">
            <span class="tm__marca"></span>
            <strong>{{ vitales() }}</strong> de {{ totalItems() }} concentran el <strong>{{ pctVital() }}</strong>
            de los casos. Pasa el cursor por un bloque para ver su detalle.
          </p>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 0; container-type: inline-size; }
    .tm__mapa {
      position: relative;
      width: 100%;
      aspect-ratio: 1.45;
      min-height: 280px;
      border-radius: var(--radius-lg);
      overflow: hidden;
      background: var(--surface-2);
    }
    @container (max-width: 520px) { .tm__mapa { aspect-ratio: 1.05; } }

    .tm__bloque {
      position: absolute;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      gap: 2px;
      padding: 8px 10px;
      border: 2px solid var(--surface);
      border-radius: 8px;
      text-align: left;
      color: var(--text);
      background: color-mix(in srgb, var(--brand) calc(12% + var(--i) * 80%), var(--surface));
      overflow: hidden;
      cursor: pointer;
      font: inherit;
      animation: tm-entra 600ms cubic-bezier(0.16, 1, 0.3, 1) both;
      transition: filter var(--transition-fast), box-shadow var(--transition-fast);
    }
    .tm__bloque:hover, .tm__bloque--on { filter: brightness(1.08) saturate(1.1); box-shadow: inset 0 0 0 2px var(--accent); z-index: 1; }
    .tm__bloque:focus-visible { outline: 3px solid var(--accent); outline-offset: -3px; }
    .tm__bloque--fuerte { color: #fff; }
    .tm__bloque--otros { background: repeating-linear-gradient(135deg, var(--surface-3) 0 8px, var(--surface-2) 8px 16px); color: var(--text-2); }
    .tm__bloque--vital::after {
      content: '';
      position: absolute;
      top: 8px;
      right: 8px;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #fff;
      box-shadow: 0 0 0 3px color-mix(in srgb, #fff 35%, transparent);
    }
    .tm__bloque:not(.tm__bloque--fuerte).tm__bloque--vital::after { background: var(--brand); }

    .tm__rank {
      align-self: flex-start;
      min-width: 20px;
      padding: 1px 5px;
      border-radius: 5px;
      font-size: 0.62rem;
      font-weight: 800;
      text-align: center;
      background: color-mix(in srgb, currentColor 16%, transparent);
    }
    .tm__nombre {
      max-width: 100%;
      font-size: clamp(0.7rem, 0.62rem + 0.6cqi, 0.9rem);
      font-weight: 800;
      line-height: 1.15;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .tm__valor {
      font-family: var(--font-display);
      font-size: clamp(1rem, 0.8rem + 1.4cqi, 1.6rem);
      font-weight: 800;
      letter-spacing: -0.03em;
      line-height: 1;
      font-variant-numeric: tabular-nums;
    }
    .tm__pct { font-size: 0.68rem; font-weight: 700; opacity: 0.85; white-space: nowrap; }
    .tm__bloque--chico { padding: 4px 6px; justify-content: center; }
    .tm__bloque--chico .tm__rank,
    .tm__bloque--chico .tm__pct { display: none; }
    .tm__bloque--chico .tm__nombre { font-size: 0.62rem; }
    .tm__bloque--chico .tm__valor { font-size: 0.82rem; }
    .tm__bloque--bajo { flex-direction: row; flex-wrap: wrap; align-items: center; justify-content: flex-start; column-gap: 8px; }
    .tm__bloque--bajo .tm__rank, .tm__bloque--bajo .tm__pct { display: none; }

    .tm__pie { min-height: 44px; margin-top: var(--space-3); }
    .tm__nota { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 6px; font-size: var(--fs-sm); color: var(--text-2); }
    .tm__nota strong { color: var(--text); }
    .tm__marca { width: 9px; height: 9px; border-radius: 50%; background: var(--brand); box-shadow: 0 0 0 3px var(--brand-soft); }
    .tm__foco { display: flex; align-items: center; gap: var(--space-3); animation: ui-rise 240ms both; }
    .tm__foco-rank {
      display: inline-flex; align-items: center; justify-content: center;
      min-width: 38px; height: 38px; padding: 0 6px;
      border-radius: var(--radius); font-weight: 800; color: #fff; background: var(--brand);
    }
    .tm__foco-texto { display: flex; flex-direction: column; min-width: 0; font-size: var(--fs-sm); color: var(--text-2); }
    .tm__foco-texto strong { color: var(--text); font-size: var(--fs-base); }
    @keyframes tm-entra { from { opacity: 0; transform: scale(0.92); } to { opacity: 1; transform: none; } }
    @media (prefers-reduced-motion: reduce) { .tm__bloque { animation: none; } }
  `,
})
export class Treemap {
  readonly items = input<ItemTreemap[]>([]);
  readonly maximo = input<number>(12);
  readonly umbral = input<number>(0.8);
  readonly titulo = input<string>('Distribución');

  readonly foco = signal<Bloque | null>(null);

  private readonly ordenados = computed(() =>
    [...this.items()].filter((i) => i.valor > 0).sort((a, b) => b.valor - a.valor),
  );
  readonly totalItems = computed(() => this.ordenados().length);

  readonly bloques = computed<Bloque[]>(() => {
    const lista = this.ordenados();
    const total = lista.reduce((t, i) => t + i.valor, 0);
    if (!total) {
      return [];
    }
    const visibles = lista.slice(0, this.maximo());
    const resto = lista.slice(this.maximo());
    const entradas: (ItemTreemap & { otros: boolean })[] = visibles.map((i) => ({ ...i, otros: false }));
    if (resto.length) {
      entradas.push({ etiqueta: `Otros (${resto.length})`, valor: resto.reduce((t, i) => t + i.valor, 0), otros: true });
    }
    const max = visibles[0]?.valor || 1;
    const min = visibles[visibles.length - 1]?.valor ?? 0;
    const rango = max - min || 1;
    let acumulado = 0;
    let vitalCerrado = false;
    const marcas = entradas.map((e) => {
      acumulado += e.valor;
      const vital = !e.otros && !vitalCerrado;
      if (acumulado / total >= this.umbral()) {
        vitalCerrado = true;
      }
      return vital;
    });
    const rects = squarify(entradas.map((e) => e.valor), 0, 0, 100 * PROPORCION, 100);
    return entradas.map((e, i) => ({
      etiqueta: e.etiqueta,
      valor: e.valor,
      detalle: e.detalle,
      x: (rects[i].x / (100 * PROPORCION)) * 100,
      y: rects[i].y,
      w: (rects[i].w / (100 * PROPORCION)) * 100,
      h: rects[i].h,
      rank: i + 1,
      // Intensidad por rango (min-max): el mayor es el más intenso y las diferencias se notan.
      intensidad: e.otros ? 0 : 0.18 + 0.82 * ((e.valor - min) / rango),
      vital: marcas[i],
      otros: e.otros,
      pct: porcentaje(e.valor / total),
      texto: entero(e.valor),
    }));
  });

  readonly vitales = computed(() => this.bloques().filter((b) => b.vital).length);

  readonly pctVital = computed(() => {
    const total = this.bloques().reduce((t, b) => t + b.valor, 0) || 1;
    return porcentaje(this.bloques().filter((b) => b.vital).reduce((t, b) => t + b.valor, 0) / total);
  });
}

interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Algoritmo squarified (Bruls et al.): filas que minimizan la peor relación de aspecto. */
function squarify(valores: number[], x: number, y: number, w: number, h: number): Rect[] {
  const total = valores.reduce((a, b) => a + b, 0);
  const areas = valores.map((v) => (v / total) * w * h);
  const salida: Rect[] = [];
  let i = 0;
  let caja = { x, y, w, h };
  while (i < areas.length) {
    const lado = Math.min(caja.w, caja.h);
    const fila: number[] = [areas[i]];
    let j = i + 1;
    while (j < areas.length && peor([...fila, areas[j]], lado) <= peor(fila, lado)) {
      fila.push(areas[j]);
      j++;
    }
    const suma = fila.reduce((a, b) => a + b, 0);
    if (caja.w >= caja.h) {
      const ancho = suma / caja.h;
      let yy = caja.y;
      for (const a of fila) {
        const alto = a / ancho;
        salida.push({ x: caja.x, y: yy, w: ancho, h: alto });
        yy += alto;
      }
      caja = { x: caja.x + ancho, y: caja.y, w: caja.w - ancho, h: caja.h };
    } else {
      const alto = suma / caja.w;
      let xx = caja.x;
      for (const a of fila) {
        const ancho = a / alto;
        salida.push({ x: xx, y: caja.y, w: ancho, h: alto });
        xx += ancho;
      }
      caja = { x: caja.x, y: caja.y + alto, w: caja.w, h: caja.h - alto };
    }
    i = j;
  }
  return salida;
}

function peor(fila: number[], lado: number): number {
  const suma = fila.reduce((a, b) => a + b, 0);
  const max = Math.max(...fila);
  const min = Math.min(...fila);
  const l2 = lado * lado;
  return Math.max((l2 * max) / (suma * suma), (suma * suma) / (l2 * min));
}
