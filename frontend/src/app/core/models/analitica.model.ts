/** Piezas comunes de los endpoints de analítica (dashboards). */

export interface PuntoSerie {
  periodo: string;
  valor: number;
  secundario: number;
}

export interface CeldaMatriz {
  fila: string;
  columna: string;
  valor: number;
}

export type Granularidad = 'dia' | 'mes';
