/** Velocidad con la que arranca un registro (bultos por hora de cada operario). */
export const VELOCIDAD_INICIAL = 100;

/** Topes que valida el backend (Limites.java): el formulario no deja pasarlos. */
export const LIMITES = {
  equipo: 10_000,
  operariosPorChute: 50,
  horas: 744,
  velocidad: 100_000,
} as const;

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

/**
 * Colores de los grupos de un cluster (pestaña de la ruta en la planilla y en el reporte):
 * distintos del semáforo (rojo, amarillo y verde) y del acento, legibles en claro y oscuro.
 */
export const PALETA_GRUPOS = [
  '#7c3aed',
  '#0891b2',
  '#db2777',
  '#4f46e5',
  '#0d9488',
  '#c2410c',
  '#9333ea',
  '#0369a1',
] as const;

/** Color de cada grupo de un cluster, en orden natural: el mismo en pantalla y en el PDF. */
export function coloresGrupos(nombres: readonly string[]): Map<string, string> {
  return new Map(
    [...nombres].sort(compararNombre).map((nombre, i) => [nombre, PALETA_GRUPOS[i % PALETA_GRUPOS.length]]),
  );
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

/** Un formateador por cantidad de decimales: crear `Intl.NumberFormat` en cada llamada es caro. */
const FORMATOS_NUMERO = new Map<number, Intl.NumberFormat>();

export function formatearNumero(valor: number | null | undefined, decimales = 0): string {
  let formato = FORMATOS_NUMERO.get(decimales);
  if (!formato) {
    formato = new Intl.NumberFormat('es-PE', { minimumFractionDigits: 0, maximumFractionDigits: decimales });
    FORMATOS_NUMERO.set(decimales, formato);
  }
  return formato.format(valor ?? 0);
}

/**
 * Entero hacia arriba para mostrar datos sin decimales (199.1 → 200). Antes se
 * corta el ruido de punto flotante: 200.0000000001 sigue siendo 200.
 */
export function redondearArriba(valor: number | null | undefined): number {
  return Math.ceil(Math.round((valor ?? 0) * 1e6) / 1e6);
}

/** Número sin decimales, redondeado hacia arriba y con separadores de es-PE. */
export function formatearEntero(valor: number | null | undefined): string {
  return formatearNumero(redondearArriba(valor));
}

/** Número de un chute según su nombre («CHUTE 18», «CH-18», «018» → 18), o null. */
export function numeroChute(nombre: string): number | null {
  const m = /^(?:CH(?:UTE)?)?[\s\-_#.:]*(?:N[°º.O]?\s*)?0*(\d{1,6})$/i.exec(nombre.trim().replace(/\s+/g, ' '));
  return m ? Number(m[1]) : null;
}

/**
 * Orden de los chutes por su número, aunque los nombres mezclen estilos
 * («CHUTE 18» antes que «22»), igual que el servidor; sin número, al final.
 */
export function compararChute(a: string, b: string): number {
  const na = numeroChute(a);
  const nb = numeroChute(b);
  if (na !== null && nb !== null && na !== nb) {
    return na - nb;
  }
  if ((na === null) !== (nb === null)) {
    return na === null ? 1 : -1;
  }
  return compararNombre(a, b);
}
