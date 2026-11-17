import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Cluster, PaletaSimulacion } from '../../models/santa.model';
import { colorCluster, etiquetaEstadoPaleta } from '../../santa-formato';

/** Cuartos de la paleta que ocupan las cajas, en el orden en que se apilan. */
const CUARTOS = [
  { x: 4, y: 4 },
  { x: 50, y: 4 },
  { x: 4, y: 50 },
  { x: 50, y: 50 },
];

/** Ancho desde el que se escribe la ruta y el avance sobre la paleta. */
const ANCHO_TEXTO = 56;
/** Ancho desde el que además de la ruta entra el avance («2101 10/16»). */
const ANCHO_AVANCE = 80;
/** Ancho desde el que ruta y avance entran con separador («2101 · 12/12»). */
const ANCHO_UNA_LINEA = 110;

/**
 * Una posición del plano dibujada como en el almacén: paleta amarilla vista
 * desde arriba y, encima, las cajas de los bultos ya recibidos (un cuarto por
 * cada 25 %). Sin paleta es un hueco libre (borde del cluster) o deshabilitado.
 * Se ubica de forma absoluta dentro del plano.
 */
@Component({
  selector: 'app-paleta',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[style.left.px]': 'x()',
    '[style.top.px]': 'y()',
    '[style.width.px]': 'ancho()',
    '[style.height.px]': 'alto()',
    '[style.--cl]': 'color()',
  },
  templateUrl: './paleta.html',
  styleUrl: './paleta.scss',
})
export class Paleta {
  readonly paleta = input<PaletaSimulacion | null>(null);
  readonly cluster = input<Cluster | null>(null);
  readonly habilitada = input(true);
  readonly x = input.required<number>();
  readonly y = input.required<number>();
  readonly ancho = input.required<number>();
  readonly alto = input.required<number>();
  readonly lugar = input('');
  readonly elegida = input(false);
  readonly elegir = output<PaletaSimulacion>();

  readonly color = computed(() => colorCluster(this.paleta()?.cluster ?? this.cluster()));
  readonly conTexto = computed(() => this.ancho() >= ANCHO_TEXTO);
  /** Bajo 80 px solo entra la ruta: el avance lo dicen las cajas (y el tooltip). */
  readonly conAvance = computed(() => this.ancho() >= ANCHO_AVANCE);
  /** Con poco ancho, ruta y avance van separados solo por un espacio. */
  readonly separador = computed(() => (this.ancho() >= ANCHO_UNA_LINEA ? ' · ' : ' '));

  readonly cajas = computed(() => {
    const p = this.paleta();
    if (!p || p.total <= 0) {
      return [];
    }
    const cuartos = p.estado === 'COMPLETA' ? 4 : Math.min(3, Math.ceil((p.recibidos / p.total) * 4));
    return CUARTOS.slice(0, p.recibidos > 0 ? cuartos : 0);
  });

  readonly titulo = computed(() => {
    const p = this.paleta();
    if (!p) {
      return `${this.lugar()} · ${this.habilitada() ? 'libre' : 'deshabilitada'}`;
    }
    return `${this.lugar()} · Ruta ${p.ruta} · ${p.local} · C${p.cluster} · ${p.recibidos}/${p.total} bultos · ${etiquetaEstadoPaleta(p.estado)}`;
  });
}
