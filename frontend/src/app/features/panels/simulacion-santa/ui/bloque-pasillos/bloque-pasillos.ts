import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  output,
  viewChild,
} from '@angular/core';
import { Cluster, Lado, PaletaSimulacion } from '../../models/santa.model';
import { BLOQUE, BloquePasillos as Bloque, clavePosicion, tamanoPaleta } from '../../plano/plano-santa';
import { colorCluster, etiquetaCluster } from '../../santa-formato';
import { anchoDelHost } from '../ancho-host';
import { Paleta } from '../paleta/paleta';

export interface PasilloVista {
  numero: number;
  cluster: Cluster | null;
}

interface CeldaVista {
  clave: string;
  pasillo: number;
  lado: Lado;
  fila: number;
  x: number;
  y: number;
  paleta: PaletaSimulacion | null;
  cluster: Cluster | null;
}

/**
 * Orden de izquierda a derecha del bloque (como en el PDF): rack simple, calle,
 * rack doble, calle, rack doble, calle ancha (columnas), rack simple, pasillo
 * central y la otra mitad igual. `col` = columna de paletas (pasillo relativo y
 * lado); `calle` = calle del pasillo relativo; `junto` = separación de un rack doble.
 */
type Tramo =
  | { tipo: 'col'; pasillo: number; lado: Lado }
  | { tipo: 'calle'; pasillo: number; ancha?: boolean }
  | { tipo: 'junto' }
  | { tipo: 'central' };

const TRAMOS: Tramo[] = [
  { tipo: 'col', pasillo: 0, lado: 'IZQ' },
  { tipo: 'calle', pasillo: 0 },
  { tipo: 'col', pasillo: 0, lado: 'DER' },
  { tipo: 'junto' },
  { tipo: 'col', pasillo: 1, lado: 'IZQ' },
  { tipo: 'calle', pasillo: 1 },
  { tipo: 'col', pasillo: 1, lado: 'DER' },
  { tipo: 'junto' },
  { tipo: 'col', pasillo: 2, lado: 'IZQ' },
  { tipo: 'calle', pasillo: 2, ancha: true },
  { tipo: 'col', pasillo: 2, lado: 'DER' },
  { tipo: 'central' },
  { tipo: 'col', pasillo: 3, lado: 'IZQ' },
  { tipo: 'calle', pasillo: 3 },
  { tipo: 'col', pasillo: 3, lado: 'DER' },
  { tipo: 'junto' },
  { tipo: 'col', pasillo: 4, lado: 'IZQ' },
  { tipo: 'calle', pasillo: 4 },
  { tipo: 'col', pasillo: 4, lado: 'DER' },
  { tipo: 'junto' },
  { tipo: 'col', pasillo: 5, lado: 'IZQ' },
  { tipo: 'calle', pasillo: 5 },
  { tipo: 'col', pasillo: 5, lado: 'DER' },
];

const MARGEN = 14;
const SEP = 6;
const JUNTO = 4;
const ARRIBA = 64;
const ABAJO = 34;

/**
 * Ventana de un bloque de 6 pasillos en modo compacto: el orden y los lados de
 * cada rack son los del plano, pero las calles se reducen. La paleta tiene el
 * tamaño M ({@link tamanoPaleta}), el mismo que en las rampas: el bloque entra
 * a lo ancho y baja con scroll vertical; la fila 1 (abajo) está junto al andén.
 */
@Component({
  selector: 'app-bloque-pasillos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Paleta],
  templateUrl: './bloque-pasillos.html',
  styleUrl: './bloque-pasillos.scss',
})
export class BloquePasillos {
  readonly bloque = input.required<Bloque>();
  readonly pasillos = input.required<PasilloVista[]>();
  readonly paletas = input.required<Map<string, PaletaSimulacion>>();
  readonly seleccionada = input<number | null>(null);
  readonly seleccionar = output<PaletaSimulacion>();

  private readonly scroll = viewChild.required<ElementRef<HTMLElement>>('scroll');
  private readonly ancho = anchoDelHost();

  readonly colorCluster = colorCluster;
  readonly etiquetaCluster = etiquetaCluster;

  constructor() {
    // Al abrir un bloque se muestra la fila 1 (abajo, junto al andén): ahí empieza el llenado.
    afterRenderEffect({
      write: () => {
        this.bloque();
        const el = this.scroll().nativeElement;
        el.scrollTop = el.scrollHeight;
      },
    });
  }

  readonly g = computed(() => {
    const { ancho: pw, alto: ph } = tamanoPaleta(this.ancho());
    const calle = Math.round(pw * 0.4);
    const calleAncha = Math.round(pw * 0.52);
    const central = Math.round(pw * 0.7);
    const paso = ph + SEP;
    const base = this.bloque().base;
    const clusterDe = new Map(this.pasillos().map((p) => [p.numero, p.cluster]));
    const paletas = this.paletas();
    const yFila = (fila: number) => ARRIBA + (BLOQUE.filas - fila) * paso;
    const altoRacks = BLOQUE.filas * paso;

    const celdas: CeldaVista[] = [];
    const racks: { x: number; ancho: number }[] = [];
    const calles: { numero: number; cluster: Cluster | null; centro: number; ancho: number }[] = [];
    let centralX = 0;
    let x = MARGEN;
    let rackDesde = x;
    for (const t of TRAMOS) {
      if (t.tipo === 'col') {
        const pasillo = base + t.pasillo;
        for (let fila = 1; fila <= BLOQUE.filas; fila++) {
          const clave = clavePosicion('PASILLO', pasillo, t.lado, fila);
          celdas.push({
            clave,
            pasillo,
            lado: t.lado,
            fila,
            x,
            y: yFila(fila),
            paleta: paletas.get(clave) ?? null,
            cluster: clusterDe.get(pasillo) ?? null,
          });
        }
        x += pw;
      } else if (t.tipo === 'junto') {
        x += JUNTO;
      } else {
        racks.push({ x: rackDesde, ancho: x - rackDesde });
        const ancho = t.tipo === 'central' ? central : t.ancha ? calleAncha : calle;
        if (t.tipo === 'calle') {
          const numero = base + t.pasillo;
          calles.push({ numero, cluster: clusterDe.get(numero) ?? null, centro: x + ancho / 2, ancho });
        } else {
          centralX = x;
        }
        x += ancho;
        rackDesde = x;
      }
    }
    racks.push({ x: rackDesde, ancho: x - rackDesde });

    // Columnas de la estructura en la calle ancha (3.er pasillo): su altura en filas.
    const calle3 = calles[2];
    const columnas = this.bloque().columnas.map((m) => ({
      x: calle3.centro - 4,
      y: ARRIBA + ((m - BLOQUE.margenFila) / BLOQUE.paso) * paso,
    }));

    return {
      pw,
      ph,
      anchoTotal: x + MARGEN,
      altoTotal: ARRIBA + altoRacks + ABAJO,
      altoRacks,
      letreroAncho: Math.max(76, Math.round(pw * 0.95)),
      racks,
      calles,
      central: { x: centralX, ancho: central },
      columnas,
      celdas,
    };
  });

  lugar(c: CeldaVista): string {
    return `Pasillo ${c.pasillo} · rack ${c.lado === 'IZQ' ? 'izquierdo' : 'derecho'} · fila ${c.fila}`;
  }
}
