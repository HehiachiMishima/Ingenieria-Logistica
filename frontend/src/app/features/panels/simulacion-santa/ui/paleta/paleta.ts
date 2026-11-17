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

/** Ancho desde el que se escribe el local sobre la paleta. */
const ANCHO_TEXTO = 56;
/** Ancho desde el que, bajo el local, entra una segunda línea con el avance («10/16»). */
const ANCHO_AVANCE = 96;

/**
 * Una posición del plano dibujada como en el almacén: paleta amarilla vista
 * desde arriba y, encima, las cajas de los bultos ya recibidos (un cuarto por
 * cada 25 %). Lleva escrito su local: una paleta es un solo local con todos sus
 * bultos (si no entra, se corta con «…» y el tooltip lo dice completo). Sin
 * paleta es un hueco libre (borde del cluster) o deshabilitado. Se ubica de
 * forma absoluta dentro del plano.
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
  /** Bajo 96 px solo entra el local: el avance lo dicen las cajas (y el tooltip). */
  readonly conAvance = computed(() => this.ancho() >= ANCHO_AVANCE);

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
    return `${p.local} · Ruta ${p.ruta} · C${p.cluster} · ${this.lugar()} · ${p.recibidos}/${p.total} bultos · ${etiquetaEstadoPaleta(p.estado)}`;
  });
}
