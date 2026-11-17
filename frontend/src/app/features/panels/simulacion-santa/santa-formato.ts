import { Cluster, EstadoPaleta, EstadoRegistroSanta, OrdenLlenado } from './models/santa.model';

export const ESTADOS_REGISTRO_SANTA: { id: EstadoRegistroSanta; etiqueta: string }[] = [
  { id: 'PENDIENTE', etiqueta: 'Pendiente' },
  { id: 'EN_CURSO', etiqueta: 'En curso' },
  { id: 'COMPLETO', etiqueta: 'Completo' },
];

export function etiquetaEstadoRegistro(estado: EstadoRegistroSanta): string {
  return ESTADOS_REGISTRO_SANTA.find((e) => e.id === estado)?.etiqueta ?? estado;
}

export function etiquetaEstadoPaleta(estado: EstadoPaleta): string {
  return estado === 'COMPLETA' ? 'Completa' : estado === 'EN_CURSO' ? 'En curso' : 'Pendiente';
}

export const CLUSTERS: Cluster[] = [1, 2, 3];

/** Color de señal de cada cluster (tokens del tema Atlas, claro y oscuro). */
export function colorCluster(cluster: Cluster | null): string {
  switch (cluster) {
    case 1:
      return 'var(--accent)';
    case 2:
      return 'var(--violet)';
    case 3:
      return 'var(--warning)';
    default:
      return 'var(--text-3)';
  }
}

export function etiquetaCluster(cluster: Cluster | null): string {
  return cluster ? `C${cluster}` : '—';
}

export const ORDENES_LLENADO: { id: OrdenLlenado; etiqueta: string }[] = [
  { id: 'RAMPAS_PRIMERO', etiqueta: 'Rampas primero' },
  { id: 'PASILLOS_PRIMERO', etiqueta: 'Pasillos primero' },
];

const FORMATO_ENTERO = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 0 });
const FORMATO_DECIMAL = new Intl.NumberFormat('es-PE', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

export function entero(valor: number | null | undefined): string {
  return FORMATO_ENTERO.format(valor ?? 0);
}

export function decimal(valor: number | null | undefined): string {
  return FORMATO_DECIMAL.format(valor ?? 0);
}

export function porcentaje(parte: number, total: number): number { // jus:ok conteos, no importes
  return total > 0 ? Math.round((parte / total) * 100) : 0;
}

const FORMATOS_EXCEL = /\.(xlsx|xls|csv)$/i;
const MAX_BYTES_EXCEL = 10 * 1024 * 1024;

export interface ProblemaArchivo {
  titulo: string;
  mensaje: string;
}

/** Revisión rápida antes de enviar el archivo (el servidor vuelve a validar). */
export function problemaArchivo(archivo: File): ProblemaArchivo | null {
  if (!FORMATOS_EXCEL.test(archivo.name)) {
    return { titulo: 'Formato no válido', mensaje: 'Usa un archivo .xlsx, .xls o .csv.' };
  }
  if (archivo.size > MAX_BYTES_EXCEL) {
    return { titulo: 'Archivo muy grande', mensaje: 'El máximo permitido es 10 MB.' };
  }
  return null;
}

export function nombreSugerido(archivo: File): string {
  return archivo.name.replace(FORMATOS_EXCEL, '').slice(0, 150);
}

export function tamanoArchivo(archivo: File): string {
  return archivo.size < 1024 * 1024
    ? `${Math.max(1, Math.round(archivo.size / 1024))} KB`
    : `${(archivo.size / (1024 * 1024)).toFixed(1)} MB`;
}
