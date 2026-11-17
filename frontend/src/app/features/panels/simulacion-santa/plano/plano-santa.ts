import { Lado, TipoUbicacion } from '../models/santa.model';

/**
 * Plano físico de Santa Anita (PDF «Cross Santa Anita»): lo que el dibujo
 * necesita saber del almacén. Las capacidades que valen para simular las envía
 * el backend.
 */

/** Paleta más chica que se dibuja (px); por debajo, el plano pasa a tener scroll. */
const PALETA_MIN = 64;
/** Proporción de la paleta: 1,00 m de fondo por 1,20 m de ancho. */
const PROPORCION = 0.82;
/** Ancho de un bloque de pasillos en «paletas»: 12 columnas + 5 calles (0,4) + la ancha (0,52) + la central (0,7). */
const BLOQUE_EN_PALETAS = 12 + 5 * 0.4 + 0.52 + 0.7;
/** Lo fijo del bloque en px: márgenes (2 × 14), juntas (4 × 4) y un scrollbar clásico (16). */
const BLOQUE_FIJO = 2 * 14 + 4 * 4 + 16;

export interface TamanoPaleta {
  ancho: number;
  alto: number;
}

/**
 * Tamaño M de la paleta, el mismo en rampas y en pasillos: el más grande que
 * deja ver un bloque de 6 pasillos sin scroll horizontal (y así, en rampas, se
 * ven la 15 a la 20 al abrir). Crece con la pantalla; nunca baja de 64 px.
 * Ambas vistas lo calculan con el ancho del plano, que es el mismo.
 */
export function tamanoPaleta(anchoPlano: number): TamanoPaleta {
  const ancho = Math.round(Math.max(PALETA_MIN, (anchoPlano - BLOQUE_FIJO) / BLOQUE_EN_PALETAS));
  return { ancho, alto: Math.round(ancho * PROPORCION) };
}

/** ¿La celda (lado, fila) de una rampa está habilitada con `h` posiciones? */
export function celdaRampaHabilitada(lado: Lado, fila: number, habilitadas: number): boolean {
  const izquierda = Math.ceil(habilitadas / 2);
  return lado === 'IZQ' ? fila <= izquierda : fila <= habilitadas - izquierda;
}

export interface BloquePasillos {
  id: string;
  /** Primer pasillo del bloque (1, 7, 13 o 19). */
  base: number;
  etiqueta: string;
  /** Columnas de la estructura dentro de la calle del 3.er pasillo (m desde arriba). */
  columnas: readonly number[];
}

/** De arriba (lejos del andén) hacia abajo, como en el plano: el 1–6 está junto al andén. */
export const BLOQUES: readonly BloquePasillos[] = [
  { id: '19-24', base: 19, etiqueta: 'Pasillos 19–24', columnas: [6.1, 14.9] },
  { id: '13-18', base: 13, etiqueta: 'Pasillos 13–18', columnas: [5.1, 13.7] },
  { id: '7-12', base: 7, etiqueta: 'Pasillos 7–12', columnas: [3.8, 12.5] },
  { id: '1-6', base: 1, etiqueta: 'Pasillos 1–6', columnas: [2.6, 11.2] },
];

/** Medidas de un rack de pasillo en metros (cotas del PDF). */
export const BLOQUE = {
  filas: 12,
  /** Paso entre filas: paleta de 1,00 m + separación de 0,20 m. */
  paso: 1.2,
  /** Margen entre el borde del rack y la primera fila. */
  margenFila: 0.6,
} as const;

/** Clave de una posición del plano: `RAMPA|15|IZQ|1`. */
export function clavePosicion(tipo: TipoUbicacion, numero: number, lado: Lado, fila: number): string {
  return `${tipo}|${numero}|${lado}|${fila}`;
}
