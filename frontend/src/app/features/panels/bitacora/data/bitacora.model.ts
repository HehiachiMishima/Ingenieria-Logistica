export interface Centro {
  id: number;
  centro: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface Sistema {
  id: number;
  sistema: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface Area {
  id: number;
  area: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface Responsable {
  id: number;
  idDataArea: number;
  responsable: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface TipoIncidencia {
  id: number;
  idDataSistema: number;
  incidencia: string;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface CentroCrearRequest {
  centro: string;
}

export interface CentroActualizarRequest {
  centro?: string;
}

export interface SistemaCrearRequest {
  sistema: string;
}

export interface SistemaActualizarRequest {
  sistema?: string;
}

export interface AreaCrearRequest {
  area: string;
}

export interface AreaActualizarRequest {
  area?: string;
}

export interface ResponsableCrearRequest {
  responsable: string;
}

export interface ResponsableActualizarRequest {
  responsable?: string;
}

export interface TipoIncidenciaCrearRequest {
  incidencia: string;
}

export interface TipoIncidenciaActualizarRequest {
  incidencia?: string;
}

export interface Incidencia {
  id: number;
  idDataCentro: number;
  centro: string | null;
  idDataSistema: number;
  sistema: string | null;
  idDataIncidencia: number;
  tipoIncidencia: string | null;
  ticket: string;
  idDataArea: number;
  area: string | null;
  idDataResponsable: number;
  responsable: string | null;
  descripcion: string;
  horaInicio: string;
  horaFin: string;
  /** Duración en segundos; null si no aplica. */
  tiempoSolucionSeg: number | null;
  creadoEn: string;
  actualizadoEn: string | null;
  creadoPor: number | null;
  actualizadoPor: number | null;
}

export interface IncidenciaCrearRequest {
  idDataCentro: number;
  idDataSistema: number;
  idDataIncidencia: number;
  ticket: string;
  idDataArea: number;
  idDataResponsable: number;
  descripcion: string;
  horaInicio: string;
  horaFin: string;
  fecha: string;
}

export type IncidenciaActualizarRequest = IncidenciaCrearRequest;

export interface IncidenciaFiltros {
  centro?: number | null;
  sistema?: number | null;
  area?: number | null;
  responsable?: number | null;
  tipo?: number | null;
  desde?: string;
  hasta?: string;
  texto?: string;
}
