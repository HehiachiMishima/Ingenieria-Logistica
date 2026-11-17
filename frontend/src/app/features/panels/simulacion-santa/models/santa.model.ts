/**
 * Tipos del panel Simulación Santa Anita: espejo campo a campo del contrato de
 * `/api/simulacion-santa` (los mismos nombres que devuelve el backend).
 */

export type EstadoRegistroSanta = 'PENDIENTE' | 'EN_CURSO' | 'COMPLETO';
export type EstadoPaleta = 'PENDIENTE' | 'EN_CURSO' | 'COMPLETA';
export type OrdenLlenado = 'RAMPAS_PRIMERO' | 'PASILLOS_PRIMERO';
export type Cluster = 1 | 2 | 3;
export type Lado = 'IZQ' | 'DER';
export type TipoUbicacion = 'RAMPA' | 'PASILLO';
export type Trio = [number, number, number];

export interface RegistroSanta {
  id: number;
  nombre: string;
  nombreArchivo: string;
  estado: EstadoRegistroSanta;
  /** Envíos ya recibidos: 0 a 3. */
  envioActual: number;
  paletas: number;
  rutas: number;
  filasOmitidas: number;
  bultos: number;
  bultosEnvios: Trio;
  paletasCluster: Trio;
  creadoPor: number;
  creadoEn: string;
  actualizadoPor: number;
  actualizadoEn: string;
}

export interface ErrorFila {
  /** Número de fila del Excel (desde 1). */
  fila: number;
  mensaje: string;
}

export interface ValidacionExcel {
  nombreArchivo: string;
  filasLeidas: number;
  filasValidas: number;
  rutas: number;
  bultos: number;
  bultosEnvios: Trio;
  paletasCluster: Trio;
  errores: ErrorFila[];
  totalErrores: number; // jus:ok conteo de filas, no es un importe
}

export interface RampaConfig {
  numero: number;
  cluster: Cluster | null;
  capacidad: number;
  posiciones: number;
}

export interface PasilloConfig {
  numero: number;
  cluster: Cluster | null;
  posiciones: number;
}

export interface ConfiguracionSanta {
  registroId: number | null;
  operarios: number;
  bultosHora: number;
  ordenClusters: [OrdenLlenado, OrdenLlenado, OrdenLlenado];
  rampas: RampaConfig[];
  pasillos: PasilloConfig[];
  actualizadoPor: number | null;
  actualizadoEn: string | null;
}

export interface ConfiguracionSantaRequest {
  operarios: number;
  bultosHora: number;
  ordenClusters: [OrdenLlenado, OrdenLlenado, OrdenLlenado];
  rampas: { numero: number; cluster: Cluster | null; posiciones: number }[];
  pasillos: { numero: number; cluster: Cluster | null }[];
}

export interface Ubicacion {
  tipo: TipoUbicacion;
  numero: number;
  lado: Lado;
  /** Rampa: 1 = junto a los pasillos. Pasillo: 1 = junto al andén. */
  fila: number;
}

export interface PaletaSimulacion {
  id: number;
  ruta: string;
  local: string;
  cluster: Cluster;
  envios: Trio;
  recibidos: number;
  total: number; // jus:ok bultos (conteo entero), no es un importe
  estado: EstadoPaleta;
  ubicacion: Ubicacion | null;
}

export interface ClusterSimulacion {
  cluster: Cluster;
  paletas: number;
  posiciones: number;
  ubicadas: number;
  sinUbicar: number;
  rampas: number[];
  pasillos: number[];
}

export interface KpisSimulacion {
  paletas: number;
  ubicadas: number;
  sinUbicar: number;
  completas: number;
  enCurso: number;
  bultosTotal: number;
  bultosRecibidos: number;
  bultosEnvios: Trio;
  capacidadHora: number;
  minutosRestantes: number;
}

export interface SimulacionSanta {
  registroId: number;
  envio: number;
  envioActual: number;
  kpis: KpisSimulacion;
  clusters: ClusterSimulacion[];
  paletas: PaletaSimulacion[];
}
