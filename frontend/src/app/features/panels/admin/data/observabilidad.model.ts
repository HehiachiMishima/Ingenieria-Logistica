/** Períodos que ofrecen las pantallas (días hacia atrás desde ahora). */
export type Periodo = 1 | 7 | 30 | 90;

export type ResultadoAuditoria = 'Éxito' | 'Fallo' | 'Denegado';

/** Los listados cuentan su total solo hasta aquí (igual que el backend). */
export const TOPE_TOTAL = 10_000;

/** Fila de Auditoría, Actividad o de las peticiones de una API (campos según la consulta). */
export interface Evento {
  id: number;
  /** Instante UTC (ISO-8601). */
  fecha: string;
  usuario?: string | null;
  accion?: string | null;
  entidad?: string | null;
  registro?: string | null;
  resultado?: ResultadoAuditoria | null;
  metodo?: string | null;
  ruta?: string | null;
  /** Estado HTTP; null si la traza ya se purgó. */
  estado?: number | null;
  duracion_ms?: number | null;
  correlacion: string;
}

export interface EndpointMonitoreo {
  metodo: string;
  ruta: string;
  peticiones: number;
  errores: number;
  /** % de peticiones con estado ≥ 400, con un decimal. */
  porcentaje: number;
  /** p95 aproximado por intervalos, en ms. */
  p95: number;
}

export interface PuntoMonitoreo {
  /** «yyyy-MM-ddTHH:00» (24 h) o «yyyy-MM-dd» en la zona del negocio. */
  periodo: string;
  peticiones: number;
  errores: number;
  p95: number;
}

export interface ResumenMonitoreo {
  peticiones: number;
  peticionesPrevias: number;
  errores: number;
  erroresPrevios: number;
  erroresServidor: number;
  erroresServidorPrevios: number;
  p95: number;
  p95Previo: number;
  serie: PuntoMonitoreo[];
  /** p95 por día de la semana y franja de 4 h. */
  calor: CeldaMonitoreo[];
}

export interface CeldaMonitoreo {
  /** 1 = lunes … 7 = domingo. */
  dia: number;
  /** Hora en que empieza la franja de 4 h (0, 4, 8…). */
  desde: number;
  peticiones: number;
  p95: number;
}

export interface PuntoActividad {
  /** Día/hora («yyyy-MM-dd», «yyyy-MM-ddTHH:00») o lunes de la semana. */
  periodo: string;
  usuarios: number;
  acciones: number;
}

export interface CeldaActividad {
  /** 1 = lunes … 7 = domingo. */
  dia: number;
  /** 0–23 en la zona del negocio. */
  hora: number;
  acciones: number;
}

export interface PanelActividad {
  /** Clave del panel (PANEL_PICKING…), SESION u OTROS. */
  panel: string;
  usuarios: number;
  acciones: number;
}

export interface ResumenActividad {
  usuarios: number;
  usuariosPrevios: number;
  acciones: number;
  accionesPrevias: number;
  calor: CeldaActividad[];
  paneles: PanelActividad[];
  /** Usuarios activos por día (o por hora en 24 h). */
  serie: PuntoActividad[];
  /** Usuarios activos por semana, últimas 12. */
  semanas: PuntoActividad[];
}

export interface PuntoAuditoria {
  periodo: string;
  exito: number;
  fallo: number;
  denegado: number;
}

export interface Conteo {
  nombre: string;
  total: number;
}

export interface ResumenAuditoria {
  cambios: number;
  cambiosPrevios: number;
  fallidos: number;
  fallidosPrevios: number;
  denegados: number;
  denegadosPrevios: number;
  personas: number;
  serie: PuntoAuditoria[];
  entidades: Conteo[];
  denegadosPorUsuario: Conteo[];
  /** Todos los intentos por día de la semana y hora. */
  calor: { dia: number; hora: number; total: number }[];
  /** Cambios aplicados de los 8 usuarios más activos por tipo de registro. */
  matriz: { usuario: string; entidad: string; total: number }[];
}

export interface FiltrosAuditoria {
  dias: Periodo;
  usuario: string;
  registro: string;
  resultado: ResultadoAuditoria | '';
}
