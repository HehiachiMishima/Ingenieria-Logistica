import { ArchivoSimulacion } from './data/simulacion.model';

/** Velocidad con la que arranca un registro (bultos por hora de cada operario). */
export const VELOCIDAD_INICIAL = 100;

/** Topes que valida el backend (Limites.java): el formulario no deja pasarlos. */
export const LIMITES = {
  equipo: 10_000,
  operariosPorChute: 50,
  horas: 744,
  velocidad: 100_000,
} as const;

export interface ColorRegistro {
  nombre: string;
  hex: string;
}

export const PALETA_REGISTROS: ColorRegistro[] = [
  { nombre: 'Azul', hex: '#1d63ed' },
  { nombre: 'Rojo', hex: '#e11d2e' },
  { nombre: 'Verde', hex: '#059669' },
  { nombre: 'Ámbar', hex: '#d97706' },
  { nombre: 'Violeta', hex: '#7c3aed' },
  { nombre: 'Cian', hex: '#0891b2' },
  { nombre: 'Rosa', hex: '#ec4899' },
  { nombre: 'Pizarra', hex: '#64748b' },
];

/** Formatos y tamaño que acepta la carga del Excel de proyección. */
export const FORMATOS_EXCEL = /\.(xlsx|xls|csv)$/i;
export const MAX_BYTES_EXCEL = 20 * 1024 * 1024;

export interface ProblemaArchivo {
  titulo: string;
  mensaje: string;
}

/** Motivo por el que el archivo no se puede cargar, o null si es válido. */
export function problemaArchivoExcel(archivo: File): ProblemaArchivo | null {
  if (!FORMATOS_EXCEL.test(archivo.name)) {
    return { titulo: 'Formato no válido', mensaje: 'Usa un archivo .xlsx, .xls o .csv.' };
  }
  if (archivo.size > MAX_BYTES_EXCEL) {
    return { titulo: 'Archivo muy grande', mensaje: 'El máximo permitido es 20 MB.' };
  }
  return null;
}

/** Nombre sugerido para el registro: el del archivo sin extensión. */
export function nombreRegistroSugerido(archivo: File): string {
  return archivo.name.replace(FORMATOS_EXCEL, '').slice(0, 150);
}

/** Tamaño legible del archivo: «840 KB», «3.2 MB». */
export function tamanoArchivo(archivo: File): string {
  return archivo.size < 1024 * 1024
    ? `${Math.max(1, Math.round(archivo.size / 1024))} KB`
    : `${(archivo.size / (1024 * 1024)).toFixed(1)} MB`;
}

/** Cuerpo multipart de la carga, con los textos ya recortados. */
export function datosCarga(
  archivo: File,
  valores: { nombreRegistro: string; etiqueta: string; colorHex: string },
): FormData {
  const datos = new FormData();
  datos.append('archivo', archivo, archivo.name);
  datos.append('nombreRegistro', valores.nombreRegistro.trim());
  datos.append('etiqueta', valores.etiqueta.trim());
  datos.append('colorHex', valores.colorHex);
  return datos;
}

export const PALETA_CLUSTERS = [
  '#1d63ed',
  '#e11d2e',
  '#059669',
  '#d97706',
  '#7c3aed',
  '#0891b2',
  '#ec4899',
  '#64748b',
  '#84cc16',
  '#f97316',
];

export function colorCluster(indice: number): string {
  return PALETA_CLUSTERS[indice % PALETA_CLUSTERS.length];
}

/** Orden natural sin mayúsculas: CHUTE 2 antes que CHUTE 10. */
export function compararNombre(a: string, b: string): number {
  return a.localeCompare(b, 'es', { numeric: true, sensitivity: 'base' });
}

/** Texto del registro en los selectores: «Nombre · etiqueta» (la etiqueta es opcional). */
export function etiquetaRegistro(registro: ArchivoSimulacion): string {
  return registro.etiqueta
    ? `${registro.nombreRegistro} · ${registro.etiqueta}`
    : registro.nombreRegistro;
}

/** Estados del registro: ningún cluster lanzado, alguno lanzado y simulación cerrada. */
export const ESTADOS_REGISTRO = [
  { id: 'PROYECCION', etiqueta: 'Proyección' },
  { id: 'EN_PROCESO', etiqueta: 'En proceso' },
  { id: 'FINALIZADO', etiqueta: 'Finalizado' },
] as const;

export function etiquetaEstado(estado: string): string {
  return ESTADOS_REGISTRO.find((e) => e.id === estado)?.etiqueta ?? 'Proyección';
}

/** Avance de cada cluster, que se trabaja de a uno en el orden de las olas. */
export const ESTADOS_CLUSTER = [
  { id: 'PENDIENTE', etiqueta: 'Sin empezar' },
  { id: 'RUTAS', etiqueta: 'Rutas balanceadas' },
  { id: 'LISTO', etiqueta: 'Con operarios' },
  { id: 'CERRADO', etiqueta: 'Cerrado' },
] as const;

export function etiquetaEstadoCluster(estado: string): string {
  return ESTADOS_CLUSTER.find((e) => e.id === estado)?.etiqueta ?? 'Sin empezar';
}

/**
 * Horas que tarda un chute: bultos / (velocidad × operarios). Sin operarios no
 * hay tiempo calculable: se devuelve Infinity para mostrar «Sin operarios».
 */
export function horas(bultos: number, velocidad: number, operarios: number): number {
  if (bultos <= 0) {
    return 0;
  }
  if (operarios <= 0 || velocidad <= 0) {
    return Number.POSITIVE_INFINITY;
  }
  return bultos / (velocidad * operarios);
}

/** Convierte horas decimales a un texto compacto: 3.5 → «3h 30m». */
export function formatearHoras(horas: number | null | undefined): string {
  if (horas === null || horas === undefined || !Number.isFinite(horas)) {
    return 'Sin operarios';
  }
  if (horas <= 0) {
    return '0m';
  }
  const minutosTotales = Math.round(horas * 60);
  const h = Math.floor(minutosTotales / 60);
  const m = minutosTotales % 60;
  if (h === 0) {
    return `${m}m`;
  }
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

export function formatearNumero(valor: number | null | undefined, decimales = 0): string {
  return new Intl.NumberFormat('es-PE', {
    minimumFractionDigits: 0,
    maximumFractionDigits: decimales,
  }).format(valor ?? 0);
}

export function colorRegistro(hex: string | null | undefined): string {
  return hex && /^#[0-9a-fA-F]{6}$/.test(hex) ? hex : PALETA_REGISTROS[0].hex;
}
