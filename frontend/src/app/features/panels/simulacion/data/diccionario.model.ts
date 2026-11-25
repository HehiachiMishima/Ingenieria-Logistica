/**
 * Registro v2 de Simulación Titan: diccionario de rutas. Módulo independiente del registro por
 * Excel. Una ruta en Datos (clusterId null) no tiene frecuencia ni grupo.
 */

export type FrecuenciaDiccionario = 'LXV' | 'MJS' | 'DIARIA';
export type TipoRutaDiccionario = 'B2C' | 'B2B';

/** Bloque de cluster del diccionario (CL1…CLn). */
export interface ClusterDiccionario {
  id: number;
  orden: number;
  nombre: string;
}

/** Una ruta con los totales de sus locales. */
export interface RutaDiccionario {
  id: number;
  codigoRuta: string;
  /** null = sin tipo (empieza vacío y se asigna con la acción Tipo). */
  tipo: TipoRutaDiccionario | null;
  /** null = está en Datos. */
  clusterId: number | null;
  frecuencia: FrecuenciaDiccionario | null;
  grupo: string | null;
  bultos: number;
  unidades: number;
  lineas: number;
  /** Cuántos códigos (locales) tiene. */
  locales: number;
  /** Sus códigos, para buscar por local. */
  codigos: string[];
}

export interface Diccionario {
  clusters: ClusterDiccionario[];
  rutas: RutaDiccionario[];
  /** Hay más rutas que el tope de la vista. */
  truncado: boolean;
}

/** Un local; `filas` > 1 si su código se ingresó varias veces y se sumó. */
export interface LocalDiccionario {
  cod: string;
  filas: number;
  bultos: number;
  unidades: number;
  lineas: number;
}

export interface DetalleRutaDiccionario {
  ruta: RutaDiccionario;
  locales: LocalDiccionario[];
}

/** Rutas que cambiaron y cuántas se dejaron como estaban (estaban en Datos). */
export interface CambioRutas {
  rutas: RutaDiccionario[];
  omitidas: number;
}

export interface RutaDiccionarioRequest {
  codigoRuta: string;
  tipo: TipoRutaDiccionario | null;
  locales: { cod: string; bultos: number; unidades: number; lineas: number }[];
}

/** Dónde estaba una ruta antes de moverla (para deshacer). */
export interface UbicacionRuta {
  id: number;
  clusterId: number | null;
  frecuencia: FrecuenciaDiccionario | null;
  grupo: string | null;
}

/** «2002 B2C», o «2002 (sin tipo)» mientras no se le asigne (igual que el servidor). */
export function nombreRuta(ruta: { codigoRuta: string; tipo: TipoRutaDiccionario | null }): string {
  return ruta.tipo ? `${ruta.codigoRuta} ${ruta.tipo}` : `${ruta.codigoRuta} (sin tipo)`;
}

/** Rótulo de la frecuencia en la ficha (el mismo de Configuración). */
export const TEXTO_FRECUENCIA: Record<FrecuenciaDiccionario, string> = {
  LXV: 'L-X-V',
  MJS: 'M-J-S',
  DIARIA: 'Diaria',
};
